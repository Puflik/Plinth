use plinth_types::{CoreError, EntityId, TrackId};
use rusqlite::{OptionalExtension, Row, params};

use crate::db::Database;
use crate::db::codes::{Code, column};
use crate::db::sql::{Storage, atomically, first_id, id, millis, opt_duration, opt_id, opt_mbid, timestamp};
use crate::model::{AlbumPlacement, Fingerprint, Track, Version};
use crate::sort::sort_key;
use crate::text::normalize;

impl Database {
    pub fn save_track(&self, track: &Track) -> Result<(), CoreError> {
        atomically(self.conn(), |tx| {
            // UPSERT, а не REPLACE: REPLACE удалил бы строку и каскадом — версии трека.
            tx.execute(
                "INSERT INTO track(id, title, title_normalized, artist_credit, artist_normalized, mbid_work, added_at,
                                   title_sort, artist_sort, sort_artist_credit)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
                 ON CONFLICT(id) DO UPDATE SET
                     title = excluded.title, title_normalized = excluded.title_normalized,
                     artist_credit = excluded.artist_credit, artist_normalized = excluded.artist_normalized,
                     mbid_work = excluded.mbid_work, added_at = excluded.added_at,
                     title_sort = excluded.title_sort, artist_sort = excluded.artist_sort,
                     sort_artist_credit = excluded.sort_artist_credit",
                params![
                    track.id.as_bytes(),
                    track.title,
                    normalize(&track.title),
                    track.artist_credit,
                    normalize(&track.artist_credit),
                    track.mbid_work.map(|m| m.to_string()),
                    track.added_at.as_millis(),
                    sort_key(&track.title),
                    sort_key(track.sort_artist_credit.as_deref().unwrap_or(&track.artist_credit)),
                    track.sort_artist_credit
                ],
            )
            .storage()?;
            tx.execute("DELETE FROM track_artist WHERE track = ?1", [track.id.as_bytes()]).storage()?;
            for (ord, artist) in (0_i64..).zip(&track.artists) {
                tx.execute(
                    "INSERT OR IGNORE INTO track_artist(track, artist, ord) VALUES (?1, ?2, ?3)",
                    params![track.id.as_bytes(), artist.as_bytes(), ord],
                )
                .storage()?;
            }
            Ok(())
        })
    }

    pub fn track(&self, id: TrackId) -> Result<Option<Track>, CoreError> {
        let found = self
            .conn()
            .query_row("SELECT * FROM track WHERE id = ?1", [id.as_bytes()], read_track)
            .optional()
            .storage()?;
        let Some(mut track) = found else { return Ok(None) };
        let mut statement =
            self.conn().prepare("SELECT artist FROM track_artist WHERE track = ?1 ORDER BY ord").storage()?;
        track.artists =
            statement.query_map([id.as_bytes()], first_id).storage()?.collect::<Result<_, _>>().storage()?;
        Ok(Some(track))
    }

    /// Удаляет трек из каталога с версиями и источниками. Пользовательское
    /// (лайки, история) — проекция журнала — остаётся: трек может вернуться.
    pub fn delete_track(&self, id: TrackId) -> Result<(), CoreError> {
        self.conn().execute("DELETE FROM track WHERE id = ?1", [id.as_bytes()]).storage().map(drop)
    }

    pub fn save_version(&self, version: &Version) -> Result<(), CoreError> {
        let album = version.album;
        self.conn()
            .execute(
                "INSERT INTO version(id, track, kind, explicitness, duration_ms, album, disc, number, release_year,
                                     mbid_recording, fingerprint)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
                 ON CONFLICT(id) DO UPDATE SET
                     track = excluded.track, kind = excluded.kind, explicitness = excluded.explicitness,
                     duration_ms = excluded.duration_ms, album = excluded.album, disc = excluded.disc,
                     number = excluded.number, release_year = excluded.release_year,
                     mbid_recording = excluded.mbid_recording, fingerprint = excluded.fingerprint",
                params![
                    version.id.as_bytes(),
                    version.track.as_bytes(),
                    version.kind.code(),
                    version.explicitness.code(),
                    version.duration.map(millis),
                    album.map(|a| *a.album.as_bytes()),
                    album.and_then(|a| a.disc),
                    album.and_then(|a| a.number),
                    version.release_year,
                    version.mbid_recording.map(|m| m.to_string()),
                    version.fingerprint.as_ref().map(|f| f.0.as_str())
                ],
            )
            .storage()
            .map(drop)
    }

    /// Версии трека: сначала оригиналы, дальше по порядку создания.
    pub fn versions_of(&self, track: TrackId) -> Result<Vec<Version>, CoreError> {
        let mut statement =
            self.conn().prepare("SELECT * FROM version WHERE track = ?1 ORDER BY kind <> 'original', id").storage()?;
        statement.query_map([track.as_bytes()], read_version).storage()?.collect::<Result<_, _>>().storage()
    }
}

fn read_track(row: &Row<'_>) -> rusqlite::Result<Track> {
    Ok(Track {
        id: id(row, "id")?,
        title: row.get("title")?,
        artist_credit: row.get("artist_credit")?,
        sort_artist_credit: row.get("sort_artist_credit")?,
        artists: Vec::new(),
        mbid_work: opt_mbid(row, "mbid_work")?,
        added_at: timestamp(row, "added_at")?,
    })
}

fn read_version(row: &Row<'_>) -> rusqlite::Result<Version> {
    let album = opt_id(row, "album")?.map(|album| AlbumPlacement {
        album,
        disc: row.get("disc").ok().flatten(),
        number: row.get("number").ok().flatten(),
    });
    Ok(Version {
        id: id(row, "id")?,
        track: id(row, "track")?,
        kind: column(row, "kind")?,
        explicitness: column(row, "explicitness")?,
        duration: opt_duration(row, "duration_ms")?,
        album,
        release_year: row.get("release_year")?,
        mbid_recording: opt_mbid(row, "mbid_recording")?,
        fingerprint: row.get::<_, Option<String>>("fingerprint")?.map(Fingerprint),
    })
}

#[cfg(test)]
mod tests {
    use plinth_types::Timestamp;

    use crate::db::repo::fixtures::{artist, creep, db, track, version};
    use crate::model::{Explicitness, TrackUserData, VersionKind};

    /// Скан пишет каталог пачками в одной транзакции: сохранение трека в неё
    /// входит, а не открывает вложенную (её SQLite не умеет).
    #[test]
    fn saving_joins_an_open_transaction_and_rolls_back_with_it() {
        let db = db();
        let radiohead = artist("Radiohead");
        let creep = track("Creep", &radiohead, 1);

        let kept = db.in_transaction(|db| {
            db.save_artist(&radiohead)?;
            db.save_track(&creep)
        });
        let other = artist("Muse");
        let rolled_back: Result<(), plinth_types::CoreError> = db.in_transaction(|db| {
            db.save_artist(&other)?;
            Err(plinth_types::CoreError::internal("roll back"))
        });

        assert_eq!(kept, Ok(()));
        assert_eq!(db.track(creep.id).unwrap().map(|t| t.artists), Some(vec![radiohead.id]));
        assert!(rolled_back.is_err());
        assert!(db.artist(other.id).unwrap().is_none());
    }

    #[test]
    fn track_and_versions_round_trip() {
        let db = db();
        let (_, _, track, original, _) = creep(&db);
        let mut live = version(&track, None, 0);
        live.kind = VersionKind::Live;
        live.explicitness = Explicitness::Clean;
        live.duration = None;
        db.save_version(&live).unwrap();

        assert_eq!(db.track(track.id).unwrap(), Some(track.clone()));
        assert_eq!(db.versions_of(track.id).unwrap(), vec![original, live]);
    }

    /// Пересохранение трека (новые теги) не теряет его версии и источники.
    #[test]
    fn saving_a_track_again_keeps_its_versions() {
        let db = db();
        let (_, _, mut track, original, source) = creep(&db);

        track.title = "Creep (Acoustic)".to_owned();
        track.added_at = Timestamp::from_millis(5);
        db.save_track(&track).unwrap();

        assert_eq!(db.track(track.id).unwrap().unwrap().title, "Creep (Acoustic)");
        assert_eq!(db.versions_of(track.id).unwrap(), vec![original.clone()]);
        assert_eq!(db.sources_of(original.id).unwrap(), vec![source]);
    }

    /// Каталог уходит каскадом, пользовательское остаётся — его источник журнал.
    #[test]
    fn deleting_a_track_takes_catalog_but_not_user_data() {
        let db = db();
        let (_, _, track, original, _) = creep(&db);
        let mut liked = TrackUserData::empty(track.id);
        liked.liked = true;
        db.save_user_data(&liked).unwrap();

        db.delete_track(track.id).unwrap();

        assert_eq!(db.track(track.id).unwrap(), None);
        assert!(db.versions_of(track.id).unwrap().is_empty());
        assert!(db.sources_of(original.id).unwrap().is_empty());
        assert_eq!(db.user_data(track.id).unwrap(), liked);
    }

    #[test]
    fn version_of_an_unknown_track_is_refused() {
        let db = db();
        let stranger = crate::db::repo::fixtures::track("Ghost", &artist("Nobody"), 0);

        assert!(db.save_version(&version(&stranger, None, 1)).is_err());
    }
}
