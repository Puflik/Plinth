//! Учёт сканера (D1): какие локальные файлы уже в каталоге и какими они
//! были при прошлом скане.

use std::collections::HashMap;

use plinth_types::{Availability, CoreError, EntityId, SourceId, Timestamp, TrackId, VersionId};
use rusqlite::params;

use crate::db::Database;
use crate::db::codes::Code;
use crate::db::sql::{Storage, id, opt_timestamp};

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

    /// Каким файл источника был при этом скане.
    pub fn save_file_stamp(&self, source: SourceId, modified_at: Timestamp, size: u64) -> Result<(), CoreError> {
        self.conn()
            .execute(
                "INSERT INTO scan_file(source, modified_at, size) VALUES (?1, ?2, ?3)
                 ON CONFLICT(source) DO UPDATE SET modified_at = excluded.modified_at, size = excluded.size",
                params![source.as_bytes(), modified_at.as_millis(), i64::try_from(size).unwrap_or(i64::MAX)],
            )
            .storage()
            .map(drop)
    }
}

#[cfg(test)]
mod tests {
    use plinth_types::Timestamp;

    use crate::db::repo::fixtures::{creep, db};

    #[test]
    fn local_files_are_known_by_path_with_their_stamp() {
        let db = db();
        let (_, _, track, version, source) = creep(&db);
        let crate::model::SourceLocation::Local { uri } = &source.location else { unreachable!() };

        let before = db.known_local_files().unwrap();
        db.save_file_stamp(source.id, Timestamp::from_millis(1_700_000_000_000), 4_321).unwrap();
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
        db.save_file_stamp(source.id, Timestamp::from_millis(1), 1).unwrap();

        db.delete_track(track.id).unwrap();

        assert!(db.known_local_files().unwrap().is_empty());
    }
}
