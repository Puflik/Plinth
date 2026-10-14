//! Р1.11а (`docs/work/r1-11a.md`, находка 10 ревью v0.2): настоящий
//! `Core::online_search` с отменой и общим сроком. Сеть Kotlin здесь —
//! заглушки `NetTransport`, которые стоят на месте, молчат или рвутся вместе с
//! отменой.
//!
//! `OnlineCancel`, третий параметр `online_search`, `online_search_within` и
//! `SEARCH_BUDGET` — новый API (сигнатуры в `docs/work/r1-11a.md`): пока их
//! нет, тесты крейта не собираются.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use super::online_api::{NetAnswer, NetRequest, NetTransport, OnlineCancel, OnlineProblem, SEARCH_BUDGET};
use crate::session::Core;
use crate::testing::Scratch;

const SEARCH_JSON: &str = r#"{"response":{"numFound":1,"start":0,"docs":[
    {"identifier":"MIXG031","title":"Vice Versa","creator":"Intoxicated Piano","year":2013}]}}"#;

/// Ядро с онлайн-источниками поверх `transport`; паузы между повторами настоящие.
fn online(dir: &Scratch, transport: Arc<dyn NetTransport>) -> Arc<Core> {
    let core = Core::open(dir.path()).unwrap();
    core.connect_online(transport).unwrap();
    core
}

fn no_answer(reason: &str) -> NetAnswer {
    NetAnswer::NoAnswer { reason: reason.to_owned() }
}

/// Сеть, которая стоит на месте, пока её не отпустят, а потом «не отвечает».
struct Gate {
    /// Сколько запросов вошло и открыты ли ворота.
    state: Mutex<(usize, bool)>,
    changed: Condvar,
}

impl Gate {
    fn new() -> Arc<Self> {
        Arc::new(Self { state: Mutex::new((0, false)), changed: Condvar::new() })
    }

    fn calls(&self) -> usize {
        self.state.lock().unwrap().0
    }

    /// Ждёт, пока первый запрос не войдёт в сеть.
    fn wait_entered(&self) {
        let state = self.state.lock().unwrap();
        let (state, _) = self.changed.wait_timeout_while(state, Duration::from_secs(10), |state| state.0 == 0).unwrap();
        assert!(state.0 > 0, "the request never reached the network");
    }

    fn open(&self) {
        self.state.lock().unwrap().1 = true;
        self.changed.notify_all();
    }
}

impl NetTransport for Gate {
    fn get(&self, _: NetRequest) -> NetAnswer {
        let mut state = self.state.lock().unwrap();
        state.0 += 1;
        self.changed.notify_all();
        drop(self.changed.wait_timeout_while(state, Duration::from_secs(10), |state| !state.1).unwrap());
        no_answer("SocketTimeoutException")
    }
}

/// Сеть, которая рвётся вместе с отменой: токен из ячейки отменяется, запрос
/// «не отвечает»; ячейка пуста — отвечает настоящим результатом поиска.
struct Torn {
    slot: Mutex<Option<Arc<OnlineCancel>>>,
    calls: AtomicUsize,
}

impl NetTransport for Torn {
    fn get(&self, _: NetRequest) -> NetAnswer {
        self.calls.fetch_add(1, Ordering::SeqCst);
        match self.slot.lock().unwrap().take() {
            Some(cancel) => {
                cancel.cancel();
                no_answer("SocketException")
            }
            None => NetAnswer::Response { status: 200, body: SEARCH_JSON.as_bytes().to_vec() },
        }
    }
}

/// Сеть, которая молчит: ждёт столько, сколько ей разрешили, и «не отвечает».
#[derive(Default)]
struct BlackHole {
    timeouts_ms: Mutex<Vec<u64>>,
}

impl NetTransport for BlackHole {
    fn get(&self, request: NetRequest) -> NetAnswer {
        self.timeouts_ms.lock().unwrap().push(request.timeout_ms);
        thread::sleep(Duration::from_millis(request.timeout_ms));
        no_answer("SocketTimeoutException")
    }
}

#[test]
fn the_search_budget_is_fifteen_seconds() {
    assert_eq!(SEARCH_BUDGET, Duration::from_secs(15));
}

#[test]
fn a_search_cancelled_before_the_call_asks_the_network_nothing() {
    let dir = Scratch::new();
    let gate = Gate::new();
    gate.open();
    let core = online(&dir, gate.clone());
    let cancel = OnlineCancel::new();
    cancel.cancel();

    let sections = core.online_search("piano".to_owned(), 10, cancel).unwrap();

    assert!(sections.is_empty());
    assert_eq!(gate.calls(), 0);
}

#[test]
fn a_search_cancelled_in_flight_is_not_retried() {
    let dir = Scratch::new();
    let gate = Gate::new();
    let core = online(&dir, gate.clone());
    let cancel = OnlineCancel::new();
    let worker = {
        let (core, cancel) = (Arc::clone(&core), Arc::clone(&cancel));
        thread::spawn(move || core.online_search("piano".to_owned(), 10, cancel))
    };
    gate.wait_entered();

    cancel.cancel();
    gate.open();
    let sections = worker.join().unwrap().unwrap();

    assert!(sections.is_empty(), "the answer to a cancelled search is nothing, not a problem of the provider");
    assert_eq!(gate.calls(), 1, "no second attempt after the cancel");
}

#[test]
fn cancelled_searches_do_not_bench_the_provider() {
    let dir = Scratch::new();
    let torn = Arc::new(Torn { slot: Mutex::new(None), calls: AtomicUsize::new(0) });
    let core = online(&dir, torn.clone());

    for _ in 0..3 {
        let cancel = OnlineCancel::new();
        *torn.slot.lock().unwrap() = Some(Arc::clone(&cancel));
        let sections = core.online_search("piano".to_owned(), 10, cancel).unwrap();
        assert!(sections.is_empty());
    }
    let sections = core.online_search("piano".to_owned(), 10, OnlineCancel::new()).unwrap();

    assert_eq!(torn.calls.load(Ordering::SeqCst), 4, "one request per search: cancelled ones are not retried");
    assert_eq!(sections.len(), 1);
    assert_eq!(sections[0].problem, None, "the provider is still asked and still answers");
    assert_eq!(sections[0].results.len(), 1);
}

#[test]
fn every_request_gets_at_most_what_is_left_of_the_search_budget() {
    struct Spy(Mutex<Vec<u64>>);
    impl NetTransport for Spy {
        fn get(&self, request: NetRequest) -> NetAnswer {
            self.0.lock().unwrap().push(request.timeout_ms);
            NetAnswer::TooLarge
        }
    }
    let dir = Scratch::new();
    let spy = Arc::new(Spy(Mutex::new(Vec::new())));
    let core = online(&dir, spy.clone());

    core.online_search_within("piano".to_owned(), 3, &OnlineCancel::new(), Duration::from_secs(3)).unwrap();
    core.online_search("piano".to_owned(), 3, OnlineCancel::new()).unwrap();

    let timeouts = spy.0.lock().unwrap().clone();
    assert_eq!(timeouts.len(), 2, "too large is not retried: {timeouts:?}");
    assert!(timeouts[0] <= 3_000 && timeouts[0] > 2_000, "{timeouts:?}");
    assert!(timeouts[1] <= 15_000 && timeouts[1] > 14_000, "the default budget is the ceiling: {timeouts:?}");
}

/// «Сеть есть, но молчит»: поиск кончается в срок, одной попыткой, и секция
/// говорит «нет сети», а не висит до минуты.
#[test]
fn a_silent_network_ends_at_the_search_budget() {
    let dir = Scratch::new();
    let hole = Arc::new(BlackHole::default());
    let core = online(&dir, hole.clone());
    let started = Instant::now();

    let sections =
        core.online_search_within("piano".to_owned(), 10, &OnlineCancel::new(), Duration::from_millis(600)).unwrap();

    assert!(started.elapsed() < Duration::from_secs(3), "took {:?}", started.elapsed());
    assert_eq!(sections.len(), 1);
    assert_eq!(sections[0].problem, Some(OnlineProblem::NoNetwork));
    assert_eq!(hole.timeouts_ms.lock().unwrap().len(), 1, "the first attempt used up the budget");
}
