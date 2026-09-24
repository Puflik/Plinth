//! Логи Rust → `AppLog` (A3.4). Ядро пишет через фасад `log`, записи уходят
//! в Kotlin через [`CoreLogger`] — там их ждут вырезание личных данных, файл
//! `files/logs/plinth.log` и logcat отладочной сборки. Уровень отсекается
//! здесь: отброшенная запись границу не пересекает.

use std::cell::Cell;
use std::sync::{Arc, PoisonError, RwLock};

/// Уровни `log`; в Kotlin `Trace` пишется как `DEBUG`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CoreLogLevel {
    Error,
    Warn,
    Info,
    Debug,
    Trace,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CoreLogRecord {
    pub level: CoreLogLevel,
    /// Модуль-источник, например `plinth_ffi::panic`; в Kotlin — тег записи.
    pub target: String,
    pub message: String,
}

/// Приёмник логов на стороне Kotlin — `CoreLogBridge`.
#[uniffi::export(with_foreign)]
pub trait CoreLogger: Send + Sync {
    fn log(&self, record: CoreLogRecord);
}

static LOGGER: RwLock<Option<Arc<dyn CoreLogger>>> = RwLock::new(None);
static FORWARDER: Forwarder = Forwarder;

thread_local! {
    static FORWARDING: Cell<bool> = const { Cell::new(false) };
}

/// Ставит приёмник и уровень. Повторный вызов заменяет и то и другое.
pub(crate) fn install(logger: Arc<dyn CoreLogger>, level: CoreLogLevel) {
    *LOGGER.write().unwrap_or_else(PoisonError::into_inner) = Some(logger);
    // Err — логгер `log` уже стоит (наш же, с прошлого вызова): менять нечего.
    let _ = log::set_logger(&FORWARDER);
    log::set_max_level(level.into());
}

/// Идёт ли сейчас в этом потоке передача записи в Kotlin. Паника в этот
/// момент — сбой самого приёмника: писать о ней в тот же приёмник нельзя.
pub(crate) fn forwarding() -> bool {
    FORWARDING.with(Cell::get)
}

struct Forwarder;

impl log::Log for Forwarder {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        metadata.level() <= log::max_level()
    }

    fn log(&self, record: &log::Record) {
        if !self.enabled(record.metadata()) || forwarding() {
            return;
        }
        // Замок не держится во время вызова: приёмник — чужой код.
        let logger = LOGGER.read().unwrap_or_else(PoisonError::into_inner).clone();
        let Some(logger) = logger else { return };
        let _forwarding = ForwardingFlag::raise();
        logger.log(CoreLogRecord {
            level: record.level().into(),
            target: record.target().to_owned(),
            message: record.args().to_string(),
        });
    }

    fn flush(&self) {}
}

/// Флаг [`forwarding`] снимается и при раскрутке паники из приёмника.
struct ForwardingFlag;

impl ForwardingFlag {
    fn raise() -> Self {
        FORWARDING.with(|flag| flag.set(true));
        Self
    }
}

impl Drop for ForwardingFlag {
    fn drop(&mut self) {
        FORWARDING.with(|flag| flag.set(false));
    }
}

impl From<log::Level> for CoreLogLevel {
    fn from(level: log::Level) -> Self {
        match level {
            log::Level::Error => Self::Error,
            log::Level::Warn => Self::Warn,
            log::Level::Info => Self::Info,
            log::Level::Debug => Self::Debug,
            log::Level::Trace => Self::Trace,
        }
    }
}

impl From<CoreLogLevel> for log::LevelFilter {
    fn from(level: CoreLogLevel) -> Self {
        match level {
            CoreLogLevel::Error => Self::Error,
            CoreLogLevel::Warn => Self::Warn,
            CoreLogLevel::Info => Self::Info,
            CoreLogLevel::Debug => Self::Debug,
            CoreLogLevel::Trace => Self::Trace,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{CoreLogLevel, CoreLogRecord};
    use crate::testing::{Capture, serial};

    #[test]
    fn records_reach_the_installed_logger() {
        let _serial = serial();
        let capture = Capture::install(CoreLogLevel::Debug);

        log::info!(target: "plinth_test", "scanned {} files", 3);

        assert_eq!(
            capture.records(),
            vec![CoreLogRecord {
                level: CoreLogLevel::Info,
                target: "plinth_test".to_owned(),
                message: "scanned 3 files".to_owned(),
            }]
        );
    }

    #[test]
    fn records_below_the_level_never_cross_the_boundary() {
        let _serial = serial();
        let capture = Capture::install(CoreLogLevel::Warn);

        log::info!(target: "plinth_test", "quiet");
        log::debug!(target: "plinth_test", "quieter");
        log::warn!(target: "plinth_test", "loud");

        let levels: Vec<_> = capture.records().into_iter().map(|record| record.level).collect();
        assert_eq!(levels, vec![CoreLogLevel::Warn]);
    }

    #[test]
    fn install_again_replaces_the_logger() {
        let _serial = serial();
        let first = Capture::install(CoreLogLevel::Debug);
        let second = Capture::install(CoreLogLevel::Debug);

        log::error!(target: "plinth_test", "once");

        assert!(first.records().is_empty());
        assert_eq!(second.records().len(), 1);
    }

    #[test]
    fn every_level_maps_to_its_log_crate_level() {
        let pairs = [
            (CoreLogLevel::Error, log::Level::Error),
            (CoreLogLevel::Warn, log::Level::Warn),
            (CoreLogLevel::Info, log::Level::Info),
            (CoreLogLevel::Debug, log::Level::Debug),
            (CoreLogLevel::Trace, log::Level::Trace),
        ];

        for (core, level) in pairs {
            assert_eq!(CoreLogLevel::from(level), core);
            assert_eq!(log::LevelFilter::from(core), level.to_level_filter());
        }
    }
}
