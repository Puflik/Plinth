//! Граница ядра с Kotlin (A3). Что её пересекает и почему —
//! `docs/adr/0010-ffi-boundary.md`. Правила этого файла:
//!
//! - всё видимое из Kotlin объявлено здесь или в модулях `ffi`, остальные
//!   крейты ядра об UniFFI не знают;
//! - каждая экспортируемая функция возвращает `Result<_, CoreError>`, и её
//!   тело идёт через [`panic::guard`];
//! - вызовы блокирующие: Kotlin зовёт их не из главного потока.
//!
//! API ядра — методы объекта [`Core`] (`session.rs`): чтение библиотеки —
//! `api/library_api.rs`, действия пользователя — `api/journal_api.rs`.

mod api;
mod logging;
mod panic;
mod session;
#[cfg(test)]
mod testing;
mod types;

use std::sync::Arc;

pub use logging::{CoreLogLevel, CoreLogRecord, CoreLogger};
use plinth_types::CoreError;
pub use session::Core;
pub use types::{NewPlay, PlaylistItem, StartupReport};

uniffi::setup_scaffolding!();

const VERSION: &str = env!("CARGO_PKG_VERSION");

// `CoreError` живёт в `plinth-types`, который об UniFFI не знает. Здесь —
// его копия для генератора; разойдётся с оригиналом — не соберётся.
// Kotlin видит `CoreException.<Категория>` с текстом ошибки.
#[uniffi::remote(Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    Storage { message: String },
    Network { message: String },
    Parse { message: String },
    Unavailable { message: String },
    Internal { message: String },
}

/// Запуск ядра: логгер, уровень логов, хук паники. Повторный вызов меняет
/// логгер и уровень.
#[uniffi::export]
pub fn start(logger: Arc<dyn CoreLogger>, max_level: CoreLogLevel) -> Result<(), CoreError> {
    panic::guard(|| {
        logging::install(logger, max_level);
        panic::install_hook();
        log::info!("Plinth core {VERSION} ready");
        Ok(())
    })
}

/// Намеренная паника: по ней инструментальный тест проверяет, что граница
/// держит и паника приходит в Kotlin исключением, а не падением процесса.
#[uniffi::export]
pub fn panic_for_test(message: String) -> Result<(), CoreError> {
    #[expect(clippy::panic, reason = "паника здесь — и есть проверяемое поведение")]
    panic::guard(|| panic!("{message}"))
}

#[cfg(test)]
mod tests {
    use super::{CoreError, panic_for_test};
    use crate::testing::serial;

    #[test]
    fn panic_for_test_comes_back_as_internal_error() {
        let _serial = serial();
        crate::panic::install_hook();

        let result = panic_for_test("on purpose".to_owned());

        assert!(
            matches!(&result, Err(CoreError::Internal { message }) if message.ends_with(": on purpose")),
            "{result:?}"
        );
    }
}
