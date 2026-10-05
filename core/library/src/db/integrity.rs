//! Целостность файла базы (B2.3): проверка, откладывание файла в сторону —
//! повреждённого (карантин) или такого, на котором не прошла миграция, — и
//! метка `<база>.damaged`, которой проверка рядом передаёт найденную порчу
//! следующему открытию.

use std::io;
use std::path::{Path, PathBuf};

use plinth_types::{CoreError, Timestamp};
use rusqlite::{Connection, ErrorCode};

/// Причина замены, если метка пуста или не читается.
const MARK_WITHOUT_REASON: &str = "integrity check found damage";

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

/// Сбой среды, а не данных: место, права, блокировка, ввод-вывод. Повтор
/// может пройти, а файл тут ни при чём — его не откладывают.
pub(crate) fn is_environmental(error: &rusqlite::Error) -> bool {
    matches!(
        error.sqlite_error_code(),
        Some(
            ErrorCode::DiskFull
                | ErrorCode::OutOfMemory
                | ErrorCode::SystemIoFailure
                | ErrorCode::DatabaseBusy
                | ErrorCode::DatabaseLocked
                | ErrorCode::ReadOnly
                | ErrorCode::PermissionDenied
                | ErrorCode::CannotOpen
                | ErrorCode::FileLockingProtocolFailed
                | ErrorCode::OperationInterrupted
        )
    )
}

/// Код ошибки SQLite для лога и текста ошибки: её собственный текст бывает с
/// полным путём (§17.3).
pub(crate) fn describe(error: &rusqlite::Error) -> String {
    match error.sqlite_error() {
        Some(failure) => format!("SQLite {:?}, extended code {}", failure.code, failure.extended_code),
        None => "not a SQLite failure".to_owned(),
    }
}

fn mark_path(db: &Path) -> PathBuf {
    PathBuf::from(format!("{}.damaged", db.display()))
}

/// Оставляет рядом с базой метку с причиной: проверка нашла порчу, и следующее
/// открытие отложит файл. В саму базу не пишет — она испорчена.
pub(crate) fn leave_mark(db: &Path, reason: &str) -> Result<(), CoreError> {
    std::fs::write(mark_path(db), reason).map_err(|e| {
        CoreError::storage(format!("integrity check: damage found, the mark was not written: {:?}", e.kind()))
    })
}

/// Причина из метки, если базу пора отложить. Метка снимается здесь, до
/// карантина: оборванный между ними переход оставит испорченную базу без
/// метки — её найдёт следующая проверка, — а не отложит новую здоровую.
/// `None` — метки нет, метка без базы (она тоже снимается) или метку не
/// удалось снять: тогда база открывается как есть, иначе метку нашла бы и
/// новая база, и откладывалась бы на каждом запуске.
pub(crate) fn take_mark(db: &Path) -> Option<String> {
    let mark = mark_path(db);
    let text = match std::fs::read(&mark) {
        Ok(bytes) => String::from_utf8_lossy(&bytes).into_owned(),
        Err(error) if error.kind() == io::ErrorKind::NotFound => return None,
        Err(_) => String::new(),
    };
    if let Err(error) = std::fs::remove_file(&mark) {
        log::warn!("integrity: the damage mark was not removed ({:?}); opening the database as it is", error.kind());
        return None;
    }
    if !db.exists() {
        return None;
    }
    Some(if text.trim().is_empty() { MARK_WITHOUT_REASON.to_owned() } else { text })
}

/// Откладывает файл базы вместе с `-wal` и `-shm` в `<имя>.<label>-<мс>`;
/// `label` — `corrupt` или `migration-failed`. Не удаляет: по нему можно
/// разобрать, что случилось.
pub(crate) fn quarantine(path: &Path, label: &str) -> Result<PathBuf, CoreError> {
    let stamp = Timestamp::now().as_millis();
    let target = PathBuf::from(format!("{}.{label}-{stamp}", path.display()));
    std::fs::rename(path, &target).map_err(|e| CoreError::storage(format!("quarantine: {e}")))?;
    for suffix in ["-wal", "-shm"] {
        let side = PathBuf::from(format!("{}{suffix}", path.display()));
        if side.exists() {
            let _ = std::fs::rename(&side, format!("{}{suffix}", target.display()));
        }
    }
    Ok(target)
}
