use std::sync::Arc;
use std::thread;
use std::time::Duration;

use plinth_types::CoreError;

use super::{HttpRequest, HttpTransport};

/// Политика запросов (E1.1). Таймаут, число попыток и пауза придут из
/// конфига провайдера; User-Agent — от приложения: Internet Archive просит
/// представляться.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpPolicy {
    pub user_agent: String,
    pub timeout: Duration,
    /// Всего попыток, с первой; меньше одной не бывает.
    pub attempts: u32,
    /// Пауза перед второй попыткой; дальше — вдвое больше каждый раз.
    pub backoff: Duration,
    pub max_body_bytes: u64,
}

impl Default for HttpPolicy {
    fn default() -> Self {
        Self {
            user_agent: format!("Plinth/{} (+https://github.com/Puflik/Plinth)", env!("CARGO_PKG_VERSION")),
            timeout: Duration::from_secs(15),
            attempts: 3,
            backoff: Duration::from_millis(500),
            max_body_bytes: 16 * 1024 * 1024,
        }
    }
}

type Pause = Arc<dyn Fn(Duration) + Send + Sync>;

/// GET по политике поверх транспорта платформы.
///
/// Повторяется то, что может пройти само: нет ответа (`Network` от
/// транспорта), 429 и 5xx. 404 и 410 — `Unavailable` сразу: элемента нет,
/// провайдер жив. Остальные статусы — `Network` сразу. В тексте ошибки —
/// только статус: в адресе бывает поисковый запрос.
#[derive(Clone)]
pub struct HttpClient {
    transport: Arc<dyn HttpTransport>,
    policy: HttpPolicy,
    pause: Pause,
}

impl HttpClient {
    pub fn new(transport: Arc<dyn HttpTransport>, policy: HttpPolicy) -> Self {
        Self { transport, policy, pause: Arc::new(thread::sleep) }
    }

    /// Паузы между попытками — через `pause` (тесты не ждут).
    pub fn with_pause(mut self, pause: impl Fn(Duration) + Send + Sync + 'static) -> Self {
        self.pause = Arc::new(pause);
        self
    }

    pub fn policy(&self) -> &HttpPolicy {
        &self.policy
    }

    /// Тело ответа 2xx. Свой `User-Agent` в `headers` заменяет политику.
    pub fn get(&self, url: &str, headers: &[(String, String)]) -> Result<Vec<u8>, CoreError> {
        let request = self.request(url, headers);
        let attempts = self.policy.attempts.max(1);
        let mut last = CoreError::network("no attempt");
        for attempt in 0..attempts {
            if attempt > 0 {
                (self.pause)(self.backoff_before(attempt));
            }
            match self.transport.get(&request) {
                Ok(response) if (200..300).contains(&response.status) => {
                    return self.checked_body(response.body);
                }
                Ok(response) if matches!(response.status, 404 | 410) => {
                    return Err(CoreError::unavailable(format!("http {}", response.status)));
                }
                Ok(response) if response.status == 429 || (500..600).contains(&response.status) => {
                    last = status_error(response.status);
                }
                Ok(response) => return Err(status_error(response.status)),
                Err(error @ CoreError::Network { .. }) => last = error,
                Err(error) => return Err(error),
            }
        }
        log::warn!("http: gave up after {attempts} attempts: {last}");
        Err(last)
    }

    fn request(&self, url: &str, headers: &[(String, String)]) -> HttpRequest {
        let own_agent = headers.iter().any(|(name, _)| name.eq_ignore_ascii_case("user-agent"));
        let mut all = Vec::with_capacity(headers.len() + 1);
        if !own_agent {
            all.push(("User-Agent".to_owned(), self.policy.user_agent.clone()));
        }
        all.extend_from_slice(headers);
        HttpRequest {
            url: url.to_owned(),
            headers: all,
            timeout: self.policy.timeout,
            max_body_bytes: self.policy.max_body_bytes,
        }
    }

    /// Перед попыткой `attempt` (вторая — 1): `backoff`, `2 × backoff`, …
    fn backoff_before(&self, attempt: u32) -> Duration {
        self.policy.backoff.saturating_mul(1 << (attempt - 1).min(16))
    }

    /// Транспорт обязан обрезать длинное тело, но проверка остаётся и здесь.
    fn checked_body(&self, body: Vec<u8>) -> Result<Vec<u8>, CoreError> {
        let len = u64::try_from(body.len()).unwrap_or(u64::MAX);
        if len > self.policy.max_body_bytes {
            Err(CoreError::parse(format!("response: {len} bytes, at most {}", self.policy.max_body_bytes)))
        } else {
            Ok(body)
        }
    }
}

/// Ответ с неудачным статусом — `Network` с текстом «http NNN». Здоровье
/// провайдера по этому тексту отличает отказ сервера от сбоя соединения.
fn status_error(status: u16) -> CoreError {
    CoreError::network(format!("http {status}"))
}

/// Статус, если ошибка — ответ сервера (`status_error`); `None` — соединения
/// не вышло вовсе (нет сети, DNS, таймаут).
pub(crate) fn http_status(error: &CoreError) -> Option<u16> {
    match error {
        CoreError::Network { message } => message.strip_prefix("http ")?.parse().ok(),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    use plinth_types::CoreError;

    use super::{HttpClient, HttpPolicy};
    use crate::http::HttpResponse;
    use crate::testing::FixtureTransport;

    const URL: &str = "https://archive.org/advancedsearch.php?q=dead";

    fn policy() -> HttpPolicy {
        HttpPolicy {
            user_agent: "Plinth/test".to_owned(),
            timeout: Duration::from_secs(7),
            attempts: 3,
            backoff: Duration::from_millis(100),
            max_body_bytes: 10,
        }
    }

    /// Клиент, который не спит, а записывает паузы.
    fn client(transport: &Arc<FixtureTransport>) -> (HttpClient, Arc<Mutex<Vec<Duration>>>) {
        let pauses = Arc::new(Mutex::new(Vec::new()));
        let recorded = Arc::clone(&pauses);
        let client =
            HttpClient::new(transport.clone(), policy()).with_pause(move |pause| recorded.lock().unwrap().push(pause));
        (client, pauses)
    }

    #[test]
    fn body_of_a_good_answer_with_policy_headers() {
        let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(HttpResponse::ok("{}"))]));
        let (client, pauses) = client(&transport);

        let body = client.get(URL, &[("Accept".to_owned(), "application/json".to_owned())]);

        assert_eq!(body, Ok(b"{}".to_vec()));
        let requests = transport.requests();
        assert_eq!(requests.len(), 1);
        assert_eq!(requests[0].url, URL);
        assert_eq!(requests[0].timeout, Duration::from_secs(7));
        assert_eq!(requests[0].max_body_bytes, 10);
        assert_eq!(
            requests[0].headers,
            [("User-Agent".to_owned(), "Plinth/test".to_owned()), ("Accept".to_owned(), "application/json".to_owned())]
        );
        assert!(pauses.lock().unwrap().is_empty());
    }

    #[test]
    fn own_user_agent_replaces_the_policy() {
        let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(HttpResponse::ok(""))]));
        let (client, _) = client(&transport);

        client.get(URL, &[("user-agent".to_owned(), "Browser".to_owned())]).unwrap();

        assert_eq!(transport.requests()[0].headers, [("user-agent".to_owned(), "Browser".to_owned())]);
    }

    #[test]
    fn transient_failures_are_retried_with_growing_pauses() {
        let transport =
            Arc::new(FixtureTransport::new().on(
                URL,
                [Err(CoreError::network("timeout")), Ok(HttpResponse::status(503)), Ok(HttpResponse::ok("[]"))],
            ));
        let (client, pauses) = client(&transport);

        assert_eq!(client.get(URL, &[]), Ok(b"[]".to_vec()));
        assert_eq!(transport.requests().len(), 3);
        assert_eq!(*pauses.lock().unwrap(), [Duration::from_millis(100), Duration::from_millis(200)]);
    }

    #[test]
    fn gives_up_after_all_attempts_with_the_last_status() {
        let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(HttpResponse::status(429))]));
        let (client, pauses) = client(&transport);

        assert_eq!(client.get(URL, &[]), Err(CoreError::network("http 429")));
        assert_eq!(transport.requests().len(), 3);
        assert_eq!(pauses.lock().unwrap().len(), 2);
    }

    #[test]
    fn missing_item_is_unavailable_at_once() {
        for status in [404, 410] {
            let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(HttpResponse::status(status))]));
            let (client, _) = client(&transport);

            assert_eq!(client.get(URL, &[]), Err(CoreError::unavailable(format!("http {status}"))));
            assert_eq!(transport.requests().len(), 1, "{status}");
        }
    }

    #[test]
    fn other_statuses_fail_at_once() {
        for status in [301, 400, 403] {
            let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(HttpResponse::status(status))]));
            let (client, _) = client(&transport);

            assert_eq!(client.get(URL, &[]), Err(CoreError::network(format!("http {status}"))));
            assert_eq!(transport.requests().len(), 1, "{status}");
        }
    }

    #[test]
    fn non_network_transport_errors_are_not_retried() {
        let transport = Arc::new(FixtureTransport::new().on(URL, [Err(CoreError::parse("body too long"))]));
        let (client, _) = client(&transport);

        assert_eq!(client.get(URL, &[]), Err(CoreError::parse("body too long")));
        assert_eq!(transport.requests().len(), 1);
    }

    #[test]
    fn too_long_body_is_refused() {
        let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(HttpResponse::ok("01234567890"))]));
        let (client, _) = client(&transport);

        assert!(matches!(client.get(URL, &[]), Err(CoreError::Parse { .. })));
    }

    #[test]
    fn at_least_one_attempt() {
        let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(HttpResponse::ok("1"))]));
        let client = HttpClient::new(transport.clone(), HttpPolicy { attempts: 0, ..policy() });

        assert_eq!(client.get(URL, &[]), Ok(b"1".to_vec()));
    }

    #[test]
    fn errors_do_not_leak_the_address() {
        let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(HttpResponse::status(500))]));
        let (client, _) = client(&transport);

        let error = client.get(URL, &[]).unwrap_err();

        assert!(!error.to_string().contains("dead"), "{error}");
    }

    #[test]
    fn default_policy_introduces_the_app() {
        let policy = HttpPolicy::default();

        assert!(policy.user_agent.starts_with("Plinth/0."), "{}", policy.user_agent);
        assert_eq!(policy.attempts, 3);
    }
}
