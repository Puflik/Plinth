//! Мелочь для репозиториев: ошибки SQLite → `CoreError`, чтение
//! идентификаторов, длительностей и текстовых полей.

use std::time::Duration;

use plinth_types::{CoreError, EntityId, Mbid, Timestamp};
use rusqlite::types::FromSqlError;
use rusqlite::{Connection, Row, types::Type};

pub(crate) trait Storage<T> {
    /// Ошибка SQLite — это ошибка хранилища.
    fn storage(self) -> Result<T, CoreError>;
}

impl<T> Storage<T> for rusqlite::Result<T> {
    fn storage(self) -> Result<T, CoreError> {
        self.map_err(|error| CoreError::storage(error.to_string()))
    }
}

pub(crate) fn id<T: EntityId>(row: &Row<'_>, column: &str) -> rusqlite::Result<T> {
    row.get::<_, [u8; 16]>(column).map(T::from_bytes)
}

/// Идентификатор из первой колонки — для выборок связок (`SELECT artist …`).
pub(crate) fn first_id<T: EntityId>(row: &Row<'_>) -> rusqlite::Result<T> {
    row.get::<_, [u8; 16]>(0).map(T::from_bytes)
}

pub(crate) fn opt_id<T: EntityId>(row: &Row<'_>, column: &str) -> rusqlite::Result<Option<T>> {
    Ok(row.get::<_, Option<[u8; 16]>>(column)?.map(T::from_bytes))
}

pub(crate) fn timestamp(row: &Row<'_>, column: &str) -> rusqlite::Result<Timestamp> {
    row.get(column).map(Timestamp::from_millis)
}

pub(crate) fn opt_timestamp(row: &Row<'_>, column: &str) -> rusqlite::Result<Option<Timestamp>> {
    Ok(row.get::<_, Option<i64>>(column)?.map(Timestamp::from_millis))
}

pub(crate) fn millis(duration: Duration) -> i64 {
    i64::try_from(duration.as_millis()).unwrap_or(i64::MAX)
}

pub(crate) fn opt_duration(row: &Row<'_>, column: &str) -> rusqlite::Result<Option<Duration>> {
    Ok(row.get::<_, Option<i64>>(column)?.and_then(|ms| u64::try_from(ms).ok()).map(Duration::from_millis))
}

pub(crate) fn opt_mbid(row: &Row<'_>, column: &str) -> rusqlite::Result<Option<Mbid>> {
    row.get::<_, Option<String>>(column)?.map(|text| text.parse().map_err(conversion)).transpose()
}

/// Значение в колонке есть, но в тип модели не превращается.
fn conversion(error: impl std::error::Error + Send + Sync + 'static) -> rusqlite::Error {
    rusqlite::Error::FromSqlConversionFailure(0, Type::Text, Box::new(error))
}

pub(crate) fn invalid(column: &str, value: &str) -> rusqlite::Error {
    conversion(FromSqlError::Other(format!("{column}: unexpected value {value:?}").into()))
}

/// Выполняет `work` атомарно: в своей транзакции, а если транзакция уже
/// открыта (`Database::in_transaction`, пачка скана), — в ней. Вложенных
/// транзакций SQLite не умеет.
pub(crate) fn atomically<T>(
    conn: &Connection,
    work: impl FnOnce(&Connection) -> Result<T, CoreError>,
) -> Result<T, CoreError> {
    if !conn.is_autocommit() {
        return work(conn);
    }
    let tx = conn.unchecked_transaction().storage()?;
    let result = work(&tx)?;
    tx.commit().storage()?;
    Ok(result)
}
