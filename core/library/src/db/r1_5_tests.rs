//! Р1.5 (`docs/work/r1-5.md`): копия, которая не снялась, не останавливает
//! миграции; провал миграции знает, была ли база до открытия; сбой среды
//! отличается от ошибки на данных.

use rusqlite::{Connection, ffi};

use super::integrity::is_environmental;
use super::migrations::{Migration, MigrationError, migrate};

const ONE: Migration = Migration { version: 1, name: "one", apply: |tx| tx.execute_batch("CREATE TABLE a (x)") };
const TWO: Migration = Migration { version: 2, name: "two", apply: |tx| tx.execute_batch("CREATE TABLE b (x)") };
/// Падает на данных: таблицы, в которую она пишет, нет.
const BROKEN: Migration =
    Migration { version: 2, name: "broken", apply: |tx| tx.execute_batch("INSERT INTO no_such_table VALUES (1)") };

fn version(conn: &Connection) -> i32 {
    conn.query_row("PRAGMA user_version", [], |r| r.get(0)).unwrap()
}

/// Копия не снялась, и это не порча (здесь — нет каталога): предупреждение в
/// лог, миграции идут без копии, данные на месте.
#[test]
fn an_unwritable_backup_does_not_stop_the_migration() {
    let missing = std::env::temp_dir().join(format!("plinth-r1-5-missing-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&missing);
    let mut conn = Connection::open_in_memory().unwrap();
    migrate(&mut conn, &[ONE], None).unwrap();
    conn.execute("INSERT INTO a VALUES (42)", []).unwrap();

    let result = migrate(&mut conn, &[ONE, TWO], Some(&missing.join("plinth.db")));

    assert!(matches!(result.as_deref(), Ok([2])), "{result:?}");
    assert_eq!(version(&conn), 2);
    assert_eq!(conn.query_row("SELECT x FROM a", [], |r| r.get::<_, i64>(0)).unwrap(), 42);
    assert!(!missing.exists(), "a copy does not make directories");
}

/// `existed` — была ли база до этого открытия. Провал на ней откладывает
/// файл; на базе, которую создало это же открытие, — ошибка.
#[test]
fn a_failed_migration_knows_whether_the_database_existed() {
    let mut fresh = Connection::open_in_memory().unwrap();
    let mut old = Connection::open_in_memory().unwrap();
    migrate(&mut old, &[ONE], None).unwrap();

    let on_fresh = migrate(&mut fresh, &[ONE, BROKEN], None);
    let on_old = migrate(&mut old, &[ONE, BROKEN], None);

    assert!(matches!(on_fresh, Err(MigrationError::Failed { version: 2, existed: false, .. })), "{on_fresh:?}");
    assert!(matches!(on_old, Err(MigrationError::Failed { version: 2, existed: true, .. })), "{on_old:?}");
}

/// Сбой среды — повтор может пройти, файл не трогают; остальное — ошибка на
/// данных. Расширенный код (`SQLITE_IOERR_WRITE`) решается по основному.
#[test]
fn environment_errors_are_told_from_data_errors() {
    let sqlite = |code| rusqlite::Error::SqliteFailure(ffi::Error::new(code), None);

    for code in [ffi::SQLITE_FULL, ffi::SQLITE_IOERR, ffi::SQLITE_IOERR_WRITE, ffi::SQLITE_BUSY, ffi::SQLITE_CANTOPEN] {
        assert!(is_environmental(&sqlite(code)), "{code}");
    }
    for code in [ffi::SQLITE_ERROR, ffi::SQLITE_CONSTRAINT, ffi::SQLITE_CORRUPT] {
        assert!(!is_environmental(&sqlite(code)), "{code}");
    }
    assert!(!is_environmental(&rusqlite::Error::ExecuteReturnedResults));
}
