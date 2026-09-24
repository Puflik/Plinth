//! Ни одна функция, видимая из Kotlin, не паникует (A3.3). Тело каждой
//! экспортируемой функции идёт через [`guard`]: паника становится
//! `CoreError::Internal` с местом и текстом, а в Kotlin —
//! `CoreException.Internal`. Без этого `unwrap` на кривых данных провайдера
//! закрывает приложение с невнятным `SIGABRT` в логе.
//!
//! Второй рубеж — UniFFI: он сам ловит панику, которая прошла мимо `guard`,
//! и отдаёт её в Kotlin как `InternalException`. Оба работают только при
//! `panic = "unwind"` в профиле сборки.

use std::any::Any;
use std::cell::{Cell, RefCell};
use std::panic::{AssertUnwindSafe, PanicHookInfo};
use std::sync::Once;

use plinth_types::CoreError;

thread_local! {
    /// Сколько `guard` сейчас открыто в этом потоке.
    static GUARDS: Cell<u32> = const { Cell::new(0) };
    /// Описание последней паники под `guard`: хук видит место, `guard` — нет.
    static CAUGHT: RefCell<Option<String>> = const { RefCell::new(None) };
}

/// Выполняет `body`; паника внутри — `Err(CoreError::Internal)` и запись в лог.
pub(crate) fn guard<T>(body: impl FnOnce() -> Result<T, CoreError>) -> Result<T, CoreError> {
    let outcome = {
        let _open = OpenGuard::enter();
        // Состояние, которое body оставил на полпути, после паники не
        // используется: вызов закончен ошибкой, повтор — новый вызов.
        std::panic::catch_unwind(AssertUnwindSafe(body))
    };
    outcome.unwrap_or_else(|payload| {
        let message = CAUGHT.with(RefCell::take).unwrap_or_else(|| format!("panicked: {}", describe(payload.as_ref())));
        // Уже вне хука: если сломан сам логгер, его паника уйдёт наружу
        // обычным путём, а не превратится в abort.
        log::error!("{message}");
        Err(CoreError::internal(message))
    })
}

/// Ставит хук паники — один раз на процесс, повторные вызовы ничего не делают.
///
/// Под `guard` хук только запоминает место и текст — пишет в лог сам `guard`,
/// уже после перехвата. Паника в коде хука — это abort, а логгер — код
/// Kotlin, который может упасть. Вне `guard` (второй рубеж — UniFFI) хук
/// пишет сам, если паника не из логгера, и отдаёт её хуку по умолчанию.
pub(crate) fn install_hook() {
    static HOOK: Once = Once::new();
    HOOK.call_once(|| {
        let default_hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            let message = place_and_text(info);
            if GUARDS.with(Cell::get) > 0 {
                CAUGHT.with(|caught| caught.replace(Some(message)));
                return;
            }
            if !crate::logging::forwarding() {
                log::error!("{message}");
            }
            default_hook(info);
        }));
    });
}

fn place_and_text(info: &PanicHookInfo) -> String {
    let text = describe(info.payload());
    match info.location() {
        Some(place) => format!("panicked at {}:{}:{}: {text}", place.file(), place.line(), place.column()),
        None => format!("panicked: {text}"),
    }
}

fn describe(payload: &(dyn Any + Send)) -> &str {
    if let Some(text) = payload.downcast_ref::<&str>() {
        text
    } else if let Some(text) = payload.downcast_ref::<String>() {
        text
    } else {
        "non-text panic payload"
    }
}

struct OpenGuard;

impl OpenGuard {
    fn enter() -> Self {
        GUARDS.with(|open| open.set(open.get() + 1));
        Self
    }
}

impl Drop for OpenGuard {
    fn drop(&mut self) {
        GUARDS.with(|open| open.set(open.get() - 1));
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use plinth_types::CoreError;

    use super::{guard, install_hook};
    use crate::logging::{CoreLogLevel, CoreLogRecord, CoreLogger};
    use crate::testing::{Capture, serial};

    fn internal_message(result: Result<(), CoreError>) -> String {
        match result {
            Err(CoreError::Internal { message }) => message,
            other => panic!("expected an internal error, got {other:?}"),
        }
    }

    #[test]
    fn result_passes_through_untouched() {
        assert_eq!(guard(|| Ok(7)), Ok(7));
        assert_eq!(guard::<()>(|| Err(CoreError::parse("bad tag"))), Err(CoreError::parse("bad tag")));
    }

    #[test]
    fn panic_becomes_internal_error_with_its_place() {
        let _serial = serial();
        install_hook();

        let message = internal_message(guard(|| panic!("boom")));

        assert!(message.starts_with("panicked at "), "{message}");
        assert!(message.contains("panic.rs:"), "{message}");
        assert!(message.ends_with(": boom"), "{message}");
    }

    #[test]
    fn formatted_panic_keeps_its_text() {
        let _serial = serial();
        install_hook();

        let message = internal_message(guard(|| panic!("track {} of {}", 3, 12)));

        assert!(message.ends_with(": track 3 of 12"), "{message}");
    }

    #[test]
    fn panic_with_non_text_payload_still_explains_itself() {
        let _serial = serial();
        install_hook();

        let message = internal_message(guard(|| std::panic::panic_any(42_u8)));

        assert!(message.ends_with(": non-text panic payload"), "{message}");
    }

    #[test]
    fn caught_panic_is_logged_once_as_error() {
        let _serial = serial();
        install_hook();
        let capture = Capture::install(CoreLogLevel::Debug);

        let message = internal_message(guard(|| panic!("boom")));

        assert_eq!(
            capture.records(),
            vec![CoreLogRecord { level: CoreLogLevel::Error, target: "plinth_ffi::panic".to_owned(), message }]
        );
    }

    struct PanickingLogger;

    impl CoreLogger for PanickingLogger {
        fn log(&self, _record: CoreLogRecord) {
            panic!("logger is broken");
        }
    }

    /// Логгер — код Kotlin за границей: его сбой приходит в Rust паникой.
    /// Паника внутри хука паники — это abort, то есть падение процесса.
    #[test]
    fn broken_logger_does_not_abort_the_process() {
        let _serial = serial();
        install_hook();
        crate::logging::install(Arc::new(PanickingLogger), CoreLogLevel::Debug);

        let outcome = std::panic::catch_unwind(|| guard::<()>(|| panic!("first")));

        assert!(outcome.is_err());
        crate::logging::install(Arc::new(Capture::default()), CoreLogLevel::Debug);
    }
}
