//! Общее для тестов крейта: логгер Rust и хук паники — на весь процесс,
//! а тесты идут в параллельных потоках. Кто их трогает, держит [`serial`].

use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};

use plinth_types::DeviceId;

use crate::logging::{CoreLogLevel, CoreLogRecord, CoreLogger};

static LOCK: Mutex<()> = Mutex::new(());

pub(crate) fn serial() -> MutexGuard<'static, ()> {
    LOCK.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Запоминает всё, что пришло, — как `CoreLogBridge` на стороне Kotlin.
#[derive(Default)]
pub(crate) struct Capture {
    records: Mutex<Vec<CoreLogRecord>>,
}

impl Capture {
    pub(crate) fn install(level: CoreLogLevel) -> Arc<Self> {
        let capture = Arc::new(Self::default());
        crate::logging::install(capture.clone(), level);
        capture
    }

    pub(crate) fn records(&self) -> Vec<CoreLogRecord> {
        self.records.lock().unwrap().clone()
    }
}

impl CoreLogger for Capture {
    fn log(&self, record: CoreLogRecord) {
        self.records.lock().unwrap().push(record);
    }
}

/// Пустой каталог данных ядра; стирается в `Drop`.
pub(crate) struct Scratch(pub(crate) PathBuf);

impl Scratch {
    pub(crate) fn new() -> Self {
        Self(std::env::temp_dir().join(format!("plinth-core-{}", DeviceId::new())))
    }

    pub(crate) fn path(&self) -> String {
        self.0.to_string_lossy().into_owned()
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
