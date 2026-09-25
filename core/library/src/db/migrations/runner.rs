//! Применение миграций (B3.1, B3.3).

use std::path::Path;

use plinth_types::CoreError;
use rusqlite::Connection;

use super::Migration;
use crate::db::backup::backup;

#[derive(Debug)]
pub(crate) enum MigrationError {
    /// База от более новой версии приложения — откат не поддерживается.
    Newer {
        found: i32,
        known: i32,
    },
    /// Миграция упала и откатилась; база осталась на предыдущей версии.
    Failed {
        version: i32,
        name: &'static str,
        error: rusqlite::Error,
    },
    Backup(CoreError),
    Sqlite(rusqlite::Error),
}

/// Доводит базу до последней миграции из `migrations`; возвращает
/// применённые версии. `file` — путь к базе: перед каждой миграцией базы,
/// которая уже была до этого открытия, с неё снимается копия (`db/backup.rs`).
pub(crate) fn migrate(
    conn: &mut Connection,
    migrations: &[Migration],
    file: Option<&Path>,
) -> Result<Vec<i32>, MigrationError> {
    let mut current: i32 =
        conn.query_row("PRAGMA user_version", [], |row| row.get(0)).map_err(MigrationError::Sqlite)?;
    let known = migrations.last().map_or(0, |m| m.version);
    if current > known {
        return Err(MigrationError::Newer { found: current, known });
    }
    let pending: Vec<&Migration> = migrations.iter().filter(|m| m.version > current).collect();
    // Копия нужна базе, которая была до этого открытия: новой терять нечего,
    // и между её первыми миграциями копии не снимаются.
    let existed = current > 0;
    let mut applied = Vec::new();
    for migration in pending {
        if let Some(path) = file.filter(|_| existed) {
            backup(conn, path, current).map_err(MigrationError::Backup)?;
        }
        let failed = |error| MigrationError::Failed { version: migration.version, name: migration.name, error };
        // Транзакция откатывается при выходе из области без commit — и при ошибке, и при панике.
        let tx = conn.transaction().map_err(MigrationError::Sqlite)?;
        (migration.apply)(&tx).map_err(failed)?;
        tx.pragma_update(None, "user_version", migration.version).map_err(failed)?;
        tx.commit().map_err(failed)?;
        current = migration.version;
        applied.push(current);
    }
    Ok(applied)
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use rusqlite::Connection;

    use super::{MigrationError, migrate};
    use crate::db::migrations::Migration;

    fn version(conn: &Connection) -> i32 {
        conn.query_row("PRAGMA user_version", [], |r| r.get(0)).unwrap()
    }

    fn has_table(conn: &Connection, name: &str) -> bool {
        conn.query_row("SELECT count(*) FROM sqlite_schema WHERE type = 'table' AND name = ?1", [name], |r| {
            r.get::<_, i64>(0)
        })
        .unwrap()
            == 1
    }

    const ONE: Migration = Migration { version: 1, name: "one", apply: |tx| tx.execute_batch("CREATE TABLE a (x)") };
    const TWO: Migration = Migration { version: 2, name: "two", apply: |tx| tx.execute_batch("CREATE TABLE b (x)") };
    /// Падает на середине: первая таблица уже создана — откат обязан её убрать.
    const BROKEN: Migration = Migration {
        version: 2,
        name: "broken",
        apply: |tx| tx.execute_batch("CREATE TABLE half (x); INSERT INTO no_such_table VALUES (1);"),
    };

    #[test]
    fn pending_migrations_run_in_order_and_set_the_version() {
        let mut conn = Connection::open_in_memory().unwrap();

        let applied = migrate(&mut conn, &[ONE, TWO], None).unwrap();

        assert_eq!(applied, vec![1, 2]);
        assert_eq!(version(&conn), 2);
        assert!(has_table(&conn, "a") && has_table(&conn, "b"));
    }

    #[test]
    fn only_the_missing_ones_run() {
        let mut conn = Connection::open_in_memory().unwrap();
        migrate(&mut conn, &[ONE], None).unwrap();

        let applied = migrate(&mut conn, &[ONE, TWO], None).unwrap();

        assert_eq!(applied, vec![2]);
        assert!(migrate(&mut conn, &[ONE, TWO], None).unwrap().is_empty());
    }

    /// Каждая миграция — в своей транзакции: упала вторая — первая осталась, вторая не оставила следов.
    #[test]
    fn failed_migration_rolls_back_and_keeps_the_previous_version() {
        let mut conn = Connection::open_in_memory().unwrap();

        let result = migrate(&mut conn, &[ONE, BROKEN], None);

        assert!(matches!(result, Err(MigrationError::Failed { version: 2, .. })));
        assert_eq!(version(&conn), 1);
        assert!(has_table(&conn, "a"));
        assert!(!has_table(&conn, "half"));
    }

    /// Откат версии приложения не поддерживается (ADR 0011): базу новее кода не трогаем.
    #[test]
    fn database_newer_than_the_code_is_refused() {
        let mut conn = Connection::open_in_memory().unwrap();
        migrate(&mut conn, &[ONE, TWO], None).unwrap();

        let result = migrate(&mut conn, &[ONE], None);

        assert!(matches!(result, Err(MigrationError::Newer { found: 2, known: 1 })));
        assert_eq!(version(&conn), 2);
    }

    /// Перед миграцией существующей базы — копия; пустой базе копия не нужна.
    /// Новая база проходит все миграции подряд: копии между ними не нужны —
    /// в ней ещё нечего терять, а файлы копий остались бы рядом навсегда.
    #[test]
    fn a_new_database_is_not_backed_up_between_its_migrations() {
        let dir = std::env::temp_dir().join(format!("plinth-migrate-new-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path: PathBuf = dir.join("plinth.db");
        let mut conn = Connection::open(&path).unwrap();

        migrate(&mut conn, &[ONE, TWO], Some(&path)).unwrap();

        assert!(crate::db::backup::backups(&path).unwrap().is_empty());
        drop(conn);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn backup_is_taken_before_migrating_an_existing_database() {
        let dir = std::env::temp_dir().join(format!("plinth-migrate-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path: PathBuf = dir.join("plinth.db");
        let mut conn = Connection::open(&path).unwrap();

        migrate(&mut conn, &[ONE], Some(&path)).unwrap();
        assert!(crate::db::backup::backups(&path).unwrap().is_empty(), "fresh database needs no backup");

        conn.execute("INSERT INTO a VALUES (42)", []).unwrap();
        migrate(&mut conn, &[ONE, TWO], Some(&path)).unwrap();

        let backups = crate::db::backup::backups(&path).unwrap();
        assert_eq!(backups.len(), 1);
        let copy = Connection::open(&backups[0]).unwrap();
        assert_eq!(version(&copy), 1);
        assert_eq!(copy.query_row("SELECT x FROM a", [], |r| r.get::<_, i64>(0)).unwrap(), 42);
        drop((conn, copy));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
