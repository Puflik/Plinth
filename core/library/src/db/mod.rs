//! Хранилище библиотеки на SQLite (B2). Об FFI не знает; вызовы
//! блокирующие, соединение одно на базу.

mod backup;
mod codes;
mod connection;
mod integrity;
mod migrations;
pub mod query;
#[cfg(test)]
mod r1_5_tests;
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
    /// Методы, которым нужна своя транзакция (`save_track` и подобные),
    /// входят в эту. Вложенный `in_transaction` — ошибка.
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
    /// База оказалась повреждённой или миграция на ней не прошла: файл
    /// отложен целиком, база создана заново. Каталог вернёт скан,
    /// пользовательское — журнал (C3); приложению — объяснить и пересканировать.
    pub recovery: Option<Recovery>,
    /// Плановая проверка целостности (раз в 20 запусков, [`IntegrityCheck::Scheduled`])
    /// пришлась на это открытие. Открытие её не делает: её делает
    /// [`Database::check_file`] после открытия, в фоне.
    pub integrity_due: bool,
}

/// Итог [`Database::check_file`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Checked {
    Healthy,
    /// Нашлась порча; рядом с базой — метка `<база>.damaged`, и следующее
    /// [`Database::open`] отложит файл и создаст базу заново.
    Damaged {
        reason: String,
    },
}

#[derive(Debug)]
pub struct Recovery {
    /// Куда отложен файл — для отчёта о сбое: `<база>.corrupt-<мс>` у
    /// повреждённого, `<база>.migration-failed-<мс>` у того, на котором не
    /// прошла миграция.
    pub quarantined: PathBuf,
    /// Что нашлось в файле: порча (в том числе из метки проверки рядом) или
    /// `migration <версия> (<имя>) failed: …`.
    pub reason: String,
}

/// Когда проверять целостность файла (B2.3). Полная проверка идёт
/// секунды на большой базе: она не должна держать открытие.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IntegrityCheck {
    /// По расписанию, раз в 20 запусков, но не на открытии: открытие только
    /// отмечает [`Opened::integrity_due`], проверку делает
    /// [`Database::check_file`] после него.
    Scheduled,
    /// На самом открытии, каждый раз: порча — замена файла сразу. Для тестов
    /// и эталонов.
    Now,
    /// Никогда.
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
