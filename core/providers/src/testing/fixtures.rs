use std::collections::{BTreeMap, VecDeque};
use std::fs;
use std::path::Path;
use std::sync::{Mutex, PoisonError};

use plinth_types::CoreError;
use serde::Deserialize;

use crate::http::{HttpRequest, HttpResponse, HttpTransport};

/// Транспорт на записанных ответах (E1.4): сеть в тестах запрещена. На
/// адрес без записи — `Network`, как будто сети нет; все запросы
/// запоминаются — тест видит, что и с какими заголовками ушло.
#[derive(Default)]
pub struct FixtureTransport {
    answers: Mutex<BTreeMap<String, VecDeque<Result<HttpResponse, CoreError>>>>,
    requests: Mutex<Vec<HttpRequest>>,
}

impl FixtureTransport {
    pub fn new() -> Self {
        Self::default()
    }

    /// Ответы на `url` по очереди; последний повторяется сколько угодно.
    pub fn on(self, url: &str, answers: impl IntoIterator<Item = Result<HttpResponse, CoreError>>) -> Self {
        self.answers
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .insert(url.to_owned(), answers.into_iter().collect());
        self
    }

    /// Записанные ответы из каталога: `index.json` — `[{url, status, body}]`,
    /// тело — файл рядом (`tools/record_ia_fixtures.py`).
    pub fn recorded(dir: &Path) -> Result<Self, CoreError> {
        let read =
            |name: &str| fs::read(dir.join(name)).map_err(|e| CoreError::storage(format!("fixture {name}: {e}")));
        let index: Vec<Recorded> = serde_json::from_slice(&read("index.json")?)
            .map_err(|e| CoreError::parse(format!("fixture index: {e}")))?;
        let mut transport = Self::new();
        for answer in index {
            let body = read(&answer.body)?;
            transport = transport.on(&answer.url, [Ok(HttpResponse { status: answer.status, body })]);
        }
        Ok(transport)
    }

    pub fn requests(&self) -> Vec<HttpRequest> {
        self.requests.lock().unwrap_or_else(PoisonError::into_inner).clone()
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Recorded {
    url: String,
    status: u16,
    body: String,
}

impl HttpTransport for FixtureTransport {
    fn get(&self, request: &HttpRequest) -> Result<HttpResponse, CoreError> {
        self.requests.lock().unwrap_or_else(PoisonError::into_inner).push(request.clone());
        let mut answers = self.answers.lock().unwrap_or_else(PoisonError::into_inner);
        let queue = answers.get_mut(&request.url);
        let answer = match queue {
            Some(queue) if queue.len() > 1 => queue.pop_front(),
            Some(queue) => queue.front().cloned(),
            None => None,
        };
        answer.unwrap_or_else(|| Err(CoreError::network("fixture: no recorded answer")))
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_types::CoreError;

    use super::FixtureTransport;
    use crate::http::{HttpRequest, HttpResponse, HttpTransport};

    fn get(url: &str) -> HttpRequest {
        HttpRequest { url: url.to_owned(), headers: Vec::new(), timeout: Duration::from_secs(1), max_body_bytes: 100 }
    }

    #[test]
    fn answers_in_order_and_repeats_the_last() {
        let transport =
            FixtureTransport::new().on("https://a.org/x", [Ok(HttpResponse::status(503)), Ok(HttpResponse::ok("1"))]);

        let answers: Vec<_> = (0..3).map(|_| transport.get(&get("https://a.org/x")).map(|r| r.status)).collect();

        assert_eq!(answers, [Ok(503), Ok(200), Ok(200)]);
        assert_eq!(transport.requests().len(), 3);
    }

    #[test]
    fn unrecorded_address_is_no_network() {
        let transport = FixtureTransport::new();

        assert!(matches!(transport.get(&get("https://a.org/y")), Err(CoreError::Network { .. })));
        assert_eq!(transport.requests(), [get("https://a.org/y")]);
    }

    #[test]
    fn recorded_answers_come_from_an_index() {
        let dir = std::env::temp_dir().join(format!("plinth-fixtures-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.json"), "{}").unwrap();
        std::fs::write(dir.join("index.json"), r#"[{ "url": "https://a.org/m/x", "status": 200, "body": "a.json" }]"#)
            .unwrap();

        let transport = FixtureTransport::recorded(&dir).unwrap();
        let broken = FixtureTransport::recorded(&dir.join("missing"));
        std::fs::remove_dir_all(&dir).unwrap();

        assert_eq!(transport.get(&get("https://a.org/m/x")), Ok(HttpResponse::ok("{}")));
        assert!(matches!(broken, Err(CoreError::Storage { .. })));
    }
}
