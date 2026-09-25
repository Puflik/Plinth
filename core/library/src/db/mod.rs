//! Хранилище библиотеки на SQLite (B2). Об FFI не знает; вызовы
//! блокирующие, соединение одно на базу.

mod backup;
mod codes;
mod connection;
mod integrity;
mod migrations;
pub mod query;
pub mod repo;
mod sql;

use std::path::PathBuf;

use plinth_types::CoreError;
use rusqlite::Connection;

use sql::Storage;

/// Открытая база. Репозитории — методы в `repo/*`.
pub struct Database {
    conn: Connection,
}

impl Database {
    /// Выполняет `work` одной транзакцией: ошибка — откат всего, что успели.
    /// Внутри нельзя звать методы, которые сами открывают транзакцию
    /// (`save_track`, `save_album`, `save_artist`).
    pub fn in_transaction<T>(&self, work: impl FnOnce(&Self) -> Result<T, CoreError>) -> Result<T, CoreError> {
        let tx = self.conn.unchecked_transaction().storage()?;
        let result = work(self)?;
        tx.commit().storage()?;
        Ok(result)
    }
}

/// Какое состояние журнала отражает проекция (C3): идентификатор журнала и
/// номер его последней записи. Не совпала с журналом — проекцию пересобирают.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct JournalMark {
    pub journal: i64,
    pub seq: i64,
}

/// Результат [`Database::open`].
pub struct Opened {
    pub db: Database,
    /// База оказалась повреждённой и создана заново. Каталог вернёт скан,
    /// пользовательское — журнал (C3); приложению — объяснить и пересканировать.
    pub recovery: Option<Recovery>,
}

#[derive(Debug)]
pub struct Recovery {
    /// Куда отложен повреждённый файл — для отчёта о сбое.
    pub quarantined: PathBuf,
    pub reason: String,
}

/// Когда проверять целостность файла (B2.3). Полная проверка идёт
/// секунды на большой базе, поэтому по умолчанию — раз в 20 запусков.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IntegrityCheck {
    Scheduled,
    Now,
    Skip,
}

const CHECK_EVERY_LAUNCHES: i64 = 20;

impl IntegrityCheck {
    fn due(self, launch: i64) -> bool {
        match self {
            Self::Scheduled => launch % CHECK_EVERY_LAUNCHES == 0,
            Self::Now => true,
            Self::Skip => false,
        }
    }
}
