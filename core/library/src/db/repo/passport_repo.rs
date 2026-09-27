//! Паспорта треков из каталога и смена ID трека (C4, ADR 0007): после
//! переустановки скан выдаёт файлам новые ID, журнал узнаёт по паспорту свой
//! трек, и каталог берёт ID из журнала.

use plinth_types::{CoreError, EntityId, TrackId};
use rusqlite::{OptionalExtension, Row, params};

use crate::db::Database;
use crate::db::sql::{Storage, atomically, id, opt_duration, opt_mbid};
use crate::model::TrackPassport;

/// Трек с его первой версией — оригиналом, если он есть, — и альбомом её.
const PASSPORTS: &str = "SELECT t.id, t.title, t.artist_credit, a.title AS album, v.duration_ms, v.mbid_recording
     FROM track t
     LEFT JOIN version v
         ON v.id = (SELECT id FROM version WHERE track = t.id ORDER BY kind <> 'original', id LIMIT 1)
     LEFT JOIN album a ON a.id = v.album";

impl Database {
    /// Паспорт трека каталога; трека нет — `None`.
    pub fn track_passport(&self, track: TrackId) -> Result<Option<TrackPassport>, CoreError> {
        let found = self
            .conn()
            .query_row(&format!("{PASSPORTS} WHERE t.id = ?1"), [track.as_bytes()], read_passport)
            .optional()
            .storage()?;
        let Some(mut passport) = found else { return Ok(None) };
        passport.sources = self.online_sources(track)?;
        Ok(Some(passport))
    }

    /// Паспорта всех треков каталога — для перепривязки: сравниваются
    /// описания, источники провайдеров ей не нужны.
    pub fn track_passports(&self) -> Result<Vec<TrackPassport>, CoreError> {
        let mut statement = self.conn().prepare(&format!("{PASSPORTS} ORDER BY t.id")).storage()?;
        statement.query_map([], read_passport).storage()?.collect::<Result<_, _>>().storage()
    }

    /// Трек `from` становится треком `to` со всеми версиями, файлами и
    /// артистами. Пользовательское не трогается: оно и так по ID журнала.
    /// Трек `to` уже есть — ошибка, каталог прежний.
    pub fn rekey_track(&self, from: TrackId, to: TrackId) -> Result<(), CoreError> {
        atomically(self.conn(), |tx| {
            // Ссылки версий и артистов на трек проверяются в конце транзакции,
            // когда они уже переведены на новый ID.
            tx.execute_batch("PRAGMA defer_foreign_keys = ON").storage()?;
            let (from, to) = (from.as_bytes(), to.as_bytes());
            tx.execute("UPDATE track SET id = ?2 WHERE id = ?1", params![from, to]).storage()?;
            tx.execute("UPDATE version SET track = ?2 WHERE track = ?1", params![from, to]).storage()?;
            tx.execute("UPDATE track_artist SET track = ?2 WHERE track = ?1", params![from, to]).storage()?;
            Ok(())
        })
    }
}

fn read_passport(row: &Row<'_>) -> rusqlite::Result<TrackPassport> {
    Ok(TrackPassport {
        track: id(row, "id")?,
        title: row.get("title")?,
        artist: row.get("artist_credit")?,
        album: row.get("album")?,
        duration: opt_duration(row, "duration_ms")?,
        mbid: opt_mbid(row, "mbid_recording")?,
        sources: Vec::new(),
    })
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_types::{CoreError, TrackId};

    use crate::db::repo::fixtures::{artist, creep, db, local_source, track, version};
    use crate::model::TrackPassport;

    #[test]
    fn a_catalog_track_has_a_passport_from_its_first_version() {
        let db = db();
        let (_, album, creep, _, _) = creep(&db);

        let passport = db.track_passport(creep.id).unwrap();

        assert_eq!(
            passport,
            Some(TrackPassport {
                track: creep.id,
                title: "Creep".to_owned(),
                artist: "Radiohead".to_owned(),
                album: Some(album.title),
                duration: Some(Duration::from_millis(238_640)),
                mbid: None,
                sources: Vec::new(),
            })
        );
        assert_eq!(db.track_passport(TrackId::new()).unwrap(), None);
    }

    #[test]
    fn a_track_without_versions_has_a_bare_passport() {
        let db = db();
        let singer = artist("Земфира");
        db.save_artist(&singer).unwrap();
        let bare = track("Хочешь?", &singer, 5);
        db.save_track(&bare).unwrap();

        let passports = db.track_passports().unwrap();

        assert_eq!(passports.len(), 1);
        assert_eq!((passports[0].album.as_deref(), passports[0].duration), (None, None));
        assert_eq!(passports[0].artist, "Земфира");
    }

    #[test]
    fn every_catalog_track_has_one_passport() {
        let db = db();
        let (radiohead, album, creep, _, _) = creep(&db);
        let other = track("Anyone Can Play Guitar", &radiohead, 2_000);
        db.save_track(&other).unwrap();
        // Две версии — паспорт один, по первой.
        for number in [4, 5] {
            db.save_version(&version(&other, Some(&album), number)).unwrap();
        }

        let mut tracks: Vec<TrackId> = db.track_passports().unwrap().into_iter().map(|p| p.track).collect();
        tracks.sort();

        let mut expected = vec![creep.id, other.id];
        expected.sort();
        assert_eq!(tracks, expected);
    }

    /// Трек переезжает на ID журнала целиком: файл, версии и артисты — за ним.
    #[test]
    fn a_rekeyed_track_keeps_its_versions_files_and_artists() {
        let db = db();
        let (radiohead, _, creep, creep_version, source) = creep(&db);
        let journal_id = TrackId::new();

        db.rekey_track(creep.id, journal_id).unwrap();

        assert_eq!(db.track(creep.id).unwrap(), None);
        let moved = db.track(journal_id).unwrap().unwrap();
        assert_eq!((moved.title.as_str(), moved.artists.as_slice()), ("Creep", [radiohead.id].as_slice()));
        assert_eq!(db.versions_of(journal_id).unwrap(), [crate::model::Version { track: journal_id, ..creep_version }]);
        let known = db.known_local_files().unwrap();
        let file = known.get("content://media/external/audio/media/42").unwrap();
        assert_eq!((file.track, file.source), (journal_id, source.id));
    }

    #[test]
    fn a_rekey_onto_an_existing_track_changes_nothing() {
        let db = db();
        let (radiohead, _, creep, _, _) = creep(&db);
        let other = track("Anyone Can Play Guitar", &radiohead, 2_000);
        db.save_track(&other).unwrap();
        let other_version = version(&other, None, 1);
        db.save_version(&other_version).unwrap();
        db.save_source(&local_source(&other_version, "/music/guitar.flac")).unwrap();

        let result = db.rekey_track(creep.id, other.id);

        assert!(matches!(result, Err(CoreError::Storage { .. })), "{result:?}");
        assert_eq!(db.track(creep.id).unwrap().unwrap().title, "Creep");
        assert_eq!(db.versions_of(other.id).unwrap(), [other_version]);
    }
}
