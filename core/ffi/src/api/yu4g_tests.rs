//! Ю4г (`docs/work/yu4g.md`): поиск у одного провайдера на границе ядра. `Core::online_search_in(provider, query, limit,
//! cancel)` отвечает секцией названного провайдера и никого больше не спрашивает: затравка радио ищется у YouTube, и
//! запрос «исполнитель название» не уходит в Internet Archive (и запуск радио не ждёт его ответа).
//!
//! `Core::online_search_in` — новый API (сигнатура в файле задачи): пока его нет, тесты крейта не собираются. Платформа
//! здесь — минимальный «youtube» в памяти, сеть Internet Archive — записанные ответы, которые считают запросы (как в
//! `yu3g_tests.rs`).

use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use plinth_providers::http::{HttpRequest, HttpTransport};
use plinth_providers::testing::FixtureTransport;
use plinth_types::{CoreError, QualityTier};

use super::external_api::{
    ExternalCapabilities, ExternalFailure, ExternalProvider, ExternalRadio, ExternalResolve, ExternalSearch,
    ExternalStream,
};
use super::online_api::{
    NetAnswer, NetRequest, NetTransport, OnlineCancel, OnlineKind, OnlineProblem, OnlineResult, OnlineSearch,
};
use crate::session::Core;
use crate::testing::Scratch;

const ARCHIVE: &str = "archive.org";
const YOUTUBE: &str = "youtube";
/// Видео YouTube: одиннадцать знаков.
const VIDEO: &str = "dQw4w9WgXcQ";

// --- Платформа в памяти -----------------------------------------------------------

/// Провайдер платформы с одним видео. Считает, сколько раз ядро его спросило, и помнит, о чём.
struct Platform {
    asked: AtomicUsize,
    questions: Mutex<Vec<(String, u32)>>,
    failure: Option<ExternalFailure>,
}

impl Platform {
    fn youtube() -> Arc<Self> {
        Arc::new(Self { asked: AtomicUsize::new(0), questions: Mutex::new(Vec::new()), failure: None })
    }

    /// На каждый поиск отвечает отказом.
    fn failing(failure: ExternalFailure) -> Arc<Self> {
        Arc::new(Self { asked: AtomicUsize::new(0), questions: Mutex::new(Vec::new()), failure: Some(failure) })
    }

    fn asked(&self) -> usize {
        self.asked.load(Ordering::SeqCst)
    }

    /// Что у платформы спросили: текст запроса и число результатов.
    fn questions(&self) -> Vec<(String, u32)> {
        self.questions.lock().unwrap().clone()
    }
}

impl ExternalProvider for Platform {
    fn id(&self) -> String {
        YOUTUBE.to_owned()
    }

    fn capabilities(&self) -> ExternalCapabilities {
        ExternalCapabilities { finds: vec![OnlineKind::Track], best_quality: QualityTier::Standard }
    }

    fn search(&self, query: String, limit: u32) -> ExternalSearch {
        self.asked.fetch_add(1, Ordering::SeqCst);
        self.questions.lock().unwrap().push((query, limit));
        if let Some(failure) = &self.failure {
            return ExternalSearch::Failed { failure: failure.clone() };
        }
        let found = OnlineResult {
            external: VIDEO.to_owned(),
            kind: OnlineKind::Track,
            title: "Piano Man".to_owned(),
            artist: Some("Billy Joel".to_owned()),
            year: None,
            duration_ms: Some(338_000),
        };
        ExternalSearch::Found { results: vec![found] }
    }

    fn resolve(&self, _: String) -> ExternalResolve {
        ExternalResolve::Found { tracks: Vec::new() }
    }

    fn stream(&self, _: String) -> ExternalStream {
        ExternalStream::Failed { failure: unavailable("none") }
    }

    fn radio(&self, _: String, _: Option<String>) -> ExternalRadio {
        ExternalRadio::Failed { failure: unavailable("none") }
    }
}

// --- Сеть Internet Archive: записанные ответы, запросы считаются ---------------------

struct Archive {
    fixtures: FixtureTransport,
    asked: AtomicUsize,
}

impl Archive {
    fn recorded() -> Arc<Self> {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../providers/tests/fixtures/ia");
        Arc::new(Self { fixtures: FixtureTransport::recorded(&dir).unwrap(), asked: AtomicUsize::new(0) })
    }

    fn asked(&self) -> usize {
        self.asked.load(Ordering::SeqCst)
    }
}

impl NetTransport for Archive {
    fn get(&self, request: NetRequest) -> NetAnswer {
        self.asked.fetch_add(1, Ordering::SeqCst);
        let request = HttpRequest {
            url: request.url,
            headers: request.headers.into_iter().map(|h| (h.name, h.value)).collect(),
            timeout: Duration::from_millis(request.timeout_ms),
            max_body_bytes: request.max_body_bytes,
        };
        match self.fixtures.get(&request) {
            Ok(response) => NetAnswer::Response { status: response.status, body: response.body, headers: Vec::new() },
            Err(error) => NetAnswer::NoAnswer { reason: error.to_string() },
        }
    }
}

// --- Помощники ----------------------------------------------------------------------

fn unavailable(text: &str) -> ExternalFailure {
    ExternalFailure::Unavailable { text: text.to_owned() }
}

fn network(text: &str) -> ExternalFailure {
    ExternalFailure::Network { text: text.to_owned() }
}

fn parse(text: &str) -> ExternalFailure {
    ExternalFailure::Parse { text: text.to_owned() }
}

/// Ядро с Internet Archive на записанных ответах (если `with_archive`) и платформой `platform`.
fn connected(dir: &Scratch, archive: &Arc<Archive>, platform: &Arc<Platform>, with_archive: bool) -> Arc<Core> {
    let core = Core::open(dir.path()).unwrap();
    let platforms = vec![Arc::clone(platform) as Arc<dyn ExternalProvider>];
    core.connect_online(Arc::clone(archive) as Arc<dyn NetTransport>, platforms, with_archive).unwrap();
    core
}

fn ask_in(core: &Core, provider: &str) -> Result<Option<OnlineSearch>, CoreError> {
    core.online_search_in(provider.to_owned(), "Piano".to_owned(), 10, OnlineCancel::new())
}

// --- Кого спрашивают -------------------------------------------------------------------

#[test]
fn the_platform_is_asked_and_the_archive_is_not() {
    let dir = Scratch::new();
    let (archive, platform) = (Archive::recorded(), Platform::youtube());
    let core = connected(&dir, &archive, &platform, true);

    let section = ask_in(&core, YOUTUBE).unwrap().unwrap();

    assert_eq!(section.provider, YOUTUBE);
    assert_eq!(section.problem, None);
    let found: Vec<_> = section.results.iter().map(|one| (one.external.as_str(), one.kind)).collect();
    assert_eq!(found, [(VIDEO, OnlineKind::Track)]);
    assert_eq!(platform.asked(), 1);
    assert_eq!(archive.asked(), 0, "Internet Archive gets no request: nothing leaks to it, nothing waits for it");
}

#[test]
fn the_archive_can_be_asked_alone_too() {
    let dir = Scratch::new();
    let (archive, platform) = (Archive::recorded(), Platform::youtube());
    let core = connected(&dir, &archive, &platform, true);

    let section = ask_in(&core, ARCHIVE).unwrap().unwrap();

    assert_eq!(section.provider, ARCHIVE);
    assert_eq!(section.problem, None);
    assert!(!section.results.is_empty(), "the recorded answer has results about the piano");
    assert!(archive.asked() > 0);
    assert_eq!(platform.asked(), 0, "the filter is not tied to YouTube");
}

/// Страж: поиск по всем не изменился — оба провайдера, Internet Archive первым, оба спрошены.
#[test]
fn the_search_of_all_still_asks_everybody_in_order() {
    let dir = Scratch::new();
    let (archive, platform) = (Archive::recorded(), Platform::youtube());
    let core = connected(&dir, &archive, &platform, true);

    let sections = core.online_search("Piano".to_owned(), 10, OnlineCancel::new()).unwrap();

    let names: Vec<_> = sections.iter().map(|section| section.provider.as_str()).collect();
    assert_eq!(names, [ARCHIVE, YOUTUBE]);
    assert!(archive.asked() > 0);
    assert_eq!(platform.asked(), 1);
}

#[test]
fn the_query_and_the_limit_reach_the_platform_as_asked() {
    let dir = Scratch::new();
    let (archive, platform) = (Archive::recorded(), Platform::youtube());
    let core = connected(&dir, &archive, &platform, true);

    core.online_search_in(YOUTUBE.to_owned(), "  Billy Joel Piano Man \n".to_owned(), 7, OnlineCancel::new()).unwrap();

    assert_eq!(platform.questions(), [("Billy Joel Piano Man".to_owned(), 7)]);
}

// --- Кого нет ----------------------------------------------------------------------------

#[test]
fn a_provider_that_is_not_connected_gives_nothing_and_nobody_is_asked() {
    let dir = Scratch::new();
    let (archive, platform) = (Archive::recorded(), Platform::youtube());
    let core = connected(&dir, &archive, &platform, false);

    assert_eq!(ask_in(&core, ARCHIVE).unwrap(), None, "the archive is switched off");
    assert_eq!(ask_in(&core, "bandcamp").unwrap(), None, "a stranger");

    assert_eq!((archive.asked(), platform.asked()), (0, 0));
}

#[test]
fn with_the_sources_off_there_is_no_section() {
    let dir = Scratch::new();
    let (archive, platform) = (Archive::recorded(), Platform::youtube());
    let core = Core::open(dir.path()).unwrap();
    assert_eq!(ask_in(&core, YOUTUBE).unwrap(), None, "never connected");

    let platforms = vec![Arc::clone(&platform) as Arc<dyn ExternalProvider>];
    core.connect_online(Arc::clone(&archive) as Arc<dyn NetTransport>, platforms, true).unwrap();
    core.disconnect_online().unwrap();
    assert_eq!(ask_in(&core, YOUTUBE).unwrap(), None, "disconnected");

    assert_eq!((archive.asked(), platform.asked()), (0, 0));
}

#[test]
fn a_blank_query_asks_nobody() {
    let dir = Scratch::new();
    let (archive, platform) = (Archive::recorded(), Platform::youtube());
    let core = connected(&dir, &archive, &platform, true);

    for text in ["", "   ", "\t\n"] {
        let found = core.online_search_in(YOUTUBE.to_owned(), text.to_owned(), 10, OnlineCancel::new()).unwrap();
        assert_eq!(found, None, "{text:?}");
    }

    assert_eq!((archive.asked(), platform.asked()), (0, 0));
}

#[test]
fn a_bad_provider_name_is_an_error_of_the_call_and_nobody_is_asked() {
    let dir = Scratch::new();
    let (archive, platform) = (Archive::recorded(), Platform::youtube());
    let core = connected(&dir, &archive, &platform, true);

    for bad in ["", "YouTube", "you tube", "a/b"] {
        let error = ask_in(&core, bad).unwrap_err();
        assert!(matches!(error, CoreError::Parse { .. }), "{bad:?}: {error:?}");
    }

    assert_eq!((archive.asked(), platform.asked()), (0, 0));
}

#[test]
fn a_search_cancelled_before_it_starts_asks_nobody() {
    let dir = Scratch::new();
    let (archive, platform) = (Archive::recorded(), Platform::youtube());
    let core = connected(&dir, &archive, &platform, true);
    let cancel = OnlineCancel::new();
    cancel.cancel();

    let found = core.online_search_in(YOUTUBE.to_owned(), "Piano".to_owned(), 10, cancel).unwrap();

    assert_eq!(found, None);
    assert_eq!((archive.asked(), platform.asked()), (0, 0));
}

// --- Что в секции --------------------------------------------------------------------------

/// Секция одного провайдера — та же, что поиск по всем даёт ему: и удача, и каждая беда читаются одинаково.
#[test]
fn the_section_is_the_one_the_search_of_all_gives_that_provider() {
    let failures = [None, Some(network("no route")), Some(unavailable("gone")), Some(parse("junk"))];
    for failure in failures {
        let platform = || failure.clone().map_or_else(Platform::youtube, Platform::failing);

        let (dir, archive, alone_platform) = (Scratch::new(), Archive::recorded(), platform());
        let alone = ask_in(&connected(&dir, &archive, &alone_platform, true), YOUTUBE).unwrap();

        let (dir, archive, all_platform) = (Scratch::new(), Archive::recorded(), platform());
        let core = connected(&dir, &archive, &all_platform, true);
        let all = core.online_search("Piano".to_owned(), 10, OnlineCancel::new()).unwrap();
        let from_all = all.into_iter().find(|section| section.provider == YOUTUBE);

        assert_eq!(alone, from_all, "{failure:?}");
        let alone = alone.unwrap();
        assert_eq!(alone.problem.is_some(), failure.is_some(), "{failure:?}");
        assert_eq!(alone.results.is_empty(), failure.is_some(), "{failure:?}");
    }
}

/// Страж: беда провайдера — это секция с бедой, а не пустота и не ошибка вызова.
#[test]
fn a_platform_that_does_not_answer_is_a_problem_of_the_section() {
    let dir = Scratch::new();
    let (archive, platform) = (Archive::recorded(), Platform::failing(network("http 503")));
    let core = connected(&dir, &archive, &platform, true);

    let section = ask_in(&core, YOUTUBE).unwrap().unwrap();

    assert_eq!(section.problem, Some(OnlineProblem::ProviderDown));
    assert!(section.results.is_empty());
    assert_eq!(archive.asked(), 0);
}
