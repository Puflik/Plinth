//! Срок и отмена одного вызова (Р1.11а). Это свойства вызова, а не клиента:
//! общий срок поиска и «ответ уже не нужен» едут в запросе до `HttpClient`,
//! который проверяет их между попытками и просит у транспорта не больше остатка.
//! Прервать уже идущий запрос ядро не может — оно проверяет флаг между шагами.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

/// Отмена: клоны видят одно состояние; отменённое не возвращается.
#[derive(Debug, Clone, Default)]
pub struct Cancel(Arc<AtomicBool>);

impl Cancel {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn cancel(&self) {
        self.0.store(true, Ordering::SeqCst);
    }

    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::SeqCst)
    }
}

/// Срок и отмена одного вызова. `Limits::default()` — то же, что
/// [`Limits::none`]: ни срока, ни отмены.
#[derive(Debug, Clone, Default)]
pub struct Limits {
    deadline: Option<Instant>,
    cancel: Cancel,
}

impl Limits {
    pub fn none() -> Self {
        Self::default()
    }

    /// Срок — от «сейчас». Не поместился в `Instant` (`Duration::MAX`) — срока нет.
    pub fn within(budget: Duration) -> Self {
        Self { deadline: Instant::now().checked_add(budget), cancel: Cancel::new() }
    }

    /// Отмена — та же, что у `cancel`: `cancel.cancel()` виден через эти `Limits`.
    pub fn cancelled_by(self, cancel: &Cancel) -> Self {
        Self { cancel: cancel.clone(), ..self }
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancel.is_cancelled()
    }

    /// Сколько осталось; `None` — срока нет, `Some(0)` — вышел.
    pub fn left(&self) -> Option<Duration> {
        self.deadline.map(|deadline| deadline.saturating_duration_since(Instant::now()))
    }
}
