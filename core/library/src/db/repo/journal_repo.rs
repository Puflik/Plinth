//! Служебное проекции журнала (C3): метка «докуда спроецировано» и очистка
//! таблиц проекции перед пересборкой.

use plinth_types::CoreError;
use rusqlite::{OptionalExtension, params};

use crate::db::sql::Storage;
use crate::db::{Database, JournalMark};

/// Таблицы проекции журнала (`m0001_initial`); записи — раньше плейлистов,
/// у них внешний ключ на плейлист.
const PROJECTION_TABLES: [&str; 8] = [
    "track_user",
    "playlist_entry",
    "playlist",
    "play_event",
    "merge_decision",
    "subscription",
    "block_entry",
    "setting",
];

impl Database {
    /// Какое состояние журнала отражает база; `None` — никакое.
    pub fn journal_mark(&self) -> Result<Option<JournalMark>, CoreError> {
        self.conn()
            .query_row(
                "SELECT j.value, s.value FROM meta j JOIN meta s
                 WHERE j.key = 'journal_id' AND s.key = 'journal_seq'",
                [],
                |row| Ok(JournalMark { journal: row.get(0)?, seq: row.get(1)? }),
            )
            .optional()
            .storage()
    }

    pub fn set_journal_mark(&self, mark: JournalMark) -> Result<(), CoreError> {
        let mut statement =
            self.conn().prepare_cached("INSERT OR REPLACE INTO meta(key, value) VALUES (?1, ?2)").storage()?;
        statement.execute(params!["journal_id", mark.journal]).storage()?;
        statement.execute(params!["journal_seq", mark.seq]).storage().map(drop)
    }

    /// Стирает всё, что отражает журнал, вместе с меткой. Каталог не трогается.
    pub fn clear_projection(&self) -> Result<(), CoreError> {
        for table in PROJECTION_TABLES {
            self.conn().execute(&format!("DELETE FROM {table}"), []).storage()?;
        }
        self.conn().execute("DELETE FROM meta WHERE key IN ('journal_id', 'journal_seq')", []).storage().map(drop)
    }
}

#[cfg(test)]
mod tests {
    use plinth_types::{CoreError, TrackId};

    use crate::db::JournalMark;
    use crate::db::repo::fixtures::{creep, db};
    use crate::model::{Setting, TrackUserData, VersionPreference};

    #[test]
    fn journal_mark_round_trips() {
        let db = db();
        assert_eq!(db.journal_mark().unwrap(), None);

        db.set_journal_mark(JournalMark { journal: -7, seq: 42 }).unwrap();

        assert_eq!(db.journal_mark().unwrap(), Some(JournalMark { journal: -7, seq: 42 }));
    }

    /// Пересборка стирает только отражение журнала — каталог остаётся.
    #[test]
    fn clearing_the_projection_keeps_the_catalog() {
        let db = db();
        let (_, _, saved, _, _) = creep(&db);
        let liked = TrackUserData { liked: true, ..TrackUserData::empty(TrackId::new()) };
        db.save_user_data(&liked).unwrap();
        db.save_setting(Setting::VersionPreference(VersionPreference::Any)).unwrap();
        db.set_journal_mark(JournalMark { journal: 1, seq: 1 }).unwrap();

        db.clear_projection().unwrap();

        assert!(db.all_user_data().unwrap().is_empty());
        assert_eq!(db.synced_settings().unwrap().version_preference, VersionPreference::Original);
        assert_eq!(db.journal_mark().unwrap(), None);
        assert!(db.track(saved.id).unwrap().is_some());
    }

    #[test]
    fn failed_transaction_leaves_nothing() {
        let db = db();
        let data = TrackUserData { liked: true, ..TrackUserData::empty(TrackId::new()) };

        let result: Result<(), CoreError> = db.in_transaction(|db| {
            db.save_user_data(&data)?;
            Err(CoreError::internal("boom"))
        });

        assert!(result.is_err());
        assert!(db.all_user_data().unwrap().is_empty());
        let done = db.in_transaction(|db| db.save_user_data(&data));
        assert_eq!(done, Ok(()));
        assert_eq!(db.user_data(data.track).unwrap(), data);
    }
}
