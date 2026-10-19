//! Р1.11г (`docs/work/r1-11d.md`, находки R1 №13 и v0.2 №30): секция поиска и экран
//! альбома говорят, что случилось на самом деле, — «нет соединения», «сервер не
//! ответил» или «сервер просит подождать». Раньше любой отказ сети, в том числе
//! 503, 429, 403 и истёкший срок, был «Нет сети», а `Retry-After` до ядра не доходил.
//!
//! `NetAnswer::TimedOut`, поле `headers` у `NetAnswer::Response`,
//! `OnlineProblem::RateLimited`, `OnlineAlbumReply`, `check_http_status` и видимость
//! `PlatformTransport` — новый API (сигнатуры в `docs/work/r1-11d.md`): пока их
//! нет, тесты крейта не собираются.

use std::collections::VecDeque;
use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use plinth_providers::http::{HttpRequest, HttpTransport, NetFailure};
use plinth_providers::testing::FixtureTransport;
use plinth_types::CoreError;

use super::online_api::{
    NetAnswer, NetHeader, NetRequest, NetTransport, OnlineCancel, OnlineProblem, PlatformTransport, check_http_status,
    registry,
};
use crate::session::Core;
use crate::testing::Scratch;

const SEARCH_JSON: &str = r#"{"response":{"numFound":1,"start":0,"docs":[
    {"identifier":"MIXG031","title":"Vice Versa","creator":"Intoxicated Piano","year":2013}]}}"#;

const RECORD: &str = "78_oh-doctor_naomi-brown-and-her-piano-h-pearl_gbia0404476a";

/// Сеть, которая отвечает по сценарию: каждому запросу — следующий ответ, последний повторяется.
struct Script {
    answers: Mutex<VecDeque<NetAnswer>>,
    calls: AtomicUsize,
}

impl Script {
    fn new(answers: impl IntoIterator<Item = NetAnswer>) -> Arc<Self> {
        Arc::new(Self { answers: Mutex::new(answers.into_iter().collect()), calls: AtomicUsize::new(0) })
    }

    fn calls(&self) -> usize {
        self.calls.load(Ordering::SeqCst)
    }
}

impl NetTransport for Script {
    fn get(&self, _: NetRequest) -> NetAnswer {
        self.calls.fetch_add(1, Ordering::SeqCst);
        let mut answers = self.answers.lock().unwrap();
        if answers.len() > 1 { answers.pop_front().unwrap() } else { answers.front().cloned().unwrap() }
    }
}

/// Записанные ответы Internet Archive (E2) как сеть Kotlin.
struct Recorded(FixtureTransport);

impl NetTransport for Recorded {
    fn get(&self, request: NetRequest) -> NetAnswer {
        let request = HttpRequest {
            url: request.url,
            headers: request.headers.into_iter().map(|h| (h.name, h.value)).collect(),
            timeout: Duration::from_millis(request.timeout_ms),
            max_body_bytes: request.max_body_bytes,
        };
        match self.0.get(&request) {
            Ok(response) => NetAnswer::Response { status: response.status, body: response.body, headers: Vec::new() },
            Err(error) => NetAnswer::NoAnswer { reason: error.to_string() },
        }
    }
}

fn status(code: u16) -> NetAnswer {
    NetAnswer::Response { status: code, body: Vec::new(), headers: Vec::new() }
}

/// Отказ, в котором сервер просит подождать (`Retry-After: wait`).
fn status_asking_to_wait(code: u16, wait: &str) -> NetAnswer {
    NetAnswer::Response {
        status: code,
        body: Vec::new(),
        headers: vec![NetHeader { name: "Retry-After".to_owned(), value: wait.to_owned() }],
    }
}

fn body(text: &str) -> NetAnswer {
    NetAnswer::Response { status: 200, body: text.as_bytes().to_vec(), headers: Vec::new() }
}

fn no_answer(reason: &str) -> NetAnswer {
    NetAnswer::NoAnswer { reason: reason.to_owned() }
}

/// Ядро с онлайн-источниками поверх `transport`, без пауз между повторами.
fn online(dir: &Scratch, transport: Arc<dyn NetTransport>) -> Arc<Core> {
    let core = Core::open(dir.path()).unwrap();
    core.set_online(Some(Arc::new(registry(Arc::new(PlatformTransport(transport)), Some(|_| {})).unwrap())));
    core
}

fn search_problem(core: &Core) -> Option<OnlineProblem> {
    let sections = core.online_search("piano".to_owned(), 10, OnlineCancel::new()).unwrap();
    assert_eq!(sections.len(), 1);
    sections[0].problem
}

fn request() -> HttpRequest {
    HttpRequest {
        url: "https://archive.org/advancedsearch.php?q=piano".to_owned(),
        headers: Vec::new(),
        timeout: Duration::from_secs(15),
        max_body_bytes: 1000,
    }
}

#[test]
fn what_went_wrong_decides_what_the_section_says() {
    let cases = [
        // Сервер ответил отказом: сеть есть, виноват он.
        (status(500), OnlineProblem::ProviderDown),
        (status(502), OnlineProblem::ProviderDown),
        (status(503), OnlineProblem::ProviderDown),
        (status(504), OnlineProblem::ProviderDown),
        (status(403), OnlineProblem::ProviderDown),
        (status(404), OnlineProblem::ProviderDown),
        // Не дождались — тоже не «нет сети».
        (NetAnswer::TimedOut, OnlineProblem::ProviderDown),
        // Просят подождать.
        (status(429), OnlineProblem::RateLimited),
        (status_asking_to_wait(429, "30"), OnlineProblem::RateLimited),
        // Соединения не вышло: это и есть «нет сети» (и подсказка про прошивку).
        (no_answer("ConnectException"), OnlineProblem::NoNetwork),
        (no_answer("UnknownHostException"), OnlineProblem::NoNetwork),
        // Ответ пришёл, но это не то: остаётся «ответил непонятно».
        (body("<html>maintenance</html>"), OnlineProblem::Broken),
    ];
    for (reply, expected) in cases {
        let dir = Scratch::new();
        let core = online(&dir, Script::new([reply.clone()]));

        let sections = core.online_search("piano".to_owned(), 10, OnlineCancel::new()).unwrap();

        assert_eq!(sections[0].problem, Some(expected), "{reply:?}");
        assert!(sections[0].results.is_empty(), "{reply:?}");
    }
}

#[test]
fn a_short_wait_is_waited_and_the_search_goes_on() {
    let dir = Scratch::new();
    let script = Script::new([status_asking_to_wait(429, "2"), body(SEARCH_JSON)]);
    let core = online(&dir, script.clone());

    let sections = core.online_search("piano".to_owned(), 10, OnlineCancel::new()).unwrap();

    assert_eq!(sections[0].problem, None);
    assert_eq!(sections[0].results.len(), 1);
    assert_eq!(script.calls(), 2);
}

#[test]
fn a_server_that_names_a_long_wait_is_asked_once_and_then_left_alone() {
    let dir = Scratch::new();
    // Если бы заголовок до ядра не дошёл, второй ответ сценария спас бы поиск.
    let script = Script::new([status_asking_to_wait(429, "120"), body(SEARCH_JSON)]);
    let core = online(&dir, script.clone());

    let first = search_problem(&core);
    let calls_after_first = script.calls();
    let second = search_problem(&core);

    assert_eq!(first, Some(OnlineProblem::RateLimited));
    assert_eq!(calls_after_first, 1, "a minute of waiting is not a retry");
    assert!(second.is_some());
    assert_eq!(script.calls(), 1, "while the server asks to wait, nobody knocks");
}

#[test]
fn the_platform_transport_hands_over_headers_and_timeouts() {
    let reply = NetAnswer::Response {
        status: 429,
        body: b"slow down".to_vec(),
        headers: vec![
            NetHeader { name: "Retry-After".to_owned(), value: "5".to_owned() },
            NetHeader { name: "Content-Type".to_owned(), value: "text/plain".to_owned() },
        ],
    };

    let response = PlatformTransport(Script::new([reply])).get(&request()).unwrap();

    assert_eq!(response.status, 429);
    assert_eq!(response.body, b"slow down");
    assert_eq!(
        response.headers,
        [("Retry-After".to_owned(), "5".to_owned()), ("Content-Type".to_owned(), "text/plain".to_owned())]
    );
    assert_eq!(response.header("retry-after"), Some("5"));

    let timed_out = PlatformTransport(Script::new([NetAnswer::TimedOut])).get(&request());
    assert_eq!(timed_out, Err(CoreError::network("timed out")));
    assert_eq!(
        NetFailure::of(&timed_out.unwrap_err()),
        Some(NetFailure::TimedOut),
        "the core reads a timeout of the platform the way it reads its own"
    );
    // Остальное — как было.
    let none = PlatformTransport(Script::new([no_answer("ConnectException")])).get(&request());
    assert_eq!(none, Err(CoreError::network("ConnectException")));
    let large = PlatformTransport(Script::new([NetAnswer::TooLarge])).get(&request());
    assert_eq!(large, Err(CoreError::parse("response is too large")));
}

#[test]
fn an_album_that_cannot_be_opened_says_why_and_is_not_an_error() {
    let cases = [
        (status(503), OnlineProblem::ProviderDown),
        (status(403), OnlineProblem::ProviderDown),
        (status(404), OnlineProblem::ProviderDown),
        (NetAnswer::TimedOut, OnlineProblem::ProviderDown),
        (status(429), OnlineProblem::RateLimited),
        (status_asking_to_wait(429, "30"), OnlineProblem::RateLimited),
        (no_answer("ConnectException"), OnlineProblem::NoNetwork),
        (body("<html>maintenance</html>"), OnlineProblem::Broken),
    ];
    for (reply, expected) in cases {
        let dir = Scratch::new();
        let core = online(&dir, Script::new([reply.clone()]));

        let album = core.online_album("archive.org".to_owned(), "MIXG031".to_owned()).unwrap();

        assert_eq!(album.problem, Some(expected), "{reply:?}");
        assert!(album.tracks.is_empty(), "{reply:?}");
    }
}

#[test]
fn an_album_that_opens_has_tracks_and_no_problem() {
    let dir = Scratch::new();
    let fixtures = Path::new(env!("CARGO_MANIFEST_DIR")).join("../providers/tests/fixtures/ia");
    let core = online(&dir, Arc::new(Recorded(FixtureTransport::recorded(&fixtures).unwrap())));

    let album = core.online_album("archive.org".to_owned(), RECORD.to_owned()).unwrap();

    assert_eq!(album.problem, None);
    assert!(!album.tracks.is_empty());
}

#[test]
fn switched_off_an_album_is_still_an_error_not_a_problem() {
    let dir = Scratch::new();
    let core = online(&dir, Script::new([body(SEARCH_JSON)]));
    core.disconnect_online().unwrap();

    let album = core.online_album("archive.org".to_owned(), RECORD.to_owned());

    assert!(matches!(album, Err(CoreError::Unavailable { .. })), "{album:?}");
}

#[test]
fn the_same_table_is_open_to_kotlin() {
    assert_eq!(check_http_status(200, None), Ok(()));
    assert_eq!(check_http_status(204, Some("30".to_owned())), Ok(()));
    assert_eq!(check_http_status(404, None), Err(CoreError::unavailable("http 404")));
    assert_eq!(check_http_status(410, None), Err(CoreError::unavailable("http 410")));
    assert_eq!(check_http_status(403, None), Err(CoreError::network("http 403")));
    assert_eq!(check_http_status(503, None), Err(CoreError::network("http 503")));

    // Заголовок приходит строкой, как его прислал сервер.
    let error = check_http_status(429, Some("30".to_owned())).unwrap_err();
    assert_eq!(
        NetFailure::of(&error),
        Some(NetFailure::Status { status: 429, retry_after: Some(Duration::from_secs(30)) })
    );
    // Дата и мусор — не срок.
    assert_eq!(
        check_http_status(429, Some("Wed, 21 Oct 2015 07:28:00 GMT".to_owned())),
        Err(CoreError::network("http 429"))
    );
    assert_eq!(check_http_status(503, Some("later".to_owned())), Err(CoreError::network("http 503")));
}
