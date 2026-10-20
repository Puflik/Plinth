//! Ю3в (`docs/work/yu3v.md`): провайдер, который живёт на платформе. YouTube работает на
//! NewPipeExtractor в Kotlin, а ядро держит реестр, здоровье и каталог. Здесь — граница
//! со стороны Rust: внешний `trait` `ExternalProvider`, адаптер `ExternalAdapter`,
//! `connect_online` с провайдерами платформы и срок адреса потока.
//!
//! `ExternalProvider` и его ответы (`external_api.rs`), `ExternalAdapter::new`, второй параметр
//! `connect_online`, поле `StreamAddress::valid_for_ms` и `StreamRequest::with_valid_for` —
//! новый API (сигнатуры в `docs/work/yu3v.md`): пока их нет, тесты крейта не собираются.
//!
//! Платформа здесь — `Platform`: провайдер в памяти с теми же ручками, что у настоящего
//! (отказ, паника, свои ответы). Сеть Internet Archive — записанные ответы.

use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use plinth_providers::http::{HttpRequest, HttpTransport};
use plinth_providers::model::{ExternalId, Health, HealthPolicy, ResultKind, SearchQuery, SearchResult};
use plinth_providers::testing::{ContractCase, FixtureTransport};
use plinth_providers::{Provider, Registry};
use plinth_types::{Bitrate, CoreError, Format, Mbid, ProviderId, QualityTier, Timestamp};

use super::external_api::{
    ExternalAdapter, ExternalCapabilities, ExternalFailure, ExternalProvider, ExternalResolve, ExternalSearch,
    ExternalStream,
};
use super::online_api::{
    NetAnswer, NetHeader, NetRequest, NetTransport, OnlineCancel, OnlineKind, OnlineProblem, OnlineResult,
    OnlineTrackInfo, OnlineVariant, StreamAddress,
};
use crate::session::Core;
use crate::testing::Scratch;

/// Имя провайдера платформы в тестах.
const NAME: &str = "stream.test";

/// Песня: одиннадцать знаков, как у видео YouTube; есть и Opus, и AAC.
const SONG: &str = "dQw4w9WgXcQ";
/// Песня с `-` и `_` в идентификаторе, кириллицей в названии и только AAC.
const SONG_NO_OPUS: &str = "-a_B9xYz01q";
/// Альбом, который находит запрос «piano»: его треки играют в обоих форматах.
const ALBUM: &str = "OLAK5uy_piano_Album-1";
const ALBUM_TRACK_2: &str = "trkCCCCCCC3";
const ALBUM_TRACK_3: &str = "trkDDDDDDD4";
/// Альбом, которого запрос «piano» не находит: у его треков Opus нет, хотя варианты названы оба.
const QUIET: &str = "OLAK5uy_quiet_Album-2";
const QUIET_TRACK_1: &str = "trkBBBBBBB2";

const UA: &str = "com.google.visionos.youtube/1.02";
const VALID_FOR_MS: u64 = 7_200_000;

// --- Платформа в памяти --------------------------------------------------------

#[derive(Default)]
struct State {
    calls: Vec<String>,
    failure: Option<ExternalFailure>,
    panic_in: Option<&'static str>,
    search: Option<Vec<OnlineResult>>,
    resolve: Option<Vec<OnlineTrackInfo>>,
    stream: Option<StreamAddress>,
}

struct Platform {
    name: String,
    best: QualityTier,
    finds: Vec<OnlineKind>,
    state: Mutex<State>,
    ids_asked: AtomicUsize,
    capabilities_asked: AtomicUsize,
}

impl Platform {
    fn new(name: &str, best: QualityTier, finds: Vec<OnlineKind>) -> Arc<Self> {
        Arc::new(Self {
            name: name.to_owned(),
            best,
            finds,
            state: Mutex::new(State::default()),
            ids_asked: AtomicUsize::new(0),
            capabilities_asked: AtomicUsize::new(0),
        })
    }

    /// Песни и альбомы, как у YouTube Music; лучшее качество — `Standard` (Opus 160, AAC 128).
    fn sample() -> Arc<Self> {
        Self::named(NAME)
    }

    fn named(name: &str) -> Arc<Self> {
        Self::new(name, QualityTier::Standard, vec![OnlineKind::Track, OnlineKind::Album])
    }

    fn offline() -> Arc<Self> {
        let platform = Self::sample();
        platform.fail_with(Some(network("UnknownHostException")));
        platform
    }

    fn fail_with(&self, failure: Option<ExternalFailure>) {
        self.state.lock().unwrap().failure = failure;
    }

    fn panic_in(&self, method: Option<&'static str>) {
        self.state.lock().unwrap().panic_in = method;
    }

    fn answer_search(&self, results: Vec<OnlineResult>) {
        self.state.lock().unwrap().search = Some(results);
    }

    fn answer_resolve(&self, tracks: Vec<OnlineTrackInfo>) {
        self.state.lock().unwrap().resolve = Some(tracks);
    }

    fn answer_stream(&self, address: StreamAddress) {
        self.state.lock().unwrap().stream = Some(address);
    }

    fn calls(&self) -> Vec<String> {
        self.state.lock().unwrap().calls.clone()
    }

    /// Записывает вызов; паника по ручке случается вне замка, чтобы не отравить его.
    fn begin(&self, call: String, method: &'static str) -> Option<ExternalFailure> {
        let (failure, boom) = {
            let mut state = self.state.lock().unwrap();
            state.calls.push(call);
            (state.failure.clone(), state.panic_in == Some(method))
        };
        assert!(!boom, "SECRET: the platform blew up in {method}");
        failure
    }
}

impl ExternalProvider for Platform {
    fn id(&self) -> String {
        self.ids_asked.fetch_add(1, Ordering::SeqCst);
        self.name.clone()
    }

    fn capabilities(&self) -> ExternalCapabilities {
        self.capabilities_asked.fetch_add(1, Ordering::SeqCst);
        ExternalCapabilities { finds: self.finds.clone(), best_quality: self.best }
    }

    fn search(&self, query: String, limit: u32) -> ExternalSearch {
        if let Some(failure) = self.begin(format!("search:{query}:{limit}"), "search") {
            return ExternalSearch::Failed { failure };
        }
        if let Some(results) = self.state.lock().unwrap().search.clone() {
            return ExternalSearch::Found { results };
        }
        let needle = query.to_lowercase();
        let hit = |text: &str| text.to_lowercase().contains(&needle);
        let results = catalog_results()
            .into_iter()
            .filter(|found| hit(&found.title) || found.artist.as_deref().is_some_and(hit))
            .take(limit as usize)
            .collect();
        ExternalSearch::Found { results }
    }

    fn resolve(&self, item: String) -> ExternalResolve {
        if let Some(failure) = self.begin(format!("resolve:{item}"), "resolve") {
            return ExternalResolve::Failed { failure };
        }
        if let Some(tracks) = self.state.lock().unwrap().resolve.clone() {
            return ExternalResolve::Found { tracks };
        }
        match catalog_tracks(&item) {
            Some(tracks) => ExternalResolve::Found { tracks },
            None => ExternalResolve::Failed { failure: unavailable("id") },
        }
    }

    fn stream(&self, source: String) -> ExternalStream {
        if let Some(failure) = self.begin(format!("stream:{source}"), "stream") {
            return ExternalStream::Failed { failure };
        }
        if let Some(address) = self.state.lock().unwrap().stream.clone() {
            return ExternalStream::Found { address };
        }
        let playable = source.split_once('/').is_some_and(|(video, itag)| match itag {
            "251" => has_opus(video),
            "140" => true,
            _ => false,
        });
        if !playable {
            return ExternalStream::Failed { failure: unavailable("variant") };
        }
        ExternalStream::Found { address: address_of(&source) }
    }
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

fn address_of(source: &str) -> StreamAddress {
    StreamAddress {
        url: format!("https://rr1---sn-test.googlevideo.com/videoplayback?source={source}"),
        headers: vec![header("User-Agent", UA), header("Range", "bytes=0-")],
        valid_for_ms: Some(VALID_FOR_MS),
    }
}

fn header(name: &str, value: &str) -> NetHeader {
    NetHeader { name: name.to_owned(), value: value.to_owned() }
}

fn has_opus(video: &str) -> bool {
    video != QUIET_TRACK_1 && video != SONG_NO_OPUS
}

fn variant(video: &str, itag: u32) -> OnlineVariant {
    let (format, kbps) = if itag == 251 { (Format::Opus, 160) } else { (Format::Aac, 128) };
    OnlineVariant { external: format!("{video}/{itag}"), format, bitrate_kbps: Some(kbps) }
}

#[allow(clippy::too_many_arguments, reason = "тестовая запись трека целиком")]
fn track(
    video: &str,
    title: &str,
    artist: &str,
    album: Option<&str>,
    number: Option<u16>,
    seconds: u64,
    itags: &[u32],
) -> OnlineTrackInfo {
    OnlineTrackInfo {
        external: video.to_owned(),
        title: title.to_owned(),
        artist: Some(artist.to_owned()),
        album: album.map(str::to_owned),
        number,
        year: None,
        duration_ms: Some(seconds * 1000),
        mbid: None,
        variants: itags.iter().map(|itag| variant(video, *itag)).collect(),
    }
}

fn result(id: &str, kind: OnlineKind, title: &str, artist: &str) -> OnlineResult {
    OnlineResult {
        external: id.to_owned(),
        kind,
        title: title.to_owned(),
        artist: Some(artist.to_owned()),
        year: None,
        duration_ms: None,
    }
}

fn catalog_results() -> Vec<OnlineResult> {
    vec![
        OnlineResult { duration_ms: Some(338_000), ..result(SONG, OnlineKind::Track, "Piano Man", "Billy Joel") },
        OnlineResult {
            duration_ms: Some(240_000),
            ..result(SONG_NO_OPUS, OnlineKind::Track, "Фортепианный концерт", "Группа КИНО")
        },
        result(ALBUM, OnlineKind::Album, "Piano Album", "Various"),
        result(QUIET, OnlineKind::Album, "Quiet Album", "Various"),
    ]
}

fn catalog_tracks(item: &str) -> Option<Vec<OnlineTrackInfo>> {
    let both = [251, 140];
    let piano = Some("Piano Album");
    let quiet = Some("Quiet Album");
    Some(match item {
        SONG => vec![track(SONG, "Piano Man", "Billy Joel", None, None, 338, &both)],
        SONG_NO_OPUS => vec![track(SONG_NO_OPUS, "Фортепианный концерт", "Группа КИНО", None, None, 240, &[140])],
        ALBUM => vec![
            track(SONG, "Piano Man", "Billy Joel", piano, Some(1), 338, &both),
            track(ALBUM_TRACK_2, "Second Movement", "Various", piano, Some(2), 200, &both),
            track(ALBUM_TRACK_3, "Third Movement", "Various", piano, Some(3), 210, &both),
        ],
        QUIET => vec![
            track(QUIET_TRACK_1, "Hush", "Various", quiet, Some(1), 180, &both),
            track(SONG_NO_OPUS, "Фортепианный концерт", "Группа КИНО", quiet, Some(2), 240, &both),
        ],
        QUIET_TRACK_1 => vec![track(QUIET_TRACK_1, "Hush", "Various", None, None, 180, &both)],
        ALBUM_TRACK_2 => vec![track(ALBUM_TRACK_2, "Second Movement", "Various", None, None, 200, &both)],
        ALBUM_TRACK_3 => vec![track(ALBUM_TRACK_3, "Third Movement", "Various", None, None, 210, &both)],
        _ => return None,
    })
}

// --- Помощники ----------------------------------------------------------------

fn adapter(platform: &Arc<Platform>) -> ExternalAdapter {
    ExternalAdapter::new(platform.clone()).unwrap()
}

fn external(text: &str) -> ExternalId {
    ExternalId::new(text).unwrap()
}

fn query(text: &str, limit: u32) -> SearchQuery {
    SearchQuery::new(text, limit)
}

fn at(seconds: i64) -> Timestamp {
    Timestamp::from_millis(seconds * 1000)
}

fn id_of(name: &str) -> ProviderId {
    ProviderId::new(name).unwrap()
}

fn millis(count: u64) -> Duration {
    Duration::from_millis(count)
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

fn recorded() -> Arc<dyn NetTransport> {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../providers/tests/fixtures/ia");
    Arc::new(Recorded(FixtureTransport::recorded(&dir).unwrap()))
}

fn as_providers(platforms: &[&Arc<Platform>]) -> Vec<Arc<dyn ExternalProvider>> {
    platforms.iter().map(|platform| Arc::clone(*platform) as Arc<dyn ExternalProvider>).collect()
}

/// Ядро с Internet Archive на записанных ответах и платформенными провайдерами `platforms`.
fn connected(dir: &Scratch, platforms: &[&Arc<Platform>]) -> Arc<Core> {
    let core = Core::open(dir.path()).unwrap();
    core.connect_online(recorded(), as_providers(platforms)).unwrap();
    core
}

fn search_sections(core: &Core) -> Vec<super::online_api::OnlineSearch> {
    core.online_search("Piano".to_owned(), 10, OnlineCancel::new()).unwrap()
}

fn problem_of(core: &Core, provider: &str) -> Option<OnlineProblem> {
    search_sections(core).into_iter().find(|section| section.provider == provider).unwrap().problem
}

// --- Общий контракт провайдера (E1) на платформенном провайдере ------------------

plinth_providers::provider_contract!(platform_provider,
    working: adapter(&Platform::sample()),
    offline: adapter(&Platform::offline()),
    case: ContractCase::new("piano", "zzz", "no-such-item"),
);

// --- Адаптер: поиск ---------------------------------------------------------------

#[test]
fn search_results_arrive_with_every_field_and_ids_as_they_are() {
    let platform = Platform::sample();
    platform.answer_search(vec![
        OnlineResult {
            external: SONG.to_owned(),
            kind: OnlineKind::Track,
            title: "  Piano Man \n".to_owned(),
            artist: Some("Billy Joel".to_owned()),
            year: Some(1973),
            duration_ms: Some(338_000),
        },
        OnlineResult {
            external: SONG_NO_OPUS.to_owned(),
            kind: OnlineKind::Album,
            title: "Фортепианный концерт".to_owned(),
            artist: Some("  ".to_owned()),
            year: Some(0),
            duration_ms: None,
        },
    ]);

    let found = adapter(&platform).search(&query("piano", 10)).unwrap();

    assert_eq!(
        found,
        vec![
            SearchResult {
                external: external(SONG),
                kind: ResultKind::Track,
                title: "Piano Man".to_owned(),
                artist: Some("Billy Joel".to_owned()),
                album: None,
                year: Some(1973),
                duration: Some(millis(338_000)),
            },
            SearchResult {
                external: external(SONG_NO_OPUS),
                kind: ResultKind::Album,
                title: "Фортепианный концерт".to_owned(),
                artist: None,
                album: None,
                year: None,
                duration: None,
            },
        ]
    );
}

#[test]
fn a_blank_query_never_reaches_the_platform() {
    let platform = Platform::sample();

    let found = adapter(&platform).search(&query("  \t ", 10)).unwrap();

    assert!(found.is_empty());
    assert!(platform.calls().is_empty(), "{:?}", platform.calls());
}

#[test]
fn the_platform_gets_the_trimmed_text_and_the_limit_the_query_allows() {
    let platform = Platform::sample();
    let adapter = adapter(&platform);

    adapter.search(&query("  piano \n", 7)).unwrap();
    adapter.search(&query("piano", 10_000)).unwrap();
    adapter.search(&query("piano", 0)).unwrap();

    assert_eq!(platform.calls(), ["search:piano:7", "search:piano:100", "search:piano:1"]);
}

#[test]
fn more_results_than_the_limit_are_cut_from_the_end() {
    let platform = Platform::sample();
    platform.answer_search(vec![
        result(SONG, OnlineKind::Track, "One", "A"),
        result(SONG_NO_OPUS, OnlineKind::Track, "Two", "A"),
        result(ALBUM, OnlineKind::Album, "Three", "A"),
    ]);

    let found = adapter(&platform).search(&query("x", 2)).unwrap();

    assert_eq!(found.iter().map(|f| f.external.as_str()).collect::<Vec<_>>(), [SONG, SONG_NO_OPUS]);
}

// --- Адаптер: треки ---------------------------------------------------------------

#[test]
fn a_track_arrives_with_its_variants_in_the_platforms_order() {
    let platform = Platform::sample();
    let mbid = "b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d";
    platform.answer_resolve(vec![OnlineTrackInfo {
        external: SONG.to_owned(),
        title: " Piano Man ".to_owned(),
        artist: Some("Billy Joel".to_owned()),
        album: Some("Piano Album".to_owned()),
        number: Some(2),
        year: Some(1973),
        duration_ms: Some(338_000),
        mbid: Some(mbid.to_owned()),
        variants: vec![variant(SONG, 251), variant(SONG, 140)],
    }]);

    let tracks = adapter(&platform).resolve(&external(ALBUM)).unwrap();

    assert_eq!(tracks.len(), 1);
    let track = &tracks[0];
    assert_eq!(track.external, external(SONG));
    assert_eq!(track.title, "Piano Man");
    assert_eq!(track.artist.as_deref(), Some("Billy Joel"));
    assert_eq!(track.album.as_deref(), Some("Piano Album"));
    assert_eq!((track.disc, track.number, track.year), (None, Some(2), Some(1973)));
    assert_eq!(track.duration, Some(millis(338_000)));
    assert_eq!(track.mbid, mbid.parse::<Mbid>().ok());
    let sources: Vec<_> = track.sources.iter().map(|s| (s.external.as_str(), s.format, s.bitrate)).collect();
    assert_eq!(
        sources,
        [("dQw4w9WgXcQ/251", Format::Opus, Some(Bitrate::kbps(160))), ("dQw4w9WgXcQ/140", Format::Aac, Some(Bitrate::kbps(128)))]
    );
}

#[test]
fn empty_captions_and_zero_numbers_are_nothing_and_a_broken_mbid_is_dropped() {
    let platform = Platform::sample();
    let mut info = track(SONG, "Piano Man", "  ", Some(" "), Some(0), 1, &[251]);
    info.year = Some(0);
    info.mbid = Some("not-a-uuid".to_owned());
    info.variants[0].bitrate_kbps = Some(0);
    platform.answer_resolve(vec![info]);

    let tracks = adapter(&platform).resolve(&external(SONG)).unwrap();

    let track = &tracks[0];
    assert_eq!((track.artist.clone(), track.album.clone()), (None, None));
    assert_eq!((track.number, track.year, track.mbid), (None, None, None));
    assert_eq!(track.sources[0].bitrate, None);
}

#[test]
fn the_platform_gets_ids_exactly_as_the_core_holds_them() {
    let platform = Platform::sample();
    let adapter = adapter(&platform);

    adapter.resolve(&external(SONG_NO_OPUS)).unwrap();
    adapter.stream_url(&external("-a_B9xYz01q/140")).unwrap();

    assert_eq!(platform.calls(), ["resolve:-a_B9xYz01q", "stream:-a_B9xYz01q/140"]);
}

// --- Адаптер: поток ---------------------------------------------------------------

#[test]
fn a_stream_keeps_the_address_the_headers_in_order_and_the_validity() {
    let platform = Platform::sample();

    let stream = adapter(&platform).stream_url(&external("dQw4w9WgXcQ/251")).unwrap();

    assert_eq!(stream.url(), "https://rr1---sn-test.googlevideo.com/videoplayback?source=dQw4w9WgXcQ/251");
    assert_eq!(
        stream.headers(),
        [("User-Agent".to_owned(), UA.to_owned()), ("Range".to_owned(), "bytes=0-".to_owned())]
    );
    assert_eq!(stream.valid_for(), Some(millis(VALID_FOR_MS)));
}

#[test]
fn a_stream_without_a_validity_does_not_age() {
    let platform = Platform::sample();
    platform.answer_stream(StreamAddress {
        url: "https://archive.org/download/x/01.mp3".to_owned(),
        headers: Vec::new(),
        valid_for_ms: None,
    });

    let stream = adapter(&platform).stream_url(&external("x/1")).unwrap();

    assert_eq!(stream.valid_for(), None);
    assert!(stream.headers().is_empty());
}

// --- Адаптер: чужие данные проверяются --------------------------------------------

fn is_parse(error: &CoreError) -> bool {
    matches!(error, CoreError::Parse { .. })
}

#[test]
fn broken_search_results_are_refused_without_quoting_them() {
    let broken = [
        result("", OnlineKind::Track, "SECRET title", "A"),
        result(&"x".repeat(ExternalId::MAX_LEN + 1), OnlineKind::Track, "SECRET title", "A"),
        result("SECRET\nid", OnlineKind::Track, "SECRET title", "A"),
        result(SONG, OnlineKind::Track, "  \t ", "SECRET artist"),
    ];
    for bad in broken {
        let platform = Platform::sample();
        platform.answer_search(vec![result(ALBUM, OnlineKind::Album, "Fine", "A"), bad.clone()]);

        let error = adapter(&platform).search(&query("x", 10)).unwrap_err();

        assert!(is_parse(&error), "{bad:?}: {error:?}");
        assert!(!error.to_string().contains("SECRET"), "{error}");
    }
}

#[test]
fn broken_tracks_are_refused_without_quoting_them() {
    let mut no_title = track(SONG, "  ", "SECRET artist", None, None, 1, &[251]);
    no_title.title = "  ".to_owned();
    let mut no_variants = track(SONG, "SECRET title", "A", None, None, 1, &[]);
    no_variants.variants.clear();
    let mut empty_variant = track(SONG, "SECRET title", "A", None, None, 1, &[251]);
    empty_variant.variants[0].external = String::new();
    let mut control_variant = track(SONG, "SECRET title", "A", None, None, 1, &[251]);
    control_variant.variants[0].external = "SECRET\u{0}/251".to_owned();
    let mut empty_id = track(SONG, "SECRET title", "A", None, None, 1, &[251]);
    empty_id.external = String::new();
    for bad in [no_title, no_variants, empty_variant, control_variant, empty_id] {
        let platform = Platform::sample();
        platform.answer_resolve(vec![bad.clone()]);

        let error = adapter(&platform).resolve(&external(SONG)).unwrap_err();

        assert!(is_parse(&error), "{bad:?}: {error:?}");
        assert!(!error.to_string().contains("SECRET"), "{error}");
    }
}

#[test]
fn broken_streams_are_refused_without_quoting_them() {
    let address = |url: &str, headers: Vec<NetHeader>| StreamAddress { url: url.to_owned(), headers, valid_for_ms: None };
    let broken = [
        address("ftp://secret.example/SECRET", Vec::new()),
        address("file:///SECRET", Vec::new()),
        address("https://rr1.test/a b SECRET", Vec::new()),
        address("", Vec::new()),
        address("https://rr1.test/a", vec![header("X A", "1")]),
        address("https://rr1.test/a", vec![header("X-A", "1\r\nCookie: SECRET")]),
    ];
    for bad in broken {
        let platform = Platform::sample();
        platform.answer_stream(bad.clone());

        let error = adapter(&platform).stream_url(&external("dQw4w9WgXcQ/251")).unwrap_err();

        assert!(is_parse(&error), "{bad:?}: {error:?}");
        assert!(!error.to_string().contains("SECRET"), "{error}");
    }
}

// --- Адаптер: беды и паника платформы ----------------------------------------------

#[test]
fn every_failure_keeps_its_kind_and_its_text_in_all_three_calls() {
    let cases = [
        (network("http 503"), CoreError::network("http 503")),
        (network("http 429, retry after 30 s"), CoreError::network("http 429, retry after 30 s")),
        (network("timed out"), CoreError::network("timed out")),
        (network("UnknownHostException"), CoreError::network("UnknownHostException")),
        (parse("no audio streams"), CoreError::parse("no audio streams")),
        (unavailable("variant"), CoreError::unavailable("variant")),
        (internal("IllegalStateException"), CoreError::internal("IllegalStateException")),
    ];
    for (failure, expected) in cases {
        let platform = Platform::sample();
        let adapter = adapter(&platform);
        platform.fail_with(Some(failure));

        assert_eq!(adapter.search(&query("piano", 10)), Err(expected.clone()));
        assert_eq!(adapter.resolve(&external(SONG)), Err(expected.clone()));
        assert_eq!(adapter.stream_url(&external("dQw4w9WgXcQ/251")).map(|_| ()), Err(expected));
    }
}

#[test]
fn a_platform_that_panics_is_an_internal_error_in_every_call_and_works_again_afterwards() {
    for method in ["search", "resolve", "stream"] {
        let platform = Platform::sample();
        let adapter = adapter(&platform);
        platform.panic_in(Some(method));

        let outcomes = [
            adapter.search(&query("piano", 10)).map(|_| ()),
            adapter.resolve(&external(SONG)).map(|_| ()),
            adapter.stream_url(&external("dQw4w9WgXcQ/251")).map(|_| ()),
        ];

        let index = ["search", "resolve", "stream"].iter().position(|name| *name == method).unwrap();
        for (position, outcome) in outcomes.iter().enumerate() {
            if position == index {
                assert!(matches!(outcome, Err(CoreError::Internal { .. })), "{method}: {outcome:?}");
            } else {
                assert_eq!(outcome, &Ok(()), "{method} at {position}");
            }
        }
        platform.panic_in(None);
        assert!(adapter.search(&query("piano", 10)).is_ok(), "{method}: the platform is asked again");
    }
}

#[test]
fn the_adapter_asks_for_the_name_and_the_capabilities_once() {
    let platform = Platform::sample();
    let adapter = adapter(&platform);

    adapter.search(&query("piano", 10)).unwrap();
    adapter.resolve(&external(SONG)).unwrap();
    adapter.stream_url(&external("dQw4w9WgXcQ/140")).unwrap();
    let _ = (adapter.id().clone(), adapter.capabilities());

    assert_eq!(platform.ids_asked.load(Ordering::SeqCst), 1);
    assert_eq!(platform.capabilities_asked.load(Ordering::SeqCst), 1);
}

#[test]
fn the_name_and_the_capabilities_are_what_the_platform_declared() {
    let platform = Platform::new("youtube", QualityTier::HighLossy, vec![OnlineKind::Album]);

    let adapter = adapter(&platform);

    assert_eq!(adapter.id(), &id_of("youtube"));
    let capabilities = adapter.capabilities();
    assert_eq!(capabilities.finds, [ResultKind::Album]);
    assert_eq!(capabilities.best_quality, QualityTier::HighLossy);
    let both = super::external_api::ExternalAdapter::new(Platform::sample()).unwrap().capabilities();
    assert_eq!(both.finds, [ResultKind::Track, ResultKind::Album]);
}

#[test]
fn a_bad_name_or_nothing_to_find_or_a_panic_keeps_the_provider_out() {
    for name in ["", "YouTube", "you tube", "a/b", &"x".repeat(65)] {
        let result = ExternalAdapter::new(Platform::named(name));
        assert!(matches!(result, Err(CoreError::Parse { .. })), "{name:?}");
    }
    let nothing = Platform::new(NAME, QualityTier::Standard, Vec::new());
    assert!(matches!(ExternalAdapter::new(nothing), Err(CoreError::Parse { .. })));
}

// --- Здоровье в реестре ---------------------------------------------------------------

const POLICY: HealthPolicy = HealthPolicy { down_after: 2, down_for: Duration::from_secs(60) };

fn registry_of(platform: &Arc<Platform>) -> Registry {
    Registry::new(vec![Arc::new(adapter(platform))], POLICY).unwrap()
}

#[test]
fn a_platform_that_refuses_is_degraded_then_benched_and_asked_again_when_the_bench_ends() {
    let platform = Platform::sample();
    let registry = registry_of(&platform);
    let name = id_of(NAME);
    platform.fail_with(Some(network("http 503")));

    registry.search(&query("piano", 10), at(0));
    assert_eq!(registry.health(&name), Some(Health::Degraded { failures: 1 }));
    registry.search(&query("piano", 10), at(1));
    assert_eq!(registry.health(&name), Some(Health::Down { failures: 2, until: at(61) }));
    let asked = platform.calls().len();
    let skipped = registry.search(&query("piano", 10), at(30));

    assert!(matches!(skipped[0].outcome, Err(CoreError::Unavailable { .. })));
    assert_eq!(platform.calls().len(), asked, "a benched provider is not asked");
    platform.fail_with(None);
    let after = registry.search(&query("piano", 10), at(61));
    assert_eq!(after[0].outcome.as_ref().map(Vec::len), Ok(2));
    assert_eq!(registry.health(&name), Some(Health::Ok));
}

#[test]
fn the_text_of_a_refusal_reaches_the_health_rules_a_server_deadline_is_kept() {
    let platform = Platform::sample();
    let registry = registry_of(&platform);
    platform.fail_with(Some(network("http 429, retry after 30 s")));

    registry.search(&query("piano", 10), at(0));

    assert_eq!(registry.health(&id_of(NAME)), Some(Health::Down { failures: 1, until: at(30) }));
}

#[test]
fn a_missing_item_and_a_refused_request_are_not_the_providers_fault() {
    let platform = Platform::sample();
    let registry = registry_of(&platform);
    let name = id_of(NAME);

    platform.fail_with(Some(unavailable("variant")));
    for second in 0..3 {
        assert!(registry.resolve(&name, &external(SONG), at(second)).is_err());
    }
    platform.fail_with(Some(network("http 403")));
    for second in 3..6 {
        assert!(registry.resolve(&name, &external(SONG), at(second)).is_err());
    }

    assert_eq!(registry.health(&name), Some(Health::Ok));
}

#[test]
fn a_broken_answer_counts_against_the_provider_and_a_good_one_clears_it() {
    let platform = Platform::sample();
    let registry = registry_of(&platform);
    let name = id_of(NAME);
    platform.fail_with(Some(parse("no audio streams")));

    registry.resolve(&name, &external(SONG), at(0)).unwrap_err();
    assert_eq!(registry.health(&name), Some(Health::Degraded { failures: 1 }));
    platform.fail_with(None);
    registry.resolve(&name, &external(SONG), at(1)).unwrap();

    assert_eq!(registry.health(&name), Some(Health::Ok));
}

#[test]
fn a_stream_that_the_platform_cannot_give_goes_to_the_next_source() {
    let platform = Platform::sample();
    let registry = registry_of(&platform);
    let candidates = [(id_of(NAME), external("trkBBBBBBB2/251")), (id_of(NAME), external("trkBBBBBBB2/140"))];

    let (chosen, stream) = registry.stream(&candidates, at(0)).unwrap();

    assert_eq!(chosen, 1);
    assert!(stream.url().ends_with("source=trkBBBBBBB2/140"), "{}", stream.url());
    assert_eq!(registry.health(&id_of(NAME)), Some(Health::Ok), "a missing format is not a failure");
}

// --- connect_online: Internet Archive и платформа вместе ---------------------------------

#[test]
fn the_platform_gets_its_own_section_after_archive_org() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    let core = connected(&dir, &[&platform]);

    let sections = search_sections(&core);

    assert_eq!(sections.iter().map(|s| s.provider.as_str()).collect::<Vec<_>>(), ["archive.org", NAME]);
    assert_eq!(sections[0].results.len(), 5);
    assert_eq!(sections[1].problem, None);
    let found: Vec<_> = sections[1].results.iter().map(|r| (r.external.as_str(), r.kind, r.title.as_str())).collect();
    assert_eq!(found, [(SONG, OnlineKind::Track, "Piano Man"), (ALBUM, OnlineKind::Album, "Piano Album")]);
    assert_eq!(sections[1].results[0].artist.as_deref(), Some("Billy Joel"));
    assert_eq!(sections[1].results[0].duration_ms, Some(338_000));
}

#[test]
fn a_platform_that_claims_the_best_quality_still_comes_after_archive_org() {
    let dir = Scratch::new();
    let greedy = Platform::new("greedy.test", QualityTier::Lossless, vec![OnlineKind::Album]);
    let core = connected(&dir, &[&greedy]);

    let sections = search_sections(&core);

    assert_eq!(sections.iter().map(|s| s.provider.as_str()).collect::<Vec<_>>(), ["archive.org", "greedy.test"]);
}

#[test]
fn without_platform_providers_it_is_archive_org_alone() {
    let dir = Scratch::new();
    let core = Core::open(dir.path()).unwrap();
    core.connect_online(recorded(), Vec::new()).unwrap();

    let sections = search_sections(&core);

    assert_eq!(sections.iter().map(|s| s.provider.as_str()).collect::<Vec<_>>(), ["archive.org"]);
    assert_eq!(sections[0].results.len(), 5);
}

#[test]
fn what_went_wrong_on_the_platform_is_a_problem_of_its_own_section_only() {
    let cases = [
        (network("UnknownHostException"), OnlineProblem::NoNetwork),
        (network("timed out"), OnlineProblem::ProviderDown),
        (network("http 503"), OnlineProblem::ProviderDown),
        (network("http 429, retry after 30 s"), OnlineProblem::RateLimited),
        (network("http 429"), OnlineProblem::RateLimited),
        (parse("no audio streams"), OnlineProblem::Broken),
        (unavailable("variant"), OnlineProblem::ProviderDown),
        (internal("IllegalStateException"), OnlineProblem::Broken),
    ];
    for (failure, expected) in cases {
        let dir = Scratch::new();
        let platform = Platform::sample();
        platform.fail_with(Some(failure.clone()));
        let core = connected(&dir, &[&platform]);

        let sections = search_sections(&core);

        assert_eq!(sections[1].problem, Some(expected), "{failure:?}");
        assert!(sections[1].results.is_empty());
        assert_eq!(sections[0].problem, None, "{failure:?}: archive.org is not to blame");
        assert_eq!(sections[0].results.len(), 5);
    }
}

#[test]
fn a_platform_that_panics_costs_its_own_section_not_the_search() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    platform.panic_in(Some("search"));
    let core = connected(&dir, &[&platform]);

    let sections = search_sections(&core);

    assert_eq!(sections[0].results.len(), 5);
    assert_eq!(sections[1].problem, Some(OnlineProblem::Broken));
    platform.panic_in(None);
    assert_eq!(search_sections(&core)[1].problem, None, "the next search asks it again");
}

#[test]
fn an_album_of_the_platform_comes_with_numbers_and_both_variants() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    let core = connected(&dir, &[&platform]);

    let reply = core.online_album(NAME.to_owned(), ALBUM.to_owned()).unwrap();

    assert_eq!(reply.problem, None);
    let tracks: Vec<_> = reply.tracks.iter().map(|t| (t.external.as_str(), t.number, t.album.as_deref())).collect();
    assert_eq!(
        tracks,
        [
            (SONG, Some(1), Some("Piano Album")),
            (ALBUM_TRACK_2, Some(2), Some("Piano Album")),
            (ALBUM_TRACK_3, Some(3), Some("Piano Album")),
        ]
    );
    let variants: Vec<_> = reply.tracks[1].variants.iter().map(|v| (v.external.as_str(), v.format)).collect();
    assert_eq!(variants, [("trkCCCCCCC3/251", Format::Opus), ("trkCCCCCCC3/140", Format::Aac)]);
}

#[test]
fn an_album_that_the_platform_lost_is_a_problem_of_the_screen_and_a_panic_is_an_error_of_the_call() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    let core = connected(&dir, &[&platform]);

    let gone = core.online_album(NAME.to_owned(), "no-such-item".to_owned()).unwrap();
    platform.panic_in(Some("resolve"));
    let blown = core.online_album(NAME.to_owned(), ALBUM.to_owned());

    assert_eq!((gone.tracks.len(), gone.problem), (0, Some(OnlineProblem::ProviderDown)));
    assert!(matches!(blown, Err(CoreError::Internal { .. })), "{blown:?}");
}

#[test]
fn archive_org_still_works_next_to_a_platform() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    let core = connected(&dir, &[&platform]);

    let reply = core.online_album("archive.org".to_owned(), "MIXG031".to_owned()).unwrap();
    let ids = core.add_online_tracks("archive.org".to_owned(), reply.tracks).unwrap();
    let stream = core.online_stream(ids[0], false, Vec::new()).unwrap();

    assert!(stream.url.starts_with("https://archive.org/download/MIXG031/"), "{}", stream.url);
    assert!(stream.headers.is_empty());
    assert_eq!(stream.valid_for_ms, None, "an address of archive.org does not age");
    assert!(platform.calls().is_empty(), "{:?}", platform.calls());
}

// --- Игра: адрес, заголовки, срок, формат -------------------------------------------

fn added(core: &Core, item: &str) -> Vec<plinth_types::TrackId> {
    let tracks = core.online_album(NAME.to_owned(), item.to_owned()).unwrap().tracks;
    core.add_online_tracks(NAME.to_owned(), tracks).unwrap()
}

#[test]
fn a_track_of_the_platform_plays_its_best_format_with_the_headers_and_the_validity_of_the_platform() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    let core = connected(&dir, &[&platform]);
    let ids = added(&core, SONG);

    let wifi = core.online_stream(ids[0], false, Vec::new()).unwrap();
    let cellular = core.online_stream(ids[0], true, Vec::new()).unwrap();

    for stream in [&wifi, &cellular] {
        assert_eq!(stream.url, "https://rr1---sn-test.googlevideo.com/videoplayback?source=dQw4w9WgXcQ/251");
        assert_eq!(stream.headers, [header("User-Agent", UA), header("Range", "bytes=0-")]);
        assert_eq!(stream.valid_for_ms, Some(VALID_FOR_MS));
    }
    assert_eq!(platform.calls().iter().filter(|call| call.starts_with("stream:")).count(), 2);
}

#[test]
fn a_format_the_device_cannot_decode_is_asked_last() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    let core = connected(&dir, &[&platform]);
    let ids = added(&core, SONG);

    let stream = core.online_stream(ids[0], false, vec![Format::Opus]).unwrap();

    assert!(stream.url.ends_with("source=dQw4w9WgXcQ/140"), "{}", stream.url);
    assert_eq!(platform.calls().iter().filter(|call| call.starts_with("stream:")).collect::<Vec<_>>().len(), 1);
}

#[test]
fn a_track_whose_video_has_no_opus_falls_through_to_aac() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    let core = connected(&dir, &[&platform]);
    let ids = added(&core, QUIET);

    let stream = core.online_stream(ids[0], false, Vec::new()).unwrap();

    assert!(stream.url.ends_with("source=trkBBBBBBB2/140"), "{}", stream.url);
    let asked: Vec<_> = platform.calls().into_iter().filter(|call| call.starts_with("stream:")).collect();
    assert_eq!(asked, ["stream:trkBBBBBBB2/251", "stream:trkBBBBBBB2/140"]);
}

#[test]
fn every_failure_to_give_a_stream_reaches_the_player_as_its_kind() {
    let cases = [
        (network("UnknownHostException"), true),
        (network("http 503"), true),
        (unavailable("variant"), false),
        (parse("no audio streams"), false),
    ];
    for (failure, is_network) in cases {
        let dir = Scratch::new();
        let platform = Platform::sample();
        let core = connected(&dir, &[&platform]);
        let ids = added(&core, SONG);
        platform.fail_with(Some(failure.clone()));

        let outcome = core.online_stream(ids[0], false, Vec::new());

        match (&failure, outcome) {
            (ExternalFailure::Network { .. }, Err(CoreError::Network { .. })) => assert!(is_network),
            (ExternalFailure::Unavailable { .. }, Err(CoreError::Unavailable { .. })) => assert!(!is_network),
            (ExternalFailure::Parse { .. }, Err(CoreError::Parse { .. })) => assert!(!is_network),
            (failure, other) => panic!("{failure:?} came as {other:?}"),
        }
    }
}

// --- Кто этот трек: идентичность данных пользователя ----------------------------------

#[test]
fn the_same_video_is_the_same_track_whatever_way_it_came_and_gains_the_variants_it_lacked() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    let core = connected(&dir, &[&platform]);
    let single = added(&core, SONG_NO_OPUS);
    let album = core.online_album(NAME.to_owned(), QUIET.to_owned()).unwrap().tracks;

    let known = core.known_online_tracks(NAME.to_owned(), album.clone()).unwrap();
    let again = core.add_online_tracks(NAME.to_owned(), album).unwrap();

    assert_eq!(known, [None, Some(single[0])], "the video of the song is found by its AAC variant");
    assert_eq!(again[1], single[0]);
    let stream = core.online_stream(single[0], false, Vec::new()).unwrap();
    assert!(stream.url.ends_with("source=-a_B9xYz01q/140"), "{}", stream.url);
    let asked: Vec<_> = platform.calls().into_iter().filter(|call| call.starts_with("stream:")).collect();
    assert_eq!(asked, ["stream:-a_B9xYz01q/251", "stream:-a_B9xYz01q/140"], "it gained the Opus variant of the album");
}

#[test]
fn an_added_track_is_asked_by_the_exact_variant_ids_the_platform_gave() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    let core = connected(&dir, &[&platform]);
    let first = added(&core, ALBUM);
    let second = added(&core, ALBUM);

    core.online_stream(first[1], false, Vec::new()).unwrap();

    assert_eq!(first, second, "adding the same album twice finds the same tracks");
    assert_eq!(first.len(), 3);
    let asked: Vec<_> = platform.calls().into_iter().filter(|call| call.starts_with("stream:")).collect();
    assert_eq!(asked, ["stream:trkCCCCCCC3/251"]);
}

#[test]
fn a_liked_track_stays_when_its_provider_is_gone_and_does_not_play() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    let core = connected(&dir, &[&platform]);
    let ids = added(&core, SONG);
    core.like(ids[0]).unwrap();

    core.connect_online(recorded(), Vec::new()).unwrap();

    let liked: Vec<String> = core.liked_tracks().unwrap().into_iter().map(|row| row.title).collect();
    assert_eq!(liked, ["Piano Man"]);
    assert!(matches!(core.online_stream(ids[0], false, Vec::new()), Err(CoreError::Unavailable { .. })));
    core.connect_online(recorded(), as_providers(&[&platform])).unwrap();
    assert!(core.online_stream(ids[0], false, Vec::new()).is_ok(), "it plays again once the provider is back");
}

// --- Подключение: замена, сброс, отказ ------------------------------------------------

#[test]
fn connecting_again_replaces_the_providers_and_forgets_their_health() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    platform.fail_with(Some(network("http 503")));
    let core = connected(&dir, &[&platform]);
    for _ in 0..3 {
        assert_eq!(problem_of(&core, NAME), Some(OnlineProblem::ProviderDown));
    }
    let asked = platform.calls().len();
    assert_eq!(problem_of(&core, NAME), Some(OnlineProblem::ProviderDown));
    assert_eq!(platform.calls().len(), asked, "after three refusals in a row it is benched");

    platform.fail_with(None);
    core.connect_online(recorded(), as_providers(&[&platform])).unwrap();

    assert_eq!(problem_of(&core, NAME), None);
    assert!(platform.calls().len() > asked, "a new connection asks it again");
    let other = Platform::named("other.test");
    core.connect_online(recorded(), as_providers(&[&other])).unwrap();
    assert_eq!(
        search_sections(&core).iter().map(|s| s.provider.as_str()).collect::<Vec<_>>(),
        ["archive.org", "other.test"]
    );
}

#[test]
fn switched_off_there_are_no_sections_and_no_streams() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    let core = connected(&dir, &[&platform]);
    let ids = added(&core, SONG);

    core.disconnect_online().unwrap();

    assert!(search_sections(&core).is_empty());
    assert!(matches!(core.online_stream(ids[0], false, Vec::new()), Err(CoreError::Unavailable { .. })));
    assert!(matches!(core.online_album(NAME.to_owned(), SONG.to_owned()), Err(CoreError::Unavailable { .. })));
}

#[test]
fn a_provider_that_cannot_be_registered_fails_the_connection_and_changes_nothing() {
    let dir = Scratch::new();
    let platform = Platform::sample();
    let core = connected(&dir, &[&platform]);
    let clash = Platform::named("archive.org");
    let twin = Platform::sample();
    let bad_name = Platform::named("YouTube");
    let nothing = Platform::new("nothing.test", QualityTier::Standard, Vec::new());

    let taken = core.connect_online(recorded(), as_providers(&[&clash]));
    let twice = core.connect_online(recorded(), as_providers(&[&platform, &twin]));
    let invalid = core.connect_online(recorded(), as_providers(&[&bad_name]));
    let blind = core.connect_online(recorded(), as_providers(&[&nothing]));

    assert!(matches!(taken, Err(CoreError::Internal { .. })), "{taken:?}");
    assert!(matches!(twice, Err(CoreError::Internal { .. })), "{twice:?}");
    assert!(matches!(invalid, Err(CoreError::Parse { .. })), "{invalid:?}");
    assert!(matches!(blind, Err(CoreError::Parse { .. })), "{blind:?}");
    let sections = search_sections(&core);
    assert_eq!(sections.iter().map(|s| s.provider.as_str()).collect::<Vec<_>>(), ["archive.org", NAME]);
    assert_eq!(sections[1].results.len(), 2, "the earlier connection still answers");
}

#[test]
fn a_connection_with_a_bad_provider_does_not_switch_online_on() {
    let dir = Scratch::new();
    let core = Core::open(dir.path()).unwrap();

    let invalid = core.connect_online(recorded(), as_providers(&[&Platform::named("YouTube")]));

    assert!(invalid.is_err());
    assert!(search_sections(&core).is_empty(), "nothing was switched on");
}

/// Платформа, которая падает, когда её спрашивают об имени или о возможностях.
struct Hopeless {
    no_name: bool,
}

impl ExternalProvider for Hopeless {
    fn id(&self) -> String {
        assert!(!self.no_name, "SECRET: no name");
        NAME.to_owned()
    }

    fn capabilities(&self) -> ExternalCapabilities {
        assert!(self.no_name, "SECRET: no capabilities");
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
}

#[test]
fn a_platform_that_panics_when_asked_who_it_is_stays_out_and_fails_only_the_connection() {
    for no_name in [true, false] {
        let direct = ExternalAdapter::new(Arc::new(Hopeless { no_name }));
        assert!(matches!(direct, Err(CoreError::Internal { .. })), "no_name={no_name}");

        let dir = Scratch::new();
        let core = Core::open(dir.path()).unwrap();
        let providers: Vec<Arc<dyn ExternalProvider>> = vec![Arc::new(Hopeless { no_name })];

        let outcome = core.connect_online(recorded(), providers);

        assert!(matches!(outcome, Err(CoreError::Internal { .. })), "no_name={no_name}: {outcome:?}");
        assert!(search_sections(&core).is_empty(), "nothing was switched on");
    }
}
