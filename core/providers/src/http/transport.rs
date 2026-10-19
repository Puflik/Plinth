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
    /// Заголовки ответа: порядок и регистр имён — как прислал сервер.
    pub headers: Vec<(String, String)>,
}

impl HttpResponse {
    pub fn ok(body: impl Into<Vec<u8>>) -> Self {
        Self { status: 200, body: body.into(), headers: Vec::new() }
    }

    pub fn status(status: u16) -> Self {
        Self { status, body: Vec::new(), headers: Vec::new() }
    }

    pub fn with_header(mut self, name: &str, value: &str) -> Self {
        self.headers.push((name.to_owned(), value.to_owned()));
        self
    }

    /// Значение первого заголовка с этим именем; регистр имени не важен.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(own, _)| own.eq_ignore_ascii_case(name)).map(|(_, value)| value.as_str())
    }
}

/// Сеть платформы (ответ автора, E1): на Android — Kotlin
/// (`HttpURLConnection`: системные сертификаты, прокси, отмена), в тестах —
/// записанные ответы. Ядро само в сеть не ходит.
///
/// Один запрос, без повторов — их делает [`super::HttpClient`]. Редиректы
/// транспорт проходит сам. Статус не ошибка: 404 и 503 — ответы, и заголовки
/// ответа (`Retry-After`) едут вместе с ним. Ошибка — когда ответа нет: не
/// соединилось, таймаут (`Network("timed out")`, [`super::timed_out`]),
/// оборвалось — `Network`; тело длиннее `max_body_bytes` — `Parse`.
pub trait HttpTransport: Send + Sync {
    fn get(&self, request: &HttpRequest) -> Result<HttpResponse, CoreError>;
}
