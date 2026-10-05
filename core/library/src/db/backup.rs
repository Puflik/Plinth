//! Копия базы перед обновлением (B3.2): одна на открытие, с той версии, на
//! которой база была, — `<база>.bak-<мс>-v<версия>`, три последних
//! обновления. Копия — через `VACUUM INTO`: целостная и в режиме WAL, без
//! остановки соединения. Пишется во временный `<база>.bak.tmp` и потом
//! переименовывается: оборванная копия — ни при ошибке, ни при убитом
//! процессе — копией не считается и законченные не вытесняет.

use std::io;
use std::path::{Path, PathBuf};

use plinth_types::{CoreError, Timestamp};
use rusqlite::Connection;

/// Сколько обновлений хранит копия: по одной копии на обновление.
pub(crate) const KEEP: usize = 3;

/// Почему копия не снялась. `Io` — шаг и `ErrorKind`, без путей: текст идёт в лог (§17.3).
#[derive(Debug)]
pub(crate) enum BackupError {
    Sqlite(rusqlite::Error),
    Io(String),
}

impl BackupError {
    /// Шаг и код для лога. Текст ошибки SQLite несёт полный путь — его здесь нет.
    pub(crate) fn describe(&self) -> String {
        match self {
            Self::Sqlite(error) => match error.sqlite_error() {
                Some(failure) => {
                    format!("VACUUM INTO: SQLite {:?}, extended code {}", failure.code, failure.extended_code)
                }
                None => "VACUUM INTO: not a SQLite failure".to_owned(),
            },
            Self::Io(step) => step.clone(),
        }
    }
}

fn io_error(step: &str, error: &io::Error) -> BackupError {
    BackupError::Io(format!("{step}: {:?}", error.kind()))
}

/// Снимает копию базы версии `from`, лишние старые удаляет. Сбой ротации не
/// ошибка: копия к этому времени снята.
pub(crate) fn backup(conn: &Connection, db: &Path, from: i32) -> Result<PathBuf, BackupError> {
    let db_text = db.to_str().ok_or_else(|| BackupError::Io("database path is not UTF-8".to_owned()))?;
    let temp_text = format!("{db_text}.bak.tmp");
    let temp = Path::new(&temp_text);
    // Застарелый `.bak.tmp` — след оборванной копии. `VACUUM INTO` в непустой
    // файл не пишет и своего при ошибке не удаляет; в мусорный упал бы с
    // NOTADB, и здоровую базу отложили бы как испорченную.
    match std::fs::remove_file(temp) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(io_error("remove the unfinished copy", &error)),
    }
    if let Err(error) = conn.execute("VACUUM INTO ?1", [temp_text.as_str()]) {
        let _ = std::fs::remove_file(temp);
        return Err(BackupError::Sqlite(error));
    }
    let target = free_name(db_text, from);
    if let Err(error) = std::fs::rename(temp, &target) {
        let _ = std::fs::remove_file(temp);
        return Err(io_error("rename the finished copy", &error));
    }
    rotate(db);
    Ok(target)
}

/// Имя копии, которого ещё нет: миллисекунды — время окончания копии.
fn free_name(db: &str, from: i32) -> PathBuf {
    let mut millis = Timestamp::now().as_millis();
    loop {
        let candidate = PathBuf::from(format!("{db}.bak-{millis:013}-v{from}"));
        if !candidate.exists() {
            return candidate;
        }
        millis += 1;
    }
}

/// Лишние старые копии удаляются. Копия уже снята — сбой уборки не ошибка.
fn rotate(db: &Path) {
    let found = match backups(db) {
        Ok(found) => found,
        Err(error) => {
            log::warn!("backup rotation: {error}");
            return;
        }
    };
    for old in found.iter().rev().skip(KEEP) {
        if let Err(error) = std::fs::remove_file(old) {
            log::warn!("backup rotation: removing an old copy failed: {:?}", error.kind());
        }
    }
}

/// Законченные копии базы, от старой к новой. `.bak.tmp` не в счёте.
pub(crate) fn backups(db: &Path) -> Result<Vec<PathBuf>, CoreError> {
    let prefix = format!("{}.bak-", db.file_name().map(|n| n.to_string_lossy()).unwrap_or_default());
    let dir = db.parent().filter(|d| !d.as_os_str().is_empty()).unwrap_or(Path::new("."));
    let mut found: Vec<PathBuf> = std::fs::read_dir(dir)
        .map_err(|e| CoreError::storage(format!("backups: {e}")))?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| path.file_name().is_some_and(|n| n.to_string_lossy().starts_with(&prefix)))
        .collect();
    // Миллисекунды — 13 цифр с нулями: порядок имён — порядок времени.
    found.sort();
    Ok(found)
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use rusqlite::Connection;

    use super::{BackupError, KEEP, backup, backups};

    #[test]
    fn only_the_newest_backups_are_kept() {
        let dir = std::env::temp_dir().join(format!("plinth-backup-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path: PathBuf = dir.join("plinth.db");
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch("CREATE TABLE t (x); INSERT INTO t VALUES (1);").unwrap();

        let made: Vec<PathBuf> = (1..=5).map(|from| backup(&conn, &path, from).unwrap()).collect();

        let kept = backups(&path).unwrap();
        assert_eq!(kept.len(), KEEP);
        assert_eq!(kept, made[made.len() - KEEP..].to_vec());
        assert!(!made[0].exists());
        drop(conn);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Копия появляется под своим именем целиком; временного файла после неё нет.
    #[test]
    fn a_finished_copy_leaves_no_temporary_file() {
        let dir = std::env::temp_dir().join(format!("plinth-backup-tmp-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path: PathBuf = dir.join("plinth.db");
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch("CREATE TABLE t (x)").unwrap();

        let made = backup(&conn, &path, 1).unwrap();

        assert_eq!(backups(&path).unwrap(), vec![made]);
        assert!(!dir.join("plinth.db.bak.tmp").exists());
        drop(conn);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Застарелый временный файл, который не удалось убрать, копию не начинает:
    /// `VACUUM INTO` в чужой файл не пишет, а в мусорный упал бы как в порчу.
    #[test]
    fn a_leftover_that_cannot_be_removed_stops_the_copy() {
        let dir = std::env::temp_dir().join(format!("plinth-backup-stuck-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("plinth.db.bak.tmp")).unwrap();
        let path: PathBuf = dir.join("plinth.db");
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch("CREATE TABLE t (x)").unwrap();

        let result = backup(&conn, &path, 1);

        assert!(matches!(result, Err(BackupError::Io(_))), "{result:?}");
        assert!(backups(&path).unwrap().is_empty());
        drop(conn);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
