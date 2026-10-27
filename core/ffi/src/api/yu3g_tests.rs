//! Ю3г, часть 1 (`docs/work/yu3g1.md`): переключатель по провайдерам. `connect_online` получает третий
//! аргумент — подключать ли Internet Archive. Выключенный не создаётся и не получает ни одного запроса,
//! а его треки остаются в каталоге и не играют, пока он выключен; то же для провайдера платформы,
//! которого не передали.
//!
//! Третий аргумент `connect_online` — новый API (сигнатура в `docs/work/yu3g1.md`): пока его нет, тесты
//! крейта не собираются. Платформа здесь — минимальный «youtube» в памяти, сеть Internet Archive —
//! записанные ответы, которые считают запросы.

use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use plinth_providers::http::{HttpRequest, HttpTransport};
use plinth_providers::testing::FixtureTransport;
use plinth_types::{CoreError, Format, QualityTier};

use super::external_api::{
    ExternalCapabilities, ExternalFailure, ExternalProvider, ExternalRadio, ExternalResolve, ExternalSearch,
    ExternalStream,
};
use super::online_api::{
    NetAnswer, NetHeader, NetRequest, NetTransport, OnlineCancel, OnlineKind, OnlineProblem, OnlineResult,
    OnlineTrackInfo, OnlineVariant, StreamAddress,
};
use crate::session::Core;
use crate::testing::Scratch;

const ARCHIVE: &str = "archive.org";
const YOUTUBE: &str = "youtube";
/// Альбом Internet Archive из записанных ответов.
const ALBUM: &str = "MIXG031";
/// Видео YouTube: одиннадцать знаков.
const VIDEO: &str = "dQw4w9WgXcQ";

// --- Платформа в памяти -----------------------------------------------------------

/// Провайдер платформы с одним видео. `asked` — сколько раз ядро его спросило.
struct Platform {
    name: String,
    asked: AtomicUsize,
}

impl Platform {
    fn youtube() -> Arc<Self> {
        Self::named(YOUTUBE)
    }

    fn named(name: &str) -> Arc<Self> {
        Arc::new(Self { name: name.to_owned(), asked: AtomicUsize::new(0) })
    }

    fn asked(&self) -> usize {
        self.asked.load(Ordering::SeqCst)
    }

    fn track() -> OnlineTrackInfo {
        OnlineTrackInfo {
            external: VIDEO.to_owned(),
            title: "Piano Man".to_owned(),
            artist: Some("Billy Joel".to_owned()),
            album: None,
            number: None,
            year: None,
            duration_ms: Some(338_000),
            mbid: None,
            variants: vec![OnlineVariant {
                external: format!("{VIDEO}/251"),
                format: Format::Opus,
                bitrate_kbps: Some(160),
            }],
        }
    }
}

impl ExternalProvider for Platform {
    fn id(&self) -> String {
        self.name.clone()
    }

    fn capabilities(&self) -> ExternalCapabilities {
        ExternalCapabilities { finds: vec![OnlineKind::Track], best_quality: QualityTier::Standard }
    }

    fn search(&self, _query: String, _limit: u32) -> ExternalSearch {
        self.asked.fetch_add(1, Ordering::SeqCst);
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

    fn resolve(&self, item: String) -> ExternalResolve {
        self.asked.fetch_add(1, Ordering::SeqCst);
        if item == VIDEO {
            ExternalResolve::Found { tracks: vec![Self::track()] }
        } else {
            ExternalResolve::Failed { failure: ExternalFailure::Unavailable { text: "id".to_owned() } }
        }
    }

    fn stream(&self, source: String) -> ExternalStream {
        self.asked.fetch_add(1, Ordering::SeqCst);
        let header = |name: &str, value: &str| NetHeader { name: name.to_owned(), value: value.to_owned() };
        ExternalStream::Found {
            address: StreamAddress {
                url: format!("https://rr1---sn-test.googlevideo.com/videoplayback?source={source}"),
                headers: vec![header("User-Agent", "com.google.visionos.youtube/1.02"), header("Range", "bytes=0-")],
                valid_for_ms: Some(7_200_000),
            },
        }
    }

    fn radio(&self, _: String, _: Option<String>) -> ExternalRadio {
        ExternalRadio::Failed { failure: ExternalFailure::Unavailable { text: "radio".to_owned() } }
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

fn open(dir: &Scratch) -> Arc<Core> {
    Core::open(dir.path()).unwrap()
}

fn with(platforms: &[&Arc<Platform>]) -> Vec<Arc<dyn ExternalProvider>> {
    platforms.iter().map(|platform| Arc::clone(*platform) as Arc<dyn ExternalProvider>).collect()
}

fn connect(
    core: &Core,
    archive: &Arc<Archive>,
    providers: Vec<Arc<dyn ExternalProvider>>,
    with_archive: bool,
) -> Result<(), CoreError> {
    core.connect_online(Arc::clone(archive) as Arc<dyn NetTransport>, providers, with_archive)
}

fn sections(core: &Core) -> Vec<super::online_api::OnlineSearch> {
    core.online_search("Piano".to_owned(), 10, OnlineCancel::new()).unwrap()
}

fn names(core: &Core) -> Vec<String> {
    sections(core).into_iter().map(|section| section.provider).collect()
}

fn is_unavailable<T: std::fmt::Debug>(outcome: &Result<T, CoreError>) -> bool {
    matches!(outcome, Err(CoreError::Unavailable { .. }))
}

// --- Internet Archive включён и выключен -----------------------------------------------

#[test]
fn with_the_archive_on_it_comes_first_and_is_asked() {
    let dir = Scratch::new();
    let core = open(&dir);
    let archive = Archive::recorded();
    connect(&core, &archive, with(&[&Platform::youtube()]), true).unwrap();

    assert_eq!(names(&core), [ARCHIVE, YOUTUBE]);
    assert!(archive.asked() > 0, "Internet Archive is asked while it is on");
}

#[test]
fn without_the_archive_the_platform_is_alone_and_the_archive_is_never_asked() {
    let dir = Scratch::new();
    let core = open(&dir);
    let archive = Archive::recorded();
    let youtube = Platform::youtube();
    connect(&core, &archive, with(&[&youtube]), false).unwrap();

    let found = sections(&core);

    assert_eq!(found.iter().map(|s| s.provider.as_str()).collect::<Vec<_>>(), [YOUTUBE]);
    assert_eq!(found[0].problem, None);
    assert_eq!(found[0].results.len(), 1);
    assert_eq!(found[0].results[0].external, VIDEO);
    assert_eq!(archive.asked(), 0, "a provider that is off is not asked at all");
    assert_eq!(youtube.asked(), 1);
}

#[test]
fn without_the_archive_its_album_is_a_problem_of_the_screen_not_an_error_of_the_call() {
    let dir = Scratch::new();
    let core = open(&dir);
    let archive = Archive::recorded();
    connect(&core, &archive, with(&[&Platform::youtube()]), false).unwrap();

    let reply = core.online_album(ARCHIVE.to_owned(), ALBUM.to_owned()).unwrap();

    assert!(reply.tracks.is_empty());
    assert_eq!(reply.problem, Some(OnlineProblem::ProviderDown));
    assert_eq!(archive.asked(), 0);
}

#[test]
fn a_track_of_the_archive_stays_in_the_catalog_while_the_archive_is_off_and_plays_again_when_it_is_on() {
    let dir = Scratch::new();
    let core = open(&dir);
    let archive = Archive::recorded();
    let youtube = Platform::youtube();
    connect(&core, &archive, with(&[&youtube]), true).unwrap();
    let tracks = core.online_album(ARCHIVE.to_owned(), ALBUM.to_owned()).unwrap().tracks;
    let ids = core.add_online_tracks(ARCHIVE.to_owned(), tracks.clone()).unwrap();
    let before = core.online_stream(ids[0], false, Vec::new()).unwrap();
    assert!(before.url.starts_with("https://archive.org/download/MIXG031/"), "{}", before.url);

    connect(&core, &archive, with(&[&youtube]), false).unwrap();
    let off = core.online_stream(ids[0], false, Vec::new());

    assert!(is_unavailable(&off), "{off:?}");
    let known = core.known_online_tracks(ARCHIVE.to_owned(), tracks.clone()).unwrap();
    assert_eq!(known, ids.iter().copied().map(Some).collect::<Vec<_>>(), "the catalog does not forget the tracks");
    assert_eq!(core.add_online_tracks(ARCHIVE.to_owned(), tracks).unwrap(), ids, "and does not duplicate them");

    connect(&core, &archive, with(&[&youtube]), true).unwrap();
    assert_eq!(core.online_stream(ids[0], false, Vec::new()).unwrap(), before);
}

#[test]
fn switching_the_archive_off_and_on_gives_the_same_search_each_time() {
    let dir = Scratch::new();
    let core = open(&dir);
    let archive = Archive::recorded();
    let youtube = Platform::youtube();
    connect(&core, &archive, with(&[&youtube]), true).unwrap();
    let first = sections(&core);

    connect(&core, &archive, with(&[&youtube]), false).unwrap();
    assert_eq!(names(&core), [YOUTUBE]);
    connect(&core, &archive, with(&[&youtube]), true).unwrap();

    assert_eq!(sections(&core), first);
}

// --- Провайдер платформы не передан --------------------------------------------------

#[test]
fn a_track_of_the_platform_stays_in_the_catalog_while_it_is_not_connected_and_plays_again_when_it_is() {
    let dir = Scratch::new();
    let core = open(&dir);
    let archive = Archive::recorded();
    let youtube = Platform::youtube();
    connect(&core, &archive, with(&[&youtube]), true).unwrap();
    let tracks = core.online_album(YOUTUBE.to_owned(), VIDEO.to_owned()).unwrap().tracks;
    let ids = core.add_online_tracks(YOUTUBE.to_owned(), tracks.clone()).unwrap();
    let before = core.online_stream(ids[0], false, Vec::new()).unwrap();
    assert!(before.url.contains(&format!("source={VIDEO}/251")), "{}", before.url);

    connect(&core, &archive, Vec::new(), true).unwrap();
    let asked = youtube.asked();
    let off = core.online_stream(ids[0], false, Vec::new());

    assert!(is_unavailable(&off), "{off:?}");
    assert_eq!(names(&core), [ARCHIVE], "no section of a provider that is not connected");
    let known = core.known_online_tracks(YOUTUBE.to_owned(), tracks).unwrap();
    assert_eq!(known, ids.iter().copied().map(Some).collect::<Vec<_>>());
    assert_eq!(youtube.asked(), asked, "a provider that was not handed to the core is not asked");

    connect(&core, &archive, with(&[&youtube]), true).unwrap();
    assert_eq!(core.online_stream(ids[0], false, Vec::new()).unwrap(), before);
}

// --- Ничего не подключено, отказ подключения ------------------------------------------

#[test]
fn an_empty_registry_is_quiet_nothing_is_found_nothing_plays_and_nothing_is_asked() {
    let dir = Scratch::new();
    let core = open(&dir);
    let archive = Archive::recorded();
    let youtube = Platform::youtube();
    connect(&core, &archive, with(&[&youtube]), true).unwrap();
    let tracks = core.online_album(YOUTUBE.to_owned(), VIDEO.to_owned()).unwrap().tracks;
    let ids = core.add_online_tracks(YOUTUBE.to_owned(), tracks).unwrap();
    let (archive_before, youtube_before) = (archive.asked(), youtube.asked());

    connect(&core, &archive, Vec::new(), false).unwrap();

    assert!(sections(&core).is_empty());
    let stream = core.online_stream(ids[0], false, Vec::new());
    assert!(is_unavailable(&stream), "{stream:?}");
    let album = core.online_album(YOUTUBE.to_owned(), VIDEO.to_owned()).unwrap();
    assert!(album.tracks.is_empty());
    assert_eq!(album.problem, Some(OnlineProblem::ProviderDown));
    assert_eq!((archive.asked(), youtube.asked()), (archive_before, youtube_before));
}

#[test]
fn a_refused_provider_keeps_the_previous_connection_also_without_the_archive() {
    let dir = Scratch::new();
    let core = open(&dir);
    let archive = Archive::recorded();
    let youtube = Platform::youtube();
    connect(&core, &archive, with(&[&youtube]), true).unwrap();

    let refused = connect(&core, &archive, with(&[&Platform::named("Not Allowed")]), false);

    assert!(refused.is_err());
    assert_eq!(names(&core), [ARCHIVE, YOUTUBE], "the previous connection stays");
}
