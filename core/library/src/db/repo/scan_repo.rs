//! Учёт сканера (D1): какие локальные файлы уже в каталоге и какими они
//! были при прошлом скане.

use std::collections::{HashMap, HashSet};

use plinth_types::{Availability, CoreError, EntityId, SourceId, Timestamp, TrackId, VersionId};
use rusqlite::{OptionalExtension, params};

use crate::db::Database;
use crate::db::codes::Code;
use crate::db::sql::{Storage, first_id, id, opt_timestamp};

/// Локальный файл, который каталог уже знает.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KnownFile {
    pub source: SourceId,
    pub version: VersionId,
    pub track: TrackId,
    /// Время изменения и размер при прошлом скане; `None` — файл не сканировался.
    pub modified_at: Option<Timestamp>,
    pub size: Option<u64>,
    pub available: bool,
}

impl Database {
    /// Все локальные источники по пути файла.
    pub fn known_local_files(&self) -> Result<HashMap<String, KnownFile>, CoreError> {
        let mut statement = self
            .conn()
            .prepare(
                "SELECT s.local_uri, s.id AS source, s.version, v.track, f.modified_at, f.size, s.availability
                 FROM source s
                 JOIN version v ON v.id = s.version
                 LEFT JOIN scan_file f ON f.source = s.id
                 WHERE s.local_uri IS NOT NULL",
            )
            .storage()?;
        let rows = statement
            .query_map([], |row| {
                let size: Option<i64> = row.get("size")?;
                let availability: String = row.get("availability")?;
                Ok((
                    row.get::<_, String>("local_uri")?,
                    KnownFile {
                        source: id(row, "source")?,
                        version: id(row, "version")?,
                        track: id(row, "track")?,
                        modified_at: opt_timestamp(row, "modified_at")?,
                        size: size.and_then(|s| u64::try_from(s).ok()),
                        available: availability == Availability::Available.code(),
                    },
                ))
            })
            .storage()?;
        rows.collect::<Result<_, _>>().storage()
    }

    /// Треки каталога, на которые у пользователя есть данные: строка `track_user`,
    /// запись плейлиста, прослушивание, решение о склейке (обе стороны), блокировка
    /// трека — то же, что журнал считает данными (`referenced` в `core/sync`).
    pub fn tracks_with_user_data(&self) -> Result<HashSet<TrackId>, CoreError> {
        let mut statement = self
            .conn()
            .prepare(
                "SELECT track FROM track_user
                 UNION SELECT track FROM playlist_entry
                 UNION SELECT track FROM play_event
                 UNION SELECT track_low FROM merge_decision
                 UNION SELECT track_high FROM merge_decision
                 UNION SELECT target FROM block_entry WHERE kind = 'track'",
            )
            .storage()?;
        statement.query_map([], first_id::<TrackId>).storage()?.collect::<Result<_, _>>().storage()
    }

    /// Трек, которому сейчас принадлежит версия; версии нет — `None`. ID версии и
    /// источника перепривязка не меняет, ID трека — меняет.
    pub fn track_of_version(&self, version: VersionId) -> Result<Option<TrackId>, CoreError> {
        self.conn()
            .query_row("SELECT track FROM version WHERE id = ?1", [version.as_bytes()], |row| id(row, "track"))
            .optional()
            .storage()
    }

    /// Каким файл источника был при этом скане и в какой папке от корня тома лежит.
    pub fn save_file_stamp(
        &self,
        source: SourceId,
        modified_at: Timestamp,
        size: u64,
        folder: &str,
    ) -> Result<(), CoreError> {
        self.conn()
            .execute(
                "INSERT INTO scan_file(source, modified_at, size, folder) VALUES (?1, ?2, ?3, ?4)
                 ON CONFLICT(source) DO UPDATE SET modified_at = excluded.modified_at, size = excluded.size,
                     folder = excluded.folder",
                params![source.as_bytes(), modified_at.as_millis(), i64::try_from(size).unwrap_or(i64::MAX), folder],
            )
            .storage()
            .map(drop)
    }

    /// Убирает из каталога альбомы без записей и артистов без треков и
    /// альбомов — остатки от сменившихся тегов. Артист, на которого подписан
    /// человек или которого он заблокировал, остаётся: журнал помнит его ID.
    pub fn prune_catalog(&self) -> Result<(), CoreError> {
        self.conn()
            .execute_batch(
                "DELETE FROM album WHERE id NOT IN (SELECT album FROM version WHERE album IS NOT NULL);
                 DELETE FROM artist
                 WHERE id NOT IN (SELECT artist FROM track_artist)
                   AND id NOT IN (SELECT artist FROM album_artist)
                   AND id NOT IN (SELECT artist FROM subscription)
                   AND id NOT IN (SELECT target FROM block_entry WHERE kind = 'artist');",
            )
            .storage()
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashSet;

    use plinth_types::{Timestamp, TrackId, VersionId};

    use plinth_types::Timestamp as Time;

    use crate::db::repo::fixtures::{album, artist, creep, db};
    use crate::model::{BlockEntry, BlockTarget, Subscription, TrackUserData};

    #[test]
    fn local_files_are_known_by_path_with_their_stamp() {
        let db = db();
        let (_, _, track, version, source) = creep(&db);
        let crate::model::SourceLocation::Local { uri } = &source.location else { unreachable!() };

        let before = db.known_local_files().unwrap();
        db.save_file_stamp(source.id, Timestamp::from_millis(1_700_000_000_000), 4_321, "Music/").unwrap();
        let after = db.known_local_files().unwrap();

        let known = &before[uri];
        assert_eq!((known.source, known.version, known.track), (source.id, version.id, track.id));
        assert_eq!((known.modified_at, known.size), (None, None));
        assert!(known.available);
        assert_eq!(after[uri].modified_at, Some(Timestamp::from_millis(1_700_000_000_000)));
        assert_eq!(after[uri].size, Some(4_321));
    }

    /// Учёт сканера уходит вместе с источником: удалили трек — и его файла нет.
    #[test]
    fn the_stamp_goes_with_its_source() {
        let db = db();
        let (_, _, track, _, source) = creep(&db);
        db.save_file_stamp(source.id, Timestamp::from_millis(1), 1, "Music/").unwrap();

        db.delete_track(track.id).unwrap();

        assert!(db.known_local_files().unwrap().is_empty());
    }

    #[test]
    fn pruning_drops_what_nothing_uses() {
        let db = db();
        let (radiohead, used_album, track, _, _) = creep(&db);
        let (lonely, gone) = (artist("Lonely"), artist("Gone"));
        db.save_artist(&lonely).unwrap();
        db.save_artist(&gone).unwrap();
        let empty_album = album(&gone);
        db.save_album(&empty_album).unwrap();

        db.prune_catalog().unwrap();

        assert!(db.album(used_album.id).unwrap().is_some());
        assert!(db.album(empty_album.id).unwrap().is_none());
        assert!(db.artist(radiohead.id).unwrap().is_some());
        assert!(db.artist(lonely.id).unwrap().is_none());
        assert!(db.artist(gone.id).unwrap().is_none());
        assert!(db.track(track.id).unwrap().is_some());
    }

    /// Артист только альбома (album artist) — нужен.
    #[test]
    fn an_album_artist_is_kept() {
        let db = db();
        let (_, mut used_album, _, _, _) = creep(&db);
        let massive = artist("Massive Attack");
        db.save_artist(&massive).unwrap();
        used_album.artists.push(massive.id);
        db.save_album(&used_album).unwrap();

        db.prune_catalog().unwrap();

        assert!(db.artist(massive.id).unwrap().is_some());
    }

    /// Подписку и блокировку помнит журнал по ID артиста — такой артист остаётся.
    #[test]
    fn artists_the_journal_refers_to_are_kept() {
        let db = db();
        let (subscribed, blocked) = (artist("Subscribed"), artist("Blocked"));
        db.save_artist(&subscribed).unwrap();
        db.save_artist(&blocked).unwrap();
        db.subscribe(&Subscription { artist: subscribed.id, since: Time::from_millis(1) }).unwrap();
        db.block(&BlockEntry { target: BlockTarget::Artist(blocked.id), since: Time::from_millis(1) }).unwrap();

        db.prune_catalog().unwrap();

        assert!(db.artist(subscribed.id).unwrap().is_some());
        assert!(db.artist(blocked.id).unwrap().is_some());
    }

    /// Голый трек данных не несёт, лайк — несёт, снятый лайк строки не оставляет.
    /// Плейлист, прослушивание и обе стороны склейки — в `core/sync`
    /// (`r9c_user_data_set`): там данные ставит сам журнал.
    #[test]
    fn a_liked_track_has_user_data_and_a_cleared_like_leaves_none() {
        let db = db();
        let (_, _, track, _, _) = creep(&db);
        assert!(db.tracks_with_user_data().unwrap().is_empty(), "голый трек");

        db.save_user_data(&TrackUserData { liked: true, ..TrackUserData::empty(track.id) }).unwrap();
        assert_eq!(db.tracks_with_user_data().unwrap(), HashSet::from([track.id]));

        db.save_user_data(&TrackUserData::empty(track.id)).unwrap();
        assert!(db.tracks_with_user_data().unwrap().is_empty(), "снятый лайк");
    }

    /// Блокировка трека — данные трека, блокировка артиста — нет: в наборе только ID треков.
    #[test]
    fn only_a_blocked_track_counts_as_user_data() {
        let db = db();
        let (artist, _, track, _, _) = creep(&db);

        db.block(&BlockEntry { target: BlockTarget::Artist(artist.id), since: Time::from_millis(1) }).unwrap();
        assert!(db.tracks_with_user_data().unwrap().is_empty(), "артист заблокирован, треки нет");

        db.block(&BlockEntry { target: BlockTarget::Track(track.id), since: Time::from_millis(2) }).unwrap();
        assert_eq!(db.tracks_with_user_data().unwrap(), HashSet::from([track.id]));
    }

    /// Версия знает свой трек; нет такой версии — нет и трека. После `rekey_track`
    /// версия идёт за треком: ID версии и источника перепривязка не меняет.
    #[test]
    fn a_version_knows_its_track_and_follows_it_when_the_id_changes() {
        let db = db();
        let (_, _, track, version, _) = creep(&db);

        assert_eq!(db.track_of_version(version.id).unwrap(), Some(track.id));
        assert_eq!(db.track_of_version(VersionId::new()).unwrap(), None);

        let restored = TrackId::new();
        db.rekey_track(track.id, restored).unwrap();
        assert_eq!(db.track_of_version(version.id).unwrap(), Some(restored));
    }
}
