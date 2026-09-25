//! Копия базы перед миграцией (B3.2): `<база>.bak-<мс>-v<версия>`, три
//! последних. Копия — через `VACUUM INTO`: целостная и в режиме WAL, без
//! остановки соединения.

use std::path::{Path, PathBuf};

use plinth_types::{CoreError, Timestamp};
use rusqlite::Connection;

use super::sql::Storage;

pub(crate) const KEEP: usize = 3;

/// Снимает копию базы версии `from`, лишние старые удаляет.
pub(crate) fn backup(conn: &Connection, db: &Path, from: i32) -> Result<PathBuf, CoreError> {
    let mut millis = Timestamp::now().as_millis();
    let target = loop {
        let candidate = PathBuf::from(format!("{}.bak-{millis:013}-v{from}", db.display()));
        if !candidate.exists() {
            break candidate;
        }
        millis += 1;
    };
    let target_text = target.to_str().ok_or_else(|| CoreError::storage("backup path is not UTF-8"))?;
    conn.execute("VACUUM INTO ?1", [target_text]).storage()?;
    for old in backups(db)?.iter().rev().skip(KEEP) {
        std::fs::remove_file(old).map_err(|e| CoreError::storage(format!("backup rotation: {e}")))?;
    }
    Ok(target)
}

/// Копии базы, от старой к новой.
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

    use super::{KEEP, backup, backups};

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
}
