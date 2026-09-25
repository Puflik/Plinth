//! Целостность файла базы (B2.3): проверка и карантин повреждённого файла.

use std::path::{Path, PathBuf};

use plinth_types::{CoreError, Timestamp};
use rusqlite::{Connection, ErrorCode};

/// Что нашёл `PRAGMA integrity_check`; пусто — файл цел. Ошибку чтения
/// отдаёт как есть: по её коду видно, порча это или нет.
pub(crate) fn problems(conn: &Connection) -> rusqlite::Result<Vec<String>> {
    let mut statement = conn.prepare("PRAGMA integrity_check")?;
    let rows: Vec<String> = statement.query_map([], |row| row.get(0))?.collect::<Result<_, _>>()?;
    Ok(if rows == ["ok"] { Vec::new() } else { rows })
}

/// Ошибка, которая значит «файл испорчен», а не «запрос неверен».
pub(crate) fn is_corruption(error: &rusqlite::Error) -> bool {
    matches!(error.sqlite_error_code(), Some(ErrorCode::DatabaseCorrupt | ErrorCode::NotADatabase))
}

/// Откладывает файл базы вместе с `-wal` и `-shm` в `<имя>.corrupt-<мс>`.
/// Не удаляет: по нему можно разобрать, что случилось.
pub(crate) fn quarantine(path: &Path) -> Result<PathBuf, CoreError> {
    let stamp = Timestamp::now().as_millis();
    let target = PathBuf::from(format!("{}.corrupt-{stamp}", path.display()));
    std::fs::rename(path, &target).map_err(|e| CoreError::storage(format!("quarantine: {e}")))?;
    for suffix in ["-wal", "-shm"] {
        let side = PathBuf::from(format!("{}{suffix}", path.display()));
        if side.exists() {
            let _ = std::fs::rename(&side, format!("{}{suffix}", target.display()));
        }
    }
    Ok(target)
}
