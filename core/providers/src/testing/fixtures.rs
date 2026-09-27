use std::collections::{BTreeMap, VecDeque};
use std::sync::{Mutex, PoisonError};

use plinth_types::CoreError;

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

    pub fn requests(&self) -> Vec<HttpRequest> {
        self.requests.lock().unwrap_or_else(PoisonError::into_inner).clone()
    }
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
}
