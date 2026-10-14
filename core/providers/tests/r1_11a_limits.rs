//! Р1.11а (`docs/work/r1-11a.md`, находка 10 ревью v0.2): срок и отмена одного
//! вызова `HttpClient`. Раньше «сеть есть, но молчит» держала поиск до 46,5 с
//! (три попытки по 15 с и паузы), а отменить его было нечем.
//!
//! `Cancel`, `Limits` и `HttpClient::get_within` — новый API (сигнатуры в
//! `docs/work/r1-11a.md`): пока их нет, тесты крейта не собираются.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use plinth_providers::http::{Cancel, HttpClient, HttpPolicy, HttpRequest, HttpResponse, HttpTransport, Limits};
use plinth_providers::testing::FixtureTransport;
use plinth_types::CoreError;

const URL: &str = "https://archive.org/advancedsearch.php?q=dead";

fn policy() -> HttpPolicy {
    HttpPolicy {
        user_agent: "Plinth/test".to_owned(),
        timeout: Duration::from_secs(15),
        attempts: 3,
        backoff: Duration::from_millis(100),
        max_body_bytes: 1000,
    }
}

fn cancelled() -> CoreError {
    CoreError::network("cancelled")
}

/// Клиент, который не спит, а записывает паузы.
fn client(transport: Arc<dyn HttpTransport>, policy: HttpPolicy) -> (HttpClient, Arc<Mutex<Vec<Duration>>>) {
    let pauses = Arc::new(Mutex::new(Vec::new()));
    let recorded = Arc::clone(&pauses);
    let client = HttpClient::new(transport, policy).with_pause(move |pause| recorded.lock().unwrap().push(pause));
    (client, pauses)
}

/// Сеть, которая на каждый запрос отвечает «нет ответа», а перед этим делает `during`.
struct Failing<F: Fn() + Send + Sync> {
    during: F,
    calls: AtomicUsize,
}

impl<F: Fn() + Send + Sync> Failing<F> {
    fn new(during: F) -> Arc<Self> {
        Arc::new(Self { during, calls: AtomicUsize::new(0) })
    }

    fn calls(&self) -> usize {
        self.calls.load(Ordering::SeqCst)
    }
}

impl<F: Fn() + Send + Sync> HttpTransport for Failing<F> {
    fn get(&self, _: &HttpRequest) -> Result<HttpResponse, CoreError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        (self.during)();
        Err(CoreError::network("boom"))
    }
}

/// Сеть, которая молчит: ждёт столько, сколько ей разрешили, и «не отвечает».
#[derive(Default)]
struct Silent {
    timeouts: Mutex<Vec<Duration>>,
}

impl HttpTransport for Silent {
    fn get(&self, request: &HttpRequest) -> Result<HttpResponse, CoreError> {
        self.timeouts.lock().unwrap().push(request.timeout);
        std::thread::sleep(request.timeout);
        Err(CoreError::network("timeout"))
    }
}

#[test]
fn cancel_is_seen_through_every_clone() {
    let cancel = Cancel::new();
    let clone = cancel.clone();
    let limits = Limits::none().cancelled_by(&cancel);

    assert!(!clone.is_cancelled() && !limits.is_cancelled());
    cancel.cancel();

    assert!(clone.is_cancelled());
    assert!(limits.is_cancelled());
    assert!(!Limits::none().is_cancelled(), "limits without a token are never cancelled");
}

#[test]
fn limits_know_how_much_time_is_left() {
    assert_eq!(Limits::none().left(), None);
    assert_eq!(Limits::within(Duration::ZERO).left(), Some(Duration::ZERO));
    let left = Limits::within(Duration::from_secs(30)).left().unwrap();
    assert!(left <= Duration::from_secs(30) && left > Duration::from_secs(25), "{left:?}");
    assert_eq!(Limits::within(Duration::MAX).left(), None, "a budget that does not fit the clock is no budget");
}

#[test]
fn a_cancelled_call_does_not_touch_the_network() {
    let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(HttpResponse::ok("{}"))]));
    let (client, pauses) = client(transport.clone(), policy());
    let cancel = Cancel::new();
    cancel.cancel();

    let result = client.get_within(URL, &[], &Limits::none().cancelled_by(&cancel));

    assert_eq!(result, Err(cancelled()));
    assert!(transport.requests().is_empty());
    assert!(pauses.lock().unwrap().is_empty());
}

#[test]
fn cancelling_during_an_attempt_stops_the_retries() {
    let cancel = Cancel::new();
    let transport = Failing::new({
        let cancel = cancel.clone();
        move || cancel.cancel()
    });
    let (client, pauses) = client(transport.clone(), policy());

    let result = client.get_within(URL, &[], &Limits::none().cancelled_by(&cancel));

    assert_eq!(result, Err(cancelled()), "the error is the cancel, not the failure it interrupted");
    assert_eq!(transport.calls(), 1);
    assert!(pauses.lock().unwrap().is_empty(), "no pause once cancelled");
}

#[test]
fn cancelling_during_a_pause_stops_the_retries() {
    let cancel = Cancel::new();
    let transport = Failing::new(|| {});
    let pausing = cancel.clone();
    let client = HttpClient::new(transport.clone(), policy()).with_pause(move |_| pausing.cancel());

    let result = client.get_within(URL, &[], &Limits::none().cancelled_by(&cancel));

    assert_eq!(result, Err(cancelled()));
    assert_eq!(transport.calls(), 1, "the pause ended cancelled: no second attempt");
}

#[test]
fn an_attempt_never_gets_more_than_what_is_left() {
    let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(HttpResponse::ok("{}"))]));
    let (client, _) = client(transport.clone(), policy());

    client.get_within(URL, &[], &Limits::within(Duration::from_secs(2))).unwrap();
    client.get_within(URL, &[], &Limits::none()).unwrap();
    client.get_within(URL, &[], &Limits::within(Duration::from_secs(60))).unwrap();

    let timeouts: Vec<Duration> = transport.requests().iter().map(|request| request.timeout).collect();
    assert!(timeouts[0] <= Duration::from_secs(2) && timeouts[0] > Duration::from_secs(1), "{:?}", timeouts[0]);
    assert_eq!(timeouts[1], Duration::from_secs(15), "no budget: the policy's timeout");
    assert_eq!(timeouts[2], Duration::from_secs(15), "a roomy budget does not stretch the timeout");
}

#[test]
fn a_spent_budget_asks_nobody() {
    let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(HttpResponse::ok("{}"))]));
    let (client, _) = client(transport.clone(), policy());

    let result = client.get_within(URL, &[], &Limits::within(Duration::ZERO));

    assert_eq!(result, Err(CoreError::network("timed out")));
    assert!(transport.requests().is_empty());
}

#[test]
fn a_pause_that_would_eat_the_rest_is_not_taken() {
    let transport = Failing::new(|| {});
    let (client, pauses) = client(transport.clone(), HttpPolicy { backoff: Duration::from_secs(5), ..policy() });

    let result = client.get_within(URL, &[], &Limits::within(Duration::from_secs(1)));

    assert_eq!(result, Err(CoreError::network("boom")), "the last real failure, not a made-up one");
    assert_eq!(transport.calls(), 1);
    assert!(pauses.lock().unwrap().is_empty());
}

/// Страж: срок, которого хватает, поведения не меняет — повторы и паузы те же.
#[test]
fn a_roomy_budget_keeps_the_retries_and_the_pauses() {
    let transport = Arc::new(
        FixtureTransport::new()
            .on(URL, [Err(CoreError::network("timeout")), Ok(HttpResponse::status(503)), Ok(HttpResponse::ok("[]"))]),
    );
    let (client, pauses) = client(transport.clone(), policy());

    let result = client.get_within(URL, &[], &Limits::within(Duration::from_secs(60)));

    assert_eq!(result, Ok(b"[]".to_vec()));
    assert_eq!(transport.requests().len(), 3);
    assert_eq!(*pauses.lock().unwrap(), [Duration::from_millis(100), Duration::from_millis(200)]);
}

/// Страж: без срока и отмены `get_within` — это прежний `get`.
#[test]
fn without_limits_it_is_the_old_get() {
    let transport = Failing::new(|| {});
    let (client, pauses) = client(transport.clone(), policy());

    let result = client.get_within(URL, &[], &Limits::none());

    assert_eq!(result, Err(CoreError::network("boom")));
    assert_eq!(transport.calls(), 3);
    assert_eq!(pauses.lock().unwrap().len(), 2);
    assert_eq!(client.get(URL, &[]), Err(CoreError::network("boom")), "`get` itself does not change");
}

/// «Сеть есть, но молчит»: общий срок кончается на первой же попытке, повторов
/// не будет. Время настоящее, поэтому запас широкий.
#[test]
fn the_budget_spans_all_attempts_on_a_silent_network() {
    let transport = Arc::new(Silent::default());
    let client = HttpClient::new(
        transport.clone(),
        HttpPolicy { timeout: Duration::from_secs(5), backoff: Duration::from_millis(10), ..policy() },
    );
    let started = Instant::now();

    let result = client.get_within(URL, &[], &Limits::within(Duration::from_millis(300)));

    assert!(matches!(result, Err(CoreError::Network { .. })), "{result:?}");
    assert!(started.elapsed() < Duration::from_millis(1500), "took {:?}", started.elapsed());
    let timeouts = transport.timeouts.lock().unwrap().clone();
    assert_eq!(timeouts.len(), 1, "{timeouts:?}");
    assert!(timeouts[0] <= Duration::from_millis(300), "{timeouts:?}");
}
