//! Р1.11г (`docs/work/r1-11d.md`, находки v0.2 №30 и R1 №13): что значит ответ
//! с этим статусом — в одном месте, — и как `HttpClient` слушает `Retry-After`.
//! Раньше 429 повторялся через 0,5 и 1 с, что бы ни просил сервер, а отказ сервера
//! и «нет соединения» были одной ошибкой.
//!
//! `check_status`, `parse_retry_after`, `timed_out`, `NetFailure`,
//! `HttpResponse::headers`, `with_header` и `header` — новый API (сигнатуры в
//! `docs/work/r1-11d.md`): пока их нет, тесты не собираются.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

use std::sync::{Arc, Mutex};
use std::time::Duration;

use plinth_providers::http::{
    HttpClient, HttpPolicy, HttpResponse, HttpTransport, Limits, NetFailure, check_status, parse_retry_after, timed_out,
};
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

/// Клиент, который не спит, а записывает паузы.
fn client(transport: Arc<dyn HttpTransport>) -> (HttpClient, Arc<Mutex<Vec<Duration>>>) {
    let pauses = Arc::new(Mutex::new(Vec::new()));
    let recorded = Arc::clone(&pauses);
    let client = HttpClient::new(transport, policy()).with_pause(move |pause| recorded.lock().unwrap().push(pause));
    (client, pauses)
}

/// 429, который просит подождать `wait` секунд.
fn retry_after(wait: u64) -> HttpResponse {
    with_retry_after(429, wait)
}

fn with_retry_after(status: u16, wait: u64) -> HttpResponse {
    HttpResponse::status(status).with_header("Retry-After", &wait.to_string())
}

fn seconds(count: u64) -> Duration {
    Duration::from_secs(count)
}

fn status_of(error: &CoreError) -> Option<NetFailure> {
    NetFailure::of(error)
}

// --- Таблица «статус → ошибка» -------------------------------------------------

#[test]
fn the_table_says_what_every_status_means() {
    for status in [200, 201, 204, 299] {
        assert_eq!(check_status(status, None), Ok(()), "{status}");
    }
    // Элемента нет — провайдер жив.
    for status in [404, 410] {
        assert_eq!(check_status(status, None), Err(CoreError::unavailable(format!("http {status}"))), "{status}");
    }
    // Всё остальное — ответ с отказом: статус в тексте, как читает его здоровье провайдера.
    for status in [301, 400, 401, 403, 408, 414, 429, 500, 502, 503, 504] {
        assert_eq!(check_status(status, None), Err(CoreError::network(format!("http {status}"))), "{status}");
    }
}

#[test]
fn a_status_that_can_pass_by_itself_keeps_the_time_the_server_asked_for() {
    for status in [429, 500, 502, 503, 504] {
        let error = check_status(status, Some(seconds(30))).unwrap_err();

        assert!(matches!(error, CoreError::Network { .. }), "{status}: {error:?}");
        assert_eq!(status_of(&error), Some(NetFailure::Status { status, retry_after: Some(seconds(30)) }), "{status}");
        assert!(error.to_string().starts_with(&format!("network: http {status}")), "{status}: {error}");
    }
}

#[test]
fn a_status_that_will_not_pass_by_itself_forgets_the_time() {
    for status in [301, 400, 403, 404, 410] {
        let plain = check_status(status, None);

        assert_eq!(check_status(status, Some(seconds(30))), plain, "{status}");
    }
    assert_eq!(check_status(200, Some(seconds(30))), Ok(()));
}

#[test]
fn the_error_is_read_back_into_what_happened() {
    assert_eq!(
        status_of(&check_status(503, None).unwrap_err()),
        Some(NetFailure::Status { status: 503, retry_after: None })
    );
    assert_eq!(
        status_of(&check_status(403, None).unwrap_err()),
        Some(NetFailure::Status { status: 403, retry_after: None })
    );
    assert_eq!(status_of(&timed_out()), Some(NetFailure::TimedOut));
    // Нет соединения: что бы транспорт ни назвал причиной.
    for reason in ["ConnectException", "UnknownHostException", "cancelled", "no route", "http", "http abc", ""] {
        assert_eq!(status_of(&CoreError::network(reason)), Some(NetFailure::NoConnection), "{reason:?}");
    }
    // Сроку после статуса верят, только если он целое число секунд.
    assert_eq!(
        status_of(&CoreError::network("http 429, retry after many s")),
        Some(NetFailure::Status { status: 429, retry_after: None })
    );
    // Не сетевая ошибка и «такого нет» — не отказ сети.
    for other in [
        CoreError::parse("html"),
        CoreError::storage("disk"),
        CoreError::internal("bug"),
        CoreError::unavailable("http 404"),
        CoreError::unavailable("provider archive.org is down"),
    ] {
        assert_eq!(status_of(&other), None, "{other:?}");
    }
}

#[test]
fn a_timeout_is_spelled_the_way_the_core_always_spelled_it() {
    assert_eq!(timed_out(), CoreError::network("timed out"));
}

#[test]
fn retry_after_is_whole_seconds() {
    assert_eq!(parse_retry_after("30"), Some(seconds(30)));
    assert_eq!(parse_retry_after(" 7 "), Some(seconds(7)));
    assert_eq!(parse_retry_after("0"), Some(Duration::ZERO));
    // Дата, мусор, дроби и отрицательное — не срок: клиент живёт по своей паузе.
    for value in ["", "soon", "-5", "1.5", "+5", "5 s", "Wed, 21 Oct 2015 07:28:00 GMT", "99999999999999999999999"] {
        assert_eq!(parse_retry_after(value), None, "{value:?}");
    }
}

#[test]
fn a_response_finds_its_headers_ignoring_case() {
    let response = HttpResponse::status(429).with_header("Retry-After", "30").with_header("Content-Type", "text/html");

    assert_eq!(response.header("retry-after"), Some("30"));
    assert_eq!(response.header("RETRY-AFTER"), Some("30"));
    assert_eq!(response.header("content-type"), Some("text/html"));
    assert_eq!(response.header("etag"), None);
    assert_eq!(
        response.headers,
        [("Retry-After".to_owned(), "30".to_owned()), ("Content-Type".to_owned(), "text/html".to_owned())]
    );
    assert_eq!(HttpResponse::ok("{}").header("retry-after"), None);
    assert!(HttpResponse::ok("{}").headers.is_empty());
}

// --- HttpClient и Retry-After --------------------------------------------------

#[test]
fn a_short_retry_after_replaces_our_own_pause() {
    let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(retry_after(2)), Ok(HttpResponse::ok("[]"))]));
    let (client, pauses) = client(transport.clone());

    assert_eq!(client.get(URL, &[]), Ok(b"[]".to_vec()));
    assert_eq!(transport.requests().len(), 2);
    assert_eq!(*pauses.lock().unwrap(), [seconds(2)]);
}

#[test]
fn a_503_may_ask_for_time_too() {
    let transport =
        Arc::new(FixtureTransport::new().on(URL, [Ok(with_retry_after(503, 3)), Ok(HttpResponse::ok("[]"))]));
    let (client, pauses) = client(transport.clone());

    assert_eq!(client.get(URL, &[]), Ok(b"[]".to_vec()));
    assert_eq!(*pauses.lock().unwrap(), [seconds(3)]);
}

#[test]
fn retry_after_never_shortens_our_own_pause() {
    let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(retry_after(0)), Ok(HttpResponse::ok("[]"))]));
    let (client, pauses) = client(transport);

    assert_eq!(client.get(URL, &[]), Ok(b"[]".to_vec()));
    assert_eq!(*pauses.lock().unwrap(), [Duration::from_millis(100)]);
}

#[test]
fn the_time_belongs_to_the_answer_that_named_it() {
    let transport = Arc::new(
        FixtureTransport::new()
            .on(URL, [Ok(with_retry_after(503, 3)), Ok(HttpResponse::status(503)), Ok(HttpResponse::ok("[]"))]),
    );
    let (client, pauses) = client(transport);

    assert_eq!(client.get(URL, &[]), Ok(b"[]".to_vec()));
    // Вторая пауза — снова наша: `2 × backoff`.
    assert_eq!(*pauses.lock().unwrap(), [seconds(3), Duration::from_millis(200)]);
}

#[test]
fn a_time_named_once_is_not_applied_to_a_later_failure_without_an_answer() {
    let transport = Arc::new(FixtureTransport::new().on(
        URL,
        [Ok(with_retry_after(503, 3)), Err(CoreError::network("ConnectException")), Ok(HttpResponse::ok("[]"))],
    ));
    let (client, pauses) = client(transport);

    assert_eq!(client.get(URL, &[]), Ok(b"[]".to_vec()));
    // Вторая пауза — наша: сервер называл срок для другой попытки, а этой он не отвечал вовсе.
    assert_eq!(*pauses.lock().unwrap(), [seconds(3), Duration::from_millis(200)]);
}

#[test]
fn without_retry_after_a_429_is_retried_as_before() {
    let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(HttpResponse::status(429))]));
    let (client, pauses) = client(transport.clone());

    assert_eq!(client.get(URL, &[]), Err(CoreError::network("http 429")));
    assert_eq!(transport.requests().len(), 3);
    assert_eq!(*pauses.lock().unwrap(), [Duration::from_millis(100), Duration::from_millis(200)]);
}

#[test]
fn the_error_after_the_last_attempt_keeps_the_time_of_the_last_answer() {
    let transport =
        Arc::new(FixtureTransport::new().on(URL, [Ok(retry_after(1)), Ok(retry_after(1)), Ok(retry_after(4))]));
    let (client, pauses) = client(transport.clone());

    let error = client.get(URL, &[]).unwrap_err();

    assert_eq!(status_of(&error), Some(NetFailure::Status { status: 429, retry_after: Some(seconds(4)) }));
    assert_eq!(transport.requests().len(), 3);
    assert_eq!(*pauses.lock().unwrap(), [seconds(1), seconds(1)]);
}

#[test]
fn a_server_that_asks_for_a_long_wait_is_not_asked_again_now() {
    let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(retry_after(120)), Ok(HttpResponse::ok("[]"))]));
    let (client, pauses) = client(transport.clone());

    let error = client.get(URL, &[]).unwrap_err();

    assert_eq!(status_of(&error), Some(NetFailure::Status { status: 429, retry_after: Some(seconds(120)) }));
    assert_eq!(transport.requests().len(), 1, "a minute of waiting is not a retry");
    assert!(pauses.lock().unwrap().is_empty());
}

#[test]
fn a_wait_that_does_not_fit_the_search_budget_is_not_taken_and_stays_a_429() {
    let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(retry_after(8)), Ok(HttpResponse::ok("[]"))]));
    let (client, pauses) = client(transport.clone());

    let error = client.get_within(URL, &[], &Limits::within(seconds(5))).unwrap_err();

    // Не «timed out»: сервер ответил, и ответ — «подождите».
    assert_eq!(status_of(&error), Some(NetFailure::Status { status: 429, retry_after: Some(seconds(8)) }));
    assert_eq!(transport.requests().len(), 1);
    assert!(pauses.lock().unwrap().is_empty());
}

#[test]
fn a_status_that_is_not_retried_ignores_retry_after() {
    let transport =
        Arc::new(FixtureTransport::new().on(URL, [Ok(with_retry_after(403, 5)), Ok(HttpResponse::ok("[]"))]));
    let (client, pauses) = client(transport.clone());

    assert_eq!(client.get(URL, &[]), Err(CoreError::network("http 403")));
    assert_eq!(transport.requests().len(), 1);
    assert!(pauses.lock().unwrap().is_empty());
}

#[test]
fn a_missing_item_stays_unavailable_whatever_the_headers_say() {
    for status in [404, 410] {
        let transport = Arc::new(FixtureTransport::new().on(URL, [Ok(with_retry_after(status, 5))]));
        let (client, _) = client(transport.clone());

        assert_eq!(client.get(URL, &[]), Err(CoreError::unavailable(format!("http {status}"))), "{status}");
        assert_eq!(transport.requests().len(), 1, "{status}");
    }
}

#[test]
fn a_transport_that_timed_out_is_retried_and_ends_as_timed_out() {
    let transport = Arc::new(FixtureTransport::new().on(URL, [Err(timed_out())]));
    let (client, pauses) = client(transport.clone());

    let error = client.get(URL, &[]).unwrap_err();

    assert_eq!(error, timed_out());
    assert_eq!(status_of(&error), Some(NetFailure::TimedOut));
    assert_eq!(transport.requests().len(), 3);
    assert_eq!(pauses.lock().unwrap().len(), 2);
}

#[test]
fn a_good_answer_after_a_wait_is_returned_with_its_body_only() {
    let transport = Arc::new(FixtureTransport::new().on(
        URL,
        [Ok(retry_after(2)), Ok(HttpResponse::ok("{\"ok\":true}").with_header("Content-Type", "application/json"))],
    ));
    let (client, _) = client(transport);

    assert_eq!(client.get(URL, &[]), Ok(b"{\"ok\":true}".to_vec()));
}
