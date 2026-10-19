//! Что значит ответ с этим статусом — в одном месте (Р1.11г, docs/work/r1-11d.md).
//!
//! `HttpClient` решает по [`check_status`], Kotlin берёт ту же таблицу через
//! `check_http_status` (загрузчик YouTube, Ю3б): второй таблицы нет. Статус и срок
//! сервера (`Retry-After`) едут в тексте `Network` — `CoreError` от этого не растёт, —
//! а читает этот текст одна функция, [`NetFailure::of`].
//!
//! Грамматика текста `Network` (в нём нет адреса и запроса, ADR 0008):
//!
//! - `timed out` — не дождались ответа;
//! - `http N` — сервер ответил статусом `N`; у статуса, который может пройти сам
//!   (429 и 5xx), с названным сроком: `http N, retry after S s` (целые секунды);
//! - всё остальное — соединения не вышло (причину называет транспорт).

use std::time::Duration;

use plinth_types::CoreError;

/// Текст `Network` «не дождались ответа».
const TIMED_OUT: &str = "timed out";
const STATUS_PREFIX: &str = "http ";
const RETRY_AFTER_INFIX: &str = ", retry after ";
const SECONDS_SUFFIX: &str = " s";

/// 2xx → `Ok(())`. 404, 410 → `Unavailable("http N")`: элемента нет, провайдер жив.
/// Остальное → `Network("http N")`; у статуса, который может пройти сам (429 и 5xx), со
/// сроком сервера: `Network("http N, retry after S s")` — секунды целые, доли
/// отбрасываются. У прочих статусов срок отбрасывается: подождать им нечего.
pub fn check_status(status: u16, retry_after: Option<Duration>) -> Result<(), CoreError> {
    match status {
        200..=299 => Ok(()),
        404 | 410 => Err(CoreError::unavailable(format!("{STATUS_PREFIX}{status}"))),
        _ => Err(CoreError::network(match retry_after.filter(|_| is_transient(status)) {
            Some(wait) => format!("{STATUS_PREFIX}{status}{RETRY_AFTER_INFIX}{}{SECONDS_SUFFIX}", wait.as_secs()),
            None => format!("{STATUS_PREFIX}{status}"),
        })),
    }
}

/// Значение заголовка `Retry-After`: целые секунды (цифры, пробелы по краям
/// терпятся). Дата, дробь, знак, пустое, мусор, переполнение `u64` — `None`: клиент
/// живёт по своей паузе.
pub fn parse_retry_after(value: &str) -> Option<Duration> {
    whole_number(value.trim()).map(Duration::from_secs)
}

/// `Network("timed out")` — как ядро писало «не дождались» и раньше.
pub fn timed_out() -> CoreError {
    CoreError::network(TIMED_OUT)
}

/// Статус, который может пройти сам: 429 и 5xx. Их повторяют, по ним провайдера
/// отправляют отдыхать, и только у них есть смысл в сроке сервера.
pub(crate) fn is_transient(status: u16) -> bool {
    status == 429 || (500..=599).contains(&status)
}

/// Что случилось с сетевым вызовом, по его ошибке.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NetFailure {
    /// Соединения не вышло: нет сети, DNS, отказ соединения, отмена.
    NoConnection,
    /// Ровно `timed out`: не дождались ответа.
    TimedOut,
    /// Сервер ответил этим статусом; `retry_after` — срок, который он назвал.
    Status { status: u16, retry_after: Option<Duration> },
}

impl NetFailure {
    /// `None` — ошибка не `Network` (`Parse`, `Unavailable`, …), в том числе
    /// `Unavailable("http 404")`: элемента нет, это не отказ сети.
    pub fn of(error: &CoreError) -> Option<Self> {
        let CoreError::Network { message } = error else { return None };
        if message == TIMED_OUT {
            return Some(Self::TimedOut);
        }
        Some(match read_status(message) {
            Some((status, retry_after)) => Self::Status { status, retry_after },
            None => Self::NoConnection,
        })
    }
}

/// «http N» или «http N, retry after S s». Срок, которого не разобрать, — просто нет срока.
fn read_status(message: &str) -> Option<(u16, Option<Duration>)> {
    let rest = message.strip_prefix(STATUS_PREFIX)?;
    let (code, wait) = match rest.split_once(RETRY_AFTER_INFIX) {
        Some((code, wait)) => (code, Some(wait)),
        None => (rest, None),
    };
    let status = u16::try_from(whole_number(code)?).ok()?;
    let retry_after = wait.and_then(|wait| wait.strip_suffix(SECONDS_SUFFIX)).and_then(whole_number);
    Some((status, retry_after.map(Duration::from_secs)))
}

/// Целое неотрицательное число: только цифры, без знака, дроби и пробелов.
fn whole_number(text: &str) -> Option<u64> {
    if text.is_empty() || !text.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    text.parse().ok()
}
