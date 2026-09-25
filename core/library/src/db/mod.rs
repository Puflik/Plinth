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

use rusqlite::Connection;

/// Открытая база. Репозитории — методы в `repo/*`.
pub struct Database {
    conn: Connection,
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
