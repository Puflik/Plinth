//! Открытие базы (B2.1, B2.3): `PRAGMA`, схема, счётчик запусков,
//! проверка целостности и замена повреждённого файла.

use std::path::Path;

use plinth_types::CoreError;
use rusqlite::{Connection, OptionalExtension};

use super::integrity::{is_corruption, problems, quarantine};
use super::migrations::{MIGRATIONS, MigrationError, migrate};
use super::sql::Storage;
use super::{Database, IntegrityCheck, Opened, Recovery};

impl Database {
    /// Открывает базу по пути, создавая её при необходимости. Испорченный
    /// файл откладывается в сторону и заменяется пустой базой — об этом
    /// говорит [`Opened::recovery`]. База из более новой версии
    /// приложения — ошибка: откат версии не поддерживается (B3).
    pub fn open(path: &Path, check: IntegrityCheck) -> Result<Opened, CoreError> {
        match Self::open_checked(path, check) {
            Ok(db) => Ok(Opened { db, recovery: None }),
            // Соединение уже закрыто: `open_checked` вернулся.
            Err(Failure::Corrupt(reason)) => Self::replace(path, reason),
            Err(Failure::Other(error)) => Err(error),
        }
    }

    /// База в памяти — для тестов.
    pub fn open_in_memory() -> Result<Self, CoreError> {
        let conn = Connection::open_in_memory().storage()?;
        Self::prepare(conn, None).map_err(Failure::into_error)
    }

    /// Что нашёл `integrity_check`; пусто — файл цел.
    pub fn integrity_problems(&self) -> Result<Vec<String>, CoreError> {
        problems(&self.conn).storage()
    }

    /// Версия схемы базы (`PRAGMA user_version`).
    pub fn schema_version(&self) -> Result<i32, CoreError> {
        self.conn.query_row("PRAGMA user_version", [], |row| row.get(0)).storage()
    }

    /// Сколько раз база открывалась, включая этот.
    pub fn launches(&self) -> Result<i64, CoreError> {
        self.conn
            .query_row("SELECT value FROM meta WHERE key = 'launches'", [], |row| row.get(0))
            .optional()
            .storage()
            .map(Option::unwrap_or_default)
    }

    pub(crate) fn conn(&self) -> &Connection {
        &self.conn
    }

    /// Порча может всплыть на любом шаге — при чтении схемы, счётчика или
    /// в самой проверке: любая такая ошибка ведёт к замене файла.
    fn open_checked(path: &Path, check: IntegrityCheck) -> Result<Self, Failure> {
        let db = Self::open_file(path)?;
        let launch = db.count_launch().map_err(Failure::from)?;
        if check.due(launch) {
            let found = problems(&db.conn).map_err(Failure::from)?;
            if !found.is_empty() {
                return Err(Failure::Corrupt(found.join("; ")));
            }
        }
        Ok(db)
    }

    fn open_file(path: &Path) -> Result<Self, Failure> {
        let conn = Connection::open(path).map_err(Failure::from)?;
        Self::prepare(conn, Some(path))
    }

    fn prepare(conn: Connection, file: Option<&Path>) -> Result<Self, Failure> {
        // WAL: читатели не ждут писателя; NORMAL в WAL не теряет целостность.
        conn.pragma_update(None, "journal_mode", "WAL").map_err(Failure::from)?;
        conn.pragma_update(None, "synchronous", "NORMAL").map_err(Failure::from)?;
        conn.pragma_update(None, "foreign_keys", true).map_err(Failure::from)?;
        conn.busy_timeout(std::time::Duration::from_secs(5)).map_err(Failure::from)?;
        let mut db = Self { conn };
        migrate(&mut db.conn, MIGRATIONS, file).map_err(Failure::from)?;
        Ok(db)
    }

    fn count_launch(&self) -> rusqlite::Result<i64> {
        self.conn.query_row(
            "INSERT INTO meta(key, value) VALUES ('launches', 1)
             ON CONFLICT(key) DO UPDATE SET value = value + 1 RETURNING value",
            [],
            |row| row.get(0),
        )
    }

    fn replace(path: &Path, reason: String) -> Result<Opened, CoreError> {
        let quarantined = quarantine(path)?;
        let db = Self::open_file(path).map_err(Failure::into_error)?;
        db.count_launch().storage()?;
        Ok(Opened { db, recovery: Some(Recovery { quarantined, reason }) })
    }
}

enum Failure {
    Corrupt(String),
    Other(CoreError),
}

impl From<rusqlite::Error> for Failure {
    fn from(error: rusqlite::Error) -> Self {
        if is_corruption(&error) {
            Self::Corrupt(error.to_string())
        } else {
            Self::Other(CoreError::storage(error.to_string()))
        }
    }
}

impl From<MigrationError> for Failure {
    fn from(error: MigrationError) -> Self {
        match error {
            MigrationError::Newer { found, known } => Self::Other(CoreError::storage(format!(
                "database schema {found} is newer than this app ({known}); downgrade is not supported"
            ))),
            // Порча, найденная миграцией, — та же порча: файл заменяется.
            MigrationError::Failed { error, .. } | MigrationError::Sqlite(error) if is_corruption(&error) => {
                Self::Corrupt(error.to_string())
            }
            MigrationError::Failed { version, name, error } => {
                Self::Other(CoreError::storage(format!("migration {version} ({name}) failed: {error}")))
            }
            MigrationError::Sqlite(error) => Self::from(error),
            MigrationError::Backup(error) => Self::Other(error),
        }
    }
}

impl Failure {
    fn into_error(self) -> CoreError {
        match self {
            Self::Corrupt(reason) => CoreError::storage(reason),
            Self::Other(error) => error,
        }
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::io::{Seek, SeekFrom, Write};
    use std::path::PathBuf;

    use plinth_types::CoreError;

    use crate::db::{Database, IntegrityCheck};

    /// Каталог под базу теста; удаляется вместе с содержимым.
    struct TempDir(PathBuf);

    impl TempDir {
        fn new(name: &str) -> Self {
            let dir = std::env::temp_dir().join(format!("plinth-db-{name}-{}", std::process::id()));
            let _ = fs::remove_dir_all(&dir);
            fs::create_dir_all(&dir).unwrap();
            Self(dir)
        }

        fn db(&self) -> PathBuf {
            self.0.join("plinth.db")
        }

        fn quarantined(&self) -> Vec<String> {
            let mut names: Vec<String> = fs::read_dir(&self.0)
                .unwrap()
                .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
                .filter(|n| n.contains(".corrupt-"))
                .collect();
            names.sort();
            names
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn setting_count(db: &Database) -> i64 {
        db.conn().query_row("SELECT count(*) FROM setting", [], |r| r.get(0)).unwrap()
    }

    #[test]
    fn fresh_file_gets_the_schema_wal_and_foreign_keys() {
        let dir = TempDir::new("fresh");

        let opened = Database::open(&dir.db(), IntegrityCheck::Skip).unwrap();

        let conn = opened.db.conn();
        let version: i32 = conn.query_row("PRAGMA user_version", [], |r| r.get(0)).unwrap();
        let mode: String = conn.query_row("PRAGMA journal_mode", [], |r| r.get(0)).unwrap();
        let fk: i32 = conn.query_row("PRAGMA foreign_keys", [], |r| r.get(0)).unwrap();
        let latest = i32::try_from(crate::db::migrations::MIGRATIONS.len()).unwrap();
        assert_eq!((version, mode.as_str(), fk), (latest, "wal", 1));
        assert!(opened.recovery.is_none());
    }

    #[test]
    fn reopening_keeps_data_and_counts_launches() {
        let dir = TempDir::new("reopen");
        {
            let opened = Database::open(&dir.db(), IntegrityCheck::Skip).unwrap();
            opened.db.conn().execute("INSERT INTO setting(key, value) VALUES ('a', 'b')", []).unwrap();
            assert_eq!(opened.db.launches().unwrap(), 1);
        }

        let opened = Database::open(&dir.db(), IntegrityCheck::Skip).unwrap();

        assert_eq!(setting_count(&opened.db), 1);
        assert_eq!(opened.db.launches().unwrap(), 2);
    }

    #[test]
    fn healthy_database_passes_the_check() {
        let dir = TempDir::new("healthy");
        drop(Database::open(&dir.db(), IntegrityCheck::Skip).unwrap());

        let opened = Database::open(&dir.db(), IntegrityCheck::Now).unwrap();

        assert!(opened.recovery.is_none());
        assert!(opened.db.integrity_problems().unwrap().is_empty());
    }

    /// Файл вместо базы — мусор: он откладывается в сторону, база создаётся заново.
    #[test]
    fn garbage_file_is_quarantined_and_replaced() {
        let dir = TempDir::new("garbage");
        fs::write(dir.db(), vec![0xA5_u8; 64 * 1024]).unwrap();

        let opened = Database::open(&dir.db(), IntegrityCheck::Skip).unwrap();

        let recovery = opened.recovery.expect("recovered");
        assert!(recovery.quarantined.exists());
        assert_eq!(dir.quarantined().len(), 1);
        assert_eq!(setting_count(&opened.db), 0);
    }

    /// База с двумя тысячами строк; соединение закрыто, WAL сброшен в файл.
    fn filled(dir: &TempDir) {
        let opened = Database::open(&dir.db(), IntegrityCheck::Skip).unwrap();
        let conn = opened.db.conn();
        for i in 0..2_000 {
            conn.execute("INSERT INTO setting(key, value) VALUES (?1, ?2)", (format!("k{i}"), "v".repeat(40))).unwrap();
        }
    }

    fn damage(dir: &TempDir, from: u64, pages: usize) {
        let mut file = fs::OpenOptions::new().write(true).open(dir.db()).unwrap();
        file.seek(SeekFrom::Start(from)).unwrap();
        file.write_all(&vec![0xFF; 4096 * pages]).unwrap();
    }

    /// DoD эпика B: `integrity_check` ловит специально испорченный файл.
    /// Испорчены страницы данных в конце файла — при открытии их никто не
    /// читает, найти порчу может только проверка.
    #[test]
    fn damaged_data_pages_are_caught_by_the_check() {
        let dir = TempDir::new("damaged");
        filled(&dir);
        let length = fs::metadata(dir.db()).unwrap().len();
        damage(&dir, length - 4096 * 6, 3);

        let unchecked = Database::open(&dir.db(), IntegrityCheck::Skip).unwrap();
        assert!(unchecked.recovery.is_none(), "without the check the damage stays unnoticed");
        drop(unchecked);

        let opened = Database::open(&dir.db(), IntegrityCheck::Now).unwrap();

        let recovery = opened.recovery.expect("recovered");
        assert!(recovery.reason.contains("page") || recovery.reason.contains("malformed"), "{}", recovery.reason);
        assert_eq!(dir.quarantined().len(), 1);
        assert_eq!(setting_count(&opened.db), 0);
        assert!(opened.db.integrity_problems().unwrap().is_empty());
    }

    /// Испорчено то, что читается при каждом открытии, — замена сразу, без проверки.
    #[test]
    fn damage_met_while_opening_is_recovered_too() {
        let dir = TempDir::new("damaged-early");
        filled(&dir);
        damage(&dir, 4096 * 2, 4);

        let opened = Database::open(&dir.db(), IntegrityCheck::Skip).unwrap();

        assert!(opened.recovery.is_some());
        assert_eq!(setting_count(&opened.db), 0);
    }

    /// Версию приложения не откатывают (B3): базу новее схемы не трогаем и не выбрасываем.
    #[test]
    fn newer_schema_is_an_error_not_a_reset() {
        let dir = TempDir::new("newer");
        {
            let opened = Database::open(&dir.db(), IntegrityCheck::Skip).unwrap();
            opened.db.conn().execute_batch("PRAGMA user_version = 99").unwrap();
        }

        let result = Database::open(&dir.db(), IntegrityCheck::Skip);

        assert!(matches!(result, Err(CoreError::Storage { .. })));
        assert!(dir.quarantined().is_empty());
    }

    #[test]
    fn check_runs_every_twentieth_launch() {
        assert!(!IntegrityCheck::Scheduled.due(1));
        assert!(!IntegrityCheck::Scheduled.due(19));
        assert!(IntegrityCheck::Scheduled.due(20));
        assert!(IntegrityCheck::Scheduled.due(40));
        assert!(IntegrityCheck::Now.due(3));
        assert!(!IntegrityCheck::Skip.due(20));
    }
}
