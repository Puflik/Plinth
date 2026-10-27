//! Ю4а (`docs/work/yu4a.md`): радио от трека на границе ядра. Платформенный провайдер отдаёт подборку
//! страницами (`ExternalProvider::radio`), `Core::online_radio` — приложению, `Core::online_origins` — откуда
//! играется трек каталога.
//!
//! `ExternalProvider::radio`, `ExternalRadio`, `Core::online_radio`, `Core::online_origins`, `OnlineRadioReply`
//! и `OnlineOrigin` — новый API (сигнатуры в `docs/work/yu4a.md`): пока их нет, тесты крейта не собираются.
//! Двойники платформы в `yu3v_tests.rs` и `yu3g_tests.rs` получили только вставку `fn radio` (она нужна, чтобы они
//! собирались: у внешнего трейта UniFFI методов по умолчанию не бывает).
//!
//! Платформа здесь — `Radio`: провайдер в памяти с ручками отказа и паники. Internet Archive не подключается
//! (`archive = false`), сети нет вовсе.

use std::sync::{Arc, Mutex};

use plinth_types::{CoreError, Format, QualityTier, TrackId};

use super::external_api::{
    ExternalCapabilities, ExternalFailure, ExternalProvider, ExternalRadio, ExternalResolve, ExternalSearch,
    ExternalStream,
};
use super::online_api::{
    NetAnswer, NetRequest, NetTransport, OnlineCancel, OnlineKind, OnlineOrigin, OnlineProblem, OnlineResult,
    OnlineTrackInfo, OnlineVariant,
};
use crate::session::Core;
use crate::testing::Scratch;

const NAME: &str = "radio.test";
const SEED: &str = "dQw4w9WgXcQ";

// --- Платформа в памяти ---------------------------------------------------------------

#[derive(Default)]
struct State {
    calls: Vec<String>,
    failure: Option<ExternalFailure>,
    boom: bool,
    /// Ответы по ключу страницы (`None` — первая); затравка в выборе не участвует.
    answers: Vec<(Option<String>, ExternalRadio)>,
}

struct Radio {
    state: Mutex<State>,
}

impl Radio {
    fn new() -> Arc<Self> {
        Arc::new(Self { state: Mutex::new(State::default()) })
    }

    fn answer(&self, page: Option<&str>, answer: ExternalRadio) {
        self.state.lock().unwrap().answers.push((page.map(str::to_owned), answer));
    }

    fn fail_with(&self, failure: Option<ExternalFailure>) {
        self.state.lock().unwrap().failure = failure;
    }

    fn blow_up(&self, boom: bool) {
        self.state.lock().unwrap().boom = boom;
    }

    fn calls(&self) -> Vec<String> {
        self.state.lock().unwrap().calls.clone()
    }
}

impl ExternalProvider for Radio {
    fn id(&self) -> String {
        NAME.to_owned()
    }

    fn capabilities(&self) -> ExternalCapabilities {
        ExternalCapabilities { finds: vec![OnlineKind::Track], best_quality: QualityTier::Standard }
    }

    fn search(&self, _: String, _: u32) -> ExternalSearch {
        ExternalSearch::Found { results: Vec::new() }
    }

    fn resolve(&self, _: String) -> ExternalResolve {
        ExternalResolve::Found { tracks: Vec::new() }
    }

    fn stream(&self, _: String) -> ExternalStream {
        ExternalStream::Failed { failure: unavailable("none") }
    }

    /// Паника случается вне замка, чтобы не отравить его.
    fn radio(&self, seed: String, page: Option<String>) -> ExternalRadio {
        let (failure, boom, answer) = {
            let mut state = self.state.lock().unwrap();
            state.calls.push(format!("radio:{seed}:{}", page.as_deref().unwrap_or("-")));
            let answer = state.answers.iter().find(|(key, _)| *key == page).map(|(_, answer)| answer.clone());
            (state.failure.clone(), state.boom, answer)
        };
        assert!(!boom, "SECRET: the platform blew up in radio");
        if let Some(failure) = failure {
            return ExternalRadio::Failed { failure };
        }
        answer.unwrap_or(ExternalRadio::Failed { failure: unavailable("page") })
    }
}

/// Платформа, которая радио не умеет, но ищет: её ответ на `radio` — «не умею».
struct Plain;

impl ExternalProvider for Plain {
    fn id(&self) -> String {
        "plain.test".to_owned()
    }

    fn capabilities(&self) -> ExternalCapabilities {
        ExternalCapabilities { finds: vec![OnlineKind::Track], best_quality: QualityTier::Standard }
    }

    fn search(&self, _: String, _: u32) -> ExternalSearch {
        let found = OnlineResult {
            external: SEED.to_owned(),
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
        ExternalRadio::Failed { failure: unavailable("radio is not supported") }
    }
}

// --- Помощники ------------------------------------------------------------------------

struct NoNet;

impl NetTransport for NoNet {
    fn get(&self, _: NetRequest) -> NetAnswer {
        NetAnswer::NoAnswer { reason: "no network in this test".to_owned() }
    }
}

fn dynamic(radio: &Arc<Radio>) -> Arc<dyn ExternalProvider> {
    radio.clone()
}

/// Ядро с платформенными провайдерами `platforms` и без Internet Archive.
fn connected(dir: &Scratch, platforms: Vec<Arc<dyn ExternalProvider>>) -> Arc<Core> {
    let core = Core::open(dir.path()).unwrap();
    core.connect_online(Arc::new(NoNet), platforms, false).unwrap();
    core
}

fn found(tracks: Vec<OnlineTrackInfo>, next: Option<&str>) -> ExternalRadio {
    ExternalRadio::Found { tracks, next: next.map(str::to_owned) }
}

/// Годный трек: два варианта, как у видео YouTube.
fn info(video: &str, title: &str) -> OnlineTrackInfo {
    OnlineTrackInfo {
        external: video.to_owned(),
        title: title.to_owned(),
        artist: Some("Artist".to_owned()),
        album: None,
        number: None,
        year: None,
        duration_ms: Some(200_000),
        mbid: None,
        variants: vec![
            OnlineVariant { external: format!("{video}/251"), format: Format::Opus, bitrate_kbps: Some(160) },
            OnlineVariant { external: format!("{video}/140"), format: Format::Aac, bitrate_kbps: Some(128) },
        ],
    }
}

fn without_variants(video: &str) -> OnlineTrackInfo {
    OnlineTrackInfo { variants: Vec::new(), ..info(video, "Hollow") }
}

fn without_title(video: &str) -> OnlineTrackInfo {
    OnlineTrackInfo { title: "   ".to_owned(), ..info(video, "x") }
}

fn network(text: &str) -> ExternalFailure {
    ExternalFailure::Network { text: text.to_owned() }
}

fn parse(text: &str) -> ExternalFailure {
    ExternalFailure::Parse { text: text.to_owned() }
}

fn unavailable(text: &str) -> ExternalFailure {
    ExternalFailure::Unavailable { text: text.to_owned() }
}

fn internal(text: &str) -> ExternalFailure {
    ExternalFailure::Internal { text: text.to_owned() }
}

fn ask(core: &Core, page: Option<&str>) -> Result<super::online_api::OnlineRadioReply, CoreError> {
    core.online_radio(NAME.to_owned(), SEED.to_owned(), page.map(str::to_owned))
}

// --- Страницы -------------------------------------------------------------------------

#[test]
fn the_first_page_brings_its_tracks_and_the_key_of_the_next() {
    let dir = Scratch::new();
    let radio = Radio::new();
    radio.answer(None, found(vec![info("aaaaaaaaaaa", "One"), info("bbbbbbbbbbb", "Два")], Some("page-2")));
    let core = connected(&dir, vec![dynamic(&radio)]);

    let reply = ask(&core, None).unwrap();

    assert_eq!(reply.problem, None);
    assert_eq!(reply.next.as_deref(), Some("page-2"));
    assert_eq!(reply.tracks, vec![info("aaaaaaaaaaa", "One"), info("bbbbbbbbbbb", "Два")]);
    assert_eq!(radio.calls(), [format!("radio:{SEED}:-")]);
}

#[test]
fn the_key_leads_to_the_next_page_and_the_last_page_has_none() {
    let dir = Scratch::new();
    let radio = Radio::new();
    radio.answer(None, found(vec![info("aaaaaaaaaaa", "One")], Some("page-2")));
    radio.answer(Some("page-2"), found(vec![info("bbbbbbbbbbb", "Two")], None));
    let core = connected(&dir, vec![dynamic(&radio)]);

    let first = ask(&core, None).unwrap();
    let second = ask(&core, first.next.as_deref()).unwrap();

    assert_eq!(second.problem, None);
    assert_eq!(second.tracks, vec![info("bbbbbbbbbbb", "Two")]);
    assert_eq!(second.next, None, "подборка кончилась");
    assert_eq!(radio.calls(), [format!("radio:{SEED}:-"), format!("radio:{SEED}:page-2")]);
}

#[test]
fn the_seed_and_the_key_reach_the_platform_verbatim() {
    let dir = Scratch::new();
    let radio = Radio::new();
    radio.answer(Some("ключ 1/== ю"), found(vec![info("ccccccccccc", "Three")], None));
    let core = connected(&dir, vec![dynamic(&radio)]);

    let reply =
        core.online_radio(NAME.to_owned(), "dQw4w9WgXcQ/251".to_owned(), Some("ключ 1/== ю".to_owned())).unwrap();

    assert_eq!(reply.tracks.len(), 1);
    assert_eq!(radio.calls(), ["radio:dQw4w9WgXcQ/251:ключ 1/== ю"]);
}

#[test]
fn a_bad_track_is_dropped_and_the_page_goes_on() {
    let dir = Scratch::new();
    let radio = Radio::new();
    radio.answer(
        None,
        found(
            vec![
                info("aaaaaaaaaaa", "One"),
                without_variants("bbbbbbbbbbb"),
                without_title("ccccccccccc"),
                info("ddddddddddd", "Four"),
            ],
            Some("page-2"),
        ),
    );
    let core = connected(&dir, vec![dynamic(&radio)]);

    let reply = ask(&core, None).unwrap();

    assert_eq!(reply.problem, None);
    assert_eq!(reply.tracks, vec![info("aaaaaaaaaaa", "One"), info("ddddddddddd", "Four")]);
    assert_eq!(reply.next.as_deref(), Some("page-2"));
}

#[test]
fn a_page_of_nothing_usable_is_broken() {
    let dir = Scratch::new();
    let radio = Radio::new();
    radio.answer(None, found(vec![without_variants("aaaaaaaaaaa"), without_title("bbbbbbbbbbb")], Some("page-2")));
    let core = connected(&dir, vec![dynamic(&radio)]);

    let reply = ask(&core, None).unwrap();

    assert_eq!(reply.problem, Some(OnlineProblem::Broken));
    assert!(reply.tracks.is_empty());
    assert_eq!(reply.next, None);
}

#[test]
fn an_empty_page_is_not_an_error_with_a_key_or_without_one() {
    let dir = Scratch::new();
    let radio = Radio::new();
    radio.answer(None, found(Vec::new(), Some("later")));
    radio.answer(Some("later"), found(Vec::new(), None));
    let core = connected(&dir, vec![dynamic(&radio)]);

    let first = ask(&core, None).unwrap();
    let second = ask(&core, first.next.as_deref()).unwrap();

    assert_eq!((first.problem, first.tracks.len(), first.next.as_deref()), (None, 0, Some("later")));
    assert_eq!((second.problem, second.tracks.len(), second.next), (None, 0, None));
}

#[test]
fn a_page_is_cut_at_a_hundred_tracks_from_the_end() {
    let dir = Scratch::new();
    let radio = Radio::new();
    let many = (0..150).map(|n| info(&format!("v{n:010}"), &format!("Song {n}"))).collect();
    radio.answer(None, found(many, None));
    let core = connected(&dir, vec![dynamic(&radio)]);

    let reply = ask(&core, None).unwrap();

    assert_eq!(reply.tracks.len(), 100);
    assert_eq!(reply.tracks[0].external, "v0000000000");
    assert_eq!(reply.tracks[99].external, "v0000000099");
}

#[test]
fn a_bad_key_from_the_platform_is_broken() {
    let keys = [String::new(), "line\nbreak".to_owned(), "k".repeat(1025)];
    for key in keys {
        let dir = Scratch::new();
        let radio = Radio::new();
        radio.answer(None, found(vec![info("aaaaaaaaaaa", "One")], Some(&key)));
        let core = connected(&dir, vec![dynamic(&radio)]);

        let reply = ask(&core, None).unwrap();

        assert_eq!(reply.problem, Some(OnlineProblem::Broken), "key of {} bytes", key.len());
        assert!(reply.tracks.is_empty());
    }
}

// --- Беды -----------------------------------------------------------------------------

#[test]
fn what_went_wrong_on_the_platform_is_a_problem_of_the_reply_not_an_error() {
    let cases = [
        (network("UnknownHostException"), OnlineProblem::NoNetwork),
        (network("timed out"), OnlineProblem::ProviderDown),
        (network("http 503"), OnlineProblem::ProviderDown),
        (network("http 429, retry after 30 s"), OnlineProblem::RateLimited),
        (network("http 429"), OnlineProblem::RateLimited),
        (parse("no audio streams"), OnlineProblem::Broken),
        (unavailable("page"), OnlineProblem::ProviderDown),
    ];
    for (failure, expected) in cases {
        let dir = Scratch::new();
        let radio = Radio::new();
        radio.fail_with(Some(failure.clone()));
        let core = connected(&dir, vec![dynamic(&radio)]);

        let reply = ask(&core, None).unwrap();

        assert_eq!(reply.problem, Some(expected), "{failure:?}");
        assert!(reply.tracks.is_empty());
        assert_eq!(reply.next, None);
    }
}

#[test]
fn an_internal_failure_of_the_platform_is_an_error_of_the_call() {
    let dir = Scratch::new();
    let radio = Radio::new();
    radio.fail_with(Some(internal("IllegalStateException")));
    let core = connected(&dir, vec![dynamic(&radio)]);

    let outcome = ask(&core, None);

    assert!(matches!(outcome, Err(CoreError::Internal { .. })), "{outcome:?}");
}

#[test]
fn a_panic_of_the_platform_is_internal_and_the_next_call_works() {
    let dir = Scratch::new();
    let radio = Radio::new();
    radio.answer(None, found(vec![info("aaaaaaaaaaa", "One")], None));
    let core = connected(&dir, vec![dynamic(&radio)]);
    radio.blow_up(true);

    let error = ask(&core, None).unwrap_err();

    assert!(matches!(error, CoreError::Internal { .. }), "{error:?}");

    radio.blow_up(false);
    assert_eq!(ask(&core, None).unwrap().tracks.len(), 1);
}

#[test]
fn radio_with_the_sources_off_is_an_error_of_the_call() {
    let dir = Scratch::new();
    let core = Core::open(dir.path()).unwrap();

    let never_on = ask(&core, None);
    core.connect_online(Arc::new(NoNet), vec![dynamic(&Radio::new())], false).unwrap();
    core.disconnect_online().unwrap();
    let switched_off = ask(&core, None);

    assert!(matches!(never_on, Err(CoreError::Unavailable { .. })), "{never_on:?}");
    assert!(matches!(switched_off, Err(CoreError::Unavailable { .. })), "{switched_off:?}");
}

#[test]
fn a_stranger_provider_is_a_problem_and_nobody_is_asked() {
    let dir = Scratch::new();
    let radio = Radio::new();
    let core = connected(&dir, vec![dynamic(&radio)]);

    let reply = core.online_radio("stranger.test".to_owned(), SEED.to_owned(), None).unwrap();

    assert_eq!(reply.problem, Some(OnlineProblem::ProviderDown));
    assert!(radio.calls().is_empty());
}

#[test]
fn bad_arguments_are_errors_and_the_platform_is_not_asked() {
    let dir = Scratch::new();
    let radio = Radio::new();
    let core = connected(&dir, vec![dynamic(&radio)]);

    let no_provider = core.online_radio(String::new(), SEED.to_owned(), None);
    let no_seed = core.online_radio(NAME.to_owned(), String::new(), None);
    let control_in_seed = core.online_radio(NAME.to_owned(), "a\u{7}b".to_owned(), None);
    let empty_key = core.online_radio(NAME.to_owned(), SEED.to_owned(), Some(String::new()));
    let huge_key = core.online_radio(NAME.to_owned(), SEED.to_owned(), Some("k".repeat(2000)));

    assert!(no_provider.is_err());
    for outcome in [no_seed, control_in_seed, empty_key, huge_key] {
        assert!(matches!(outcome, Err(CoreError::Parse { .. })), "{outcome:?}");
    }
    assert!(radio.calls().is_empty());
}

#[test]
fn a_platform_that_does_not_do_radio_is_down_for_radio_only_and_never_blamed() {
    let dir = Scratch::new();
    let core = connected(&dir, vec![Arc::new(Plain)]);

    for _ in 0..5 {
        let reply = core.online_radio("plain.test".to_owned(), SEED.to_owned(), None).unwrap();
        assert_eq!(reply.problem, Some(OnlineProblem::ProviderDown));
        assert!(reply.tracks.is_empty());
    }
    let sections = core.online_search("piano".to_owned(), 10, OnlineCancel::new()).unwrap();

    assert_eq!(sections.len(), 1);
    assert_eq!(sections[0].problem, None, "Unavailable провайдеру не в укор: поиск по нему жив");
    assert_eq!(sections[0].results.len(), 1);
}

#[test]
fn radio_failures_do_not_rest_the_provider_and_the_search_goes_on() {
    let dir = Scratch::new();
    let radio = Radio::new();
    radio.fail_with(Some(network("http 503")));
    radio.answer(None, found(vec![info("aaaaaaaaaaa", "One")], None));
    let core = connected(&dir, vec![dynamic(&radio)]);

    for _ in 0..5 {
        assert_eq!(ask(&core, None).unwrap().problem, Some(OnlineProblem::ProviderDown));
    }
    let sections = core.online_search("piano".to_owned(), 10, OnlineCancel::new()).unwrap();
    radio.fail_with(None);
    let reply = ask(&core, None).unwrap();

    assert_eq!(sections.len(), 1);
    assert_eq!(sections[0].problem, None, "поломка радио не убирает провайдера из поиска");
    assert_eq!(reply.problem, None);
    assert_eq!(reply.tracks.len(), 1);
    assert_eq!(radio.calls().len(), 6, "радио спрашивают каждый раз");
}

// --- Каталог --------------------------------------------------------------------------

#[test]
fn radio_writes_nothing_to_the_catalog() {
    let dir = Scratch::new();
    let radio = Radio::new();
    radio.answer(None, found(vec![info("aaaaaaaaaaa", "One"), info("bbbbbbbbbbb", "Two")], None));
    let core = connected(&dir, vec![dynamic(&radio)]);

    let reply = ask(&core, None).unwrap();
    let known = core.known_online_tracks(NAME.to_owned(), reply.tracks.clone()).unwrap();

    assert_eq!(reply.tracks.len(), 2);
    assert_eq!(known.len(), 2);
    assert!(known.iter().all(Option::is_none), "заводит треки приложение, как у альбома");
}

#[test]
fn the_origins_of_an_online_track_are_its_sources() {
    let dir = Scratch::new();
    let core = Core::open(dir.path()).unwrap();
    let ids = core.add_online_tracks(NAME.to_owned(), vec![info("aaaaaaaaaaa", "One")]).unwrap();

    let mut origins: Vec<(String, String)> =
        core.online_origins(ids[0]).unwrap().into_iter().map(|o| (o.provider, o.source)).collect();
    origins.sort();

    assert_eq!(
        origins,
        [(NAME.to_owned(), "aaaaaaaaaaa/140".to_owned()), (NAME.to_owned(), "aaaaaaaaaaa/251".to_owned())],
        "онлайн-источники для этого вызова включены быть не обязаны"
    );
}

#[test]
fn an_unknown_track_has_no_origins() {
    let dir = Scratch::new();
    let core = Core::open(dir.path()).unwrap();

    let origins: Vec<OnlineOrigin> = core.online_origins(TrackId::new()).unwrap();

    assert!(origins.is_empty());
}
