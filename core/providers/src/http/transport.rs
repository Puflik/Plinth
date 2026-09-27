use std::time::Duration;

use plinth_types::CoreError;

/// Один GET, как его видит транспорт.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpRequest {
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub timeout: Duration,
    /// Тело длиннее — транспорт бросает чтение: ответ-мусор не съест память.
    pub max_body_bytes: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpResponse {
    pub status: u16,
    pub body: Vec<u8>,
}

impl HttpResponse {
    pub fn ok(body: impl Into<Vec<u8>>) -> Self {
        Self { status: 200, body: body.into() }
    }

    pub fn status(status: u16) -> Self {
        Self { status, body: Vec::new() }
    }
}

/// Сеть платформы (ответ автора, E1): на Android — Kotlin
/// (`HttpURLConnection`: системные сертификаты, прокси, отмена), в тестах —
/// записанные ответы. Ядро само в сеть не ходит.
///
/// Один запрос, без повторов — их делает [`super::HttpClient`]. Редиректы
/// транспорт проходит сам. Статус не ошибка: 404 и 503 — ответы. Ошибка —
/// когда ответа нет: не соединилось, таймаут, оборвалось — `Network`; тело
/// длиннее `max_body_bytes` — `Parse`.
pub trait HttpTransport: Send + Sync {
    fn get(&self, request: &HttpRequest) -> Result<HttpResponse, CoreError>;
}
