//! Онлайн-источники для Kotlin (E3a, docs/adr/0008-provider-trait.md).
//!
//! - сеть делает Kotlin: [`NetTransport`] — один GET по просьбе ядра;
//!   политика (User-Agent, таймаут, повторы) — в ядре;
//! - `connect_online` включает источники (переключатель «Онлайн-источники»),
//!   `disconnect_online` — выключает; выключены — поиск пуст, потока нет;
//! - поиск по провайдерам — секциями, по секции на провайдера (ответ
//!   автора); альбом — `resolve`;
//! - каталог при действии (ответ автора E1): `add_online_tracks` заводит
//!   треки альбома, дальше они живут как локальные;
//! - адрес потока — в момент загрузки (ответ автора E3): вариант по типу
//!   сети (`playback_order`), первый ответивший провайдер (`Registry`).

use std::sync::Arc;
use std::time::Duration;

use plinth_library::model::AudioSpec;
use plinth_library::model::{OnlineSource, OnlineTrack};
use plinth_providers::http::{HttpRequest, HttpResponse, HttpTransport};
use plinth_providers::model::{
    ExternalId, HealthPolicy, Network, RemoteTrack, ResultKind, SearchQuery, SourceOption, playback_order,
};
use plinth_providers::{InternetArchive, Provider, ProviderSearch, Registry};
use plinth_types::{Bitrate, CoreError, Format, Mbid, ProviderId, Timestamp, TrackId};

use crate::panic;
use crate::session::Core;

/// Сеть платформы: один GET без повторов. Ответ с любым статусом —
/// `Response`; ответа нет (нет сети, таймаут, оборвалось) — `NoAnswer`
/// с причиной без адреса; тело длиннее `max_body_bytes` — `TooLarge`.
#[uniffi::export(with_foreign)]
pub trait NetTransport: Send + Sync {
    fn get(&self, request: NetRequest) -> NetAnswer;
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NetRequest {
    pub url: String,
    pub headers: Vec<NetHeader>,
    pub timeout_ms: u64,
    pub max_body_bytes: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NetHeader {
    pub name: String,
    pub value: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum NetAnswer {
    Response { status: u16, body: Vec<u8> },
    NoAnswer { reason: String },
    TooLarge,
}

#[uniffi::remote(Enum)]
pub enum Format {
    Flac,
    Alac,
    Wav,
    Aiff,
    Mp3,
    Aac,
    Vorbis,
    Opus,
    Other,
}

/// Что нашлось: трек или собрание (альбом, концерт).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum OnlineKind {
    Track,
    Album,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OnlineResult {
    pub external: String,
    pub kind: OnlineKind,
    pub title: String,
    pub artist: Option<String>,
    pub year: Option<u16>,
    pub duration_ms: Option<u64>,
}

/// Почему секции провайдера нечего показать: нет сети; провайдер недоступен
/// (не ответил несколько раз подряд — пауза, plan.md 6.4); ответ не понять.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum OnlineProblem {
    NoNetwork,
    ProviderDown,
    Broken,
}

/// Секция поиска: провайдер и его результаты или беда.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OnlineSearch {
    pub provider: String,
    pub results: Vec<OnlineResult>,
    pub problem: Option<OnlineProblem>,
}

/// Вариант источника: FLAC, MP3, Ogg одного трека.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OnlineVariant {
    pub external: String,
    pub format: Format,
    pub bitrate_kbps: Option<u32>,
}

/// Трек альбома провайдера — ещё не в каталоге.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OnlineTrackInfo {
    pub external: String,
    pub title: String,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub number: Option<u16>,
    pub year: Option<u16>,
    pub duration_ms: Option<u64>,
    pub mbid: Option<String>,
    pub variants: Vec<OnlineVariant>,
}

/// Что отдать Media3: адрес и заголовки.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct StreamAddress {
    pub url: String,
    pub headers: Vec<NetHeader>,
}

#[uniffi::export]
impl Core {
    /// Включает онлайн-источники поверх сети Kotlin. Повторный вызов
    /// заменяет транспорт и сбрасывает здоровье провайдеров.
    pub fn connect_online(&self, transport: Arc<dyn NetTransport>) -> Result<(), CoreError> {
        panic::guard(|| {
            self.set_online(Some(Arc::new(registry(Arc::new(PlatformTransport(transport)), None)?)));
            log::info!("online sources on");
            Ok(())
        })
    }

    pub fn disconnect_online(&self) -> Result<(), CoreError> {
        panic::guard(|| {
            self.set_online(None);
            log::info!("online sources off");
            Ok(())
        })
    }

    /// Секция на каждого провайдера по порядку. Источники выключены или
    /// запрос пуст — секций нет. Блокирует на время ответа сети.
    pub fn online_search(&self, query: String, limit: u32) -> Result<Vec<OnlineSearch>, CoreError> {
        panic::guard(|| {
            let Some(registry) = self.online() else { return Ok(Vec::new()) };
            Ok(registry.search(&SearchQuery::new(&query, limit), Timestamp::now()).into_iter().map(section).collect())
        })
    }

    /// Треки собрания `item` провайдера `provider` (экран альбома).
    pub fn online_album(&self, provider: String, item: String) -> Result<Vec<OnlineTrackInfo>, CoreError> {
        panic::guard(|| {
            let registry = self.online().ok_or_else(off)?;
            let tracks = registry.resolve(&ProviderId::new(&provider)?, &ExternalId::new(&item)?, Timestamp::now())?;
            Ok(tracks.into_iter().map(track_info).collect())
        })
    }

    /// Заводит треки провайдера в каталоге (каталог при действии) — ID в
    /// том же порядке. Уже заведённые находятся по любому варианту.
    pub fn add_online_tracks(&self, provider: String, tracks: Vec<OnlineTrackInfo>) -> Result<Vec<TrackId>, CoreError> {
        panic::guard(|| {
            let provider = ProviderId::new(&provider)?;
            let tracks: Vec<OnlineTrack> =
                tracks.into_iter().map(|track| online_track(&provider, track)).collect::<Result<_, _>>()?;
            let now = Timestamp::now();
            self.with(|state| tracks.iter().map(|track| state.db.add_online_track(track, now)).collect())
        })
    }

    /// Адрес потока трека каталога — в момент загрузки: вариант по сети
    /// (`metered` — сотовая: MP3; иначе лучший), первый ответивший
    /// провайдер. Источники выключены или сетевых вариантов нет — `Unavailable`.
    pub fn online_stream(&self, track: TrackId, metered: bool) -> Result<StreamAddress, CoreError> {
        panic::guard(|| {
            let registry = self.online().ok_or_else(off)?;
            let sources = self.with(|state| state.db.online_sources(track))?;
            let (providers, options): (Vec<ProviderId>, Vec<SourceOption>) = sources
                .into_iter()
                .filter_map(|source| {
                    let external = ExternalId::new(&source.external_id).ok()?;
                    let option = SourceOption { external, format: source.audio.format, bitrate: source.audio.bitrate };
                    Some((source.provider, option))
                })
                .unzip();
            let network = if metered { Network::Metered } else { Network::Unmetered };
            // `playback_order` отдаёт ссылки в `options`: по ним — свой провайдер.
            let candidates: Vec<(ProviderId, ExternalId)> = playback_order(&options, network)
                .into_iter()
                .filter_map(|chosen| options.iter().position(|option| std::ptr::eq(option, chosen)))
                .map(|index| (providers[index].clone(), options[index].external.clone()))
                .collect();
            let (_, stream) = registry.stream(&candidates, Timestamp::now())?;
            Ok(StreamAddress {
                url: stream.url().to_owned(),
                headers: stream
                    .headers()
                    .iter()
                    .map(|(name, value)| NetHeader { name: name.clone(), value: value.clone() })
                    .collect(),
            })
        })
    }
}

/// Реестр онлайн-источников: в v0.2 — Internet Archive. `pause` — паузы
/// между повторами (тесты не ждут); `None` — настоящие.
pub(crate) fn registry(transport: Arc<dyn HttpTransport>, pause: Option<fn(Duration)>) -> Result<Registry, CoreError> {
    let mut archive = InternetArchive::new(transport)?;
    if let Some(pause) = pause {
        archive = archive.with_pause(pause);
    }
    let providers: Vec<Arc<dyn Provider>> = vec![Arc::new(archive)];
    Registry::new(providers, HealthPolicy::default())
}

fn off() -> CoreError {
    CoreError::unavailable("online sources are off")
}

/// Транспорт Kotlin как транспорт ядра.
struct PlatformTransport(Arc<dyn NetTransport>);

impl HttpTransport for PlatformTransport {
    fn get(&self, request: &HttpRequest) -> Result<HttpResponse, CoreError> {
        let request = NetRequest {
            url: request.url.clone(),
            headers: request
                .headers
                .iter()
                .map(|(name, value)| NetHeader { name: name.clone(), value: value.clone() })
                .collect(),
            timeout_ms: u64::try_from(request.timeout.as_millis()).unwrap_or(u64::MAX),
            max_body_bytes: request.max_body_bytes,
        };
        match self.0.get(request) {
            NetAnswer::Response { status, body } => Ok(HttpResponse { status, body }),
            NetAnswer::NoAnswer { reason } => Err(CoreError::network(reason)),
            NetAnswer::TooLarge => Err(CoreError::parse("response is too large")),
        }
    }
}

fn section(search: ProviderSearch) -> OnlineSearch {
    let provider = search.provider.to_string();
    match search.outcome {
        Ok(results) => OnlineSearch { provider, results: results.into_iter().map(result).collect(), problem: None },
        Err(error) => {
            let problem = match error {
                CoreError::Network { .. } => OnlineProblem::NoNetwork,
                CoreError::Unavailable { .. } => OnlineProblem::ProviderDown,
                _ => OnlineProblem::Broken,
            };
            OnlineSearch { provider, results: Vec::new(), problem: Some(problem) }
        }
    }
}

fn result(found: plinth_providers::model::SearchResult) -> OnlineResult {
    OnlineResult {
        external: found.external.to_string(),
        kind: match found.kind {
            ResultKind::Track => OnlineKind::Track,
            ResultKind::Album => OnlineKind::Album,
        },
        title: found.title,
        artist: found.artist,
        year: found.year,
        duration_ms: found.duration.map(millis),
    }
}

fn track_info(track: RemoteTrack) -> OnlineTrackInfo {
    OnlineTrackInfo {
        external: track.external.to_string(),
        title: track.title,
        artist: track.artist,
        album: track.album,
        number: track.number,
        year: track.year,
        duration_ms: track.duration.map(millis),
        mbid: track.mbid.map(|mbid| mbid.to_string()),
        variants: track
            .sources
            .into_iter()
            .map(|source| OnlineVariant {
                external: source.external.to_string(),
                format: source.format,
                bitrate_kbps: source.bitrate.map(Bitrate::as_kbps),
            })
            .collect(),
    }
}

/// Трек из Kotlin — проверенный: пустые и битые варианты — ошибка разбора.
fn online_track(provider: &ProviderId, track: OnlineTrackInfo) -> Result<OnlineTrack, CoreError> {
    let sources = track
        .variants
        .into_iter()
        .map(|variant| {
            let external = ExternalId::new(&variant.external)?;
            Ok(OnlineSource {
                provider: provider.clone(),
                external_id: external.to_string(),
                audio: AudioSpec {
                    format: variant.format,
                    bitrate: variant.bitrate_kbps.filter(|kbps| *kbps > 0).map(Bitrate::kbps),
                    sample_rate_hz: None,
                    bit_depth: None,
                },
            })
        })
        .collect::<Result<_, CoreError>>()?;
    let title = track.title.trim();
    if title.is_empty() {
        return Err(CoreError::parse("online track: no title"));
    }
    Ok(OnlineTrack {
        title: title.to_owned(),
        artist: track.artist.filter(|artist| !artist.trim().is_empty()),
        album: track.album.filter(|album| !album.trim().is_empty()),
        number: track.number.filter(|number| *number > 0),
        year: track.year.filter(|year| *year > 0),
        duration: track.duration_ms.map(Duration::from_millis),
        mbid: track.mbid.and_then(|mbid| mbid.parse::<Mbid>().ok()),
        sources,
    })
}

fn millis(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

#[cfg(test)]
mod tests {
    use std::path::Path;
    use std::sync::{Arc, Mutex};

    use plinth_providers::http::{HttpRequest, HttpTransport};
    use plinth_providers::testing::FixtureTransport;
    use plinth_types::CoreError;

    use super::{NetAnswer, NetRequest, NetTransport, OnlineKind, OnlineProblem, PlatformTransport, registry};
    use crate::session::Core;
    use crate::testing::Scratch;

    /// Записанные ответы Internet Archive (E2) как сеть Kotlin.
    struct Recorded(FixtureTransport);

    impl NetTransport for Recorded {
        fn get(&self, request: NetRequest) -> NetAnswer {
            let request = HttpRequest {
                url: request.url,
                headers: request.headers.into_iter().map(|h| (h.name, h.value)).collect(),
                timeout: std::time::Duration::from_millis(request.timeout_ms),
                max_body_bytes: request.max_body_bytes,
            };
            match self.0.get(&request) {
                Ok(response) => NetAnswer::Response { status: response.status, body: response.body },
                Err(error) => NetAnswer::NoAnswer { reason: error.to_string() },
            }
        }
    }

    fn recorded() -> Arc<dyn NetTransport> {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../providers/tests/fixtures/ia");
        Arc::new(Recorded(FixtureTransport::recorded(&dir).unwrap()))
    }

    /// Ядро с онлайн-источниками поверх `transport`, без пауз между повторами.
    fn online(dir: &Scratch, transport: Arc<dyn NetTransport>) -> Arc<Core> {
        let core = Core::open(dir.path()).unwrap();
        core.set_online(Some(Arc::new(registry(Arc::new(PlatformTransport(transport)), Some(|_| {})).unwrap())));
        core
    }

    const RECORD: &str = "78_oh-doctor_naomi-brown-and-her-piano-h-pearl_gbia0404476a";

    #[test]
    fn search_gives_a_section_per_provider() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());

        let sections = core.online_search("Piano".to_owned(), 10).unwrap();

        assert_eq!(sections.len(), 1);
        assert_eq!(sections[0].provider, "archive.org");
        assert_eq!(sections[0].problem, None);
        assert_eq!(sections[0].results.len(), 5);
        assert_eq!(sections[0].results[0].external, "MIXG031");
        assert_eq!(sections[0].results[0].kind, OnlineKind::Album);
        assert!(core.online_search("  ".to_owned(), 10).unwrap().is_empty(), "a blank query asks nobody");
    }

    #[test]
    fn switched_off_there_are_no_sections_and_no_streams() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());
        let album = core.online_album("archive.org".to_owned(), RECORD.to_owned()).unwrap();
        let ids = core.add_online_tracks("archive.org".to_owned(), album).unwrap();

        core.disconnect_online().unwrap();

        assert!(core.online_search("piano".to_owned(), 10).unwrap().is_empty());
        assert!(matches!(core.online_stream(ids[0], false), Err(CoreError::Unavailable { .. })));
        assert!(matches!(
            core.online_album("archive.org".to_owned(), RECORD.to_owned()),
            Err(CoreError::Unavailable { .. })
        ));
    }

    #[test]
    fn no_network_is_a_problem_of_the_section_not_an_error() {
        struct Offline;
        impl NetTransport for Offline {
            fn get(&self, _: NetRequest) -> NetAnswer {
                NetAnswer::NoAnswer { reason: "no route".to_owned() }
            }
        }
        let dir = Scratch::new();
        let core = online(&dir, Arc::new(Offline));

        let sections = core.online_search("piano".to_owned(), 10).unwrap();

        assert_eq!(sections[0].problem, Some(OnlineProblem::NoNetwork));
        assert!(sections[0].results.is_empty());
    }

    #[test]
    fn album_tracks_carry_their_variants() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());

        let tracks = core.online_album("archive.org".to_owned(), RECORD.to_owned()).unwrap();

        assert_eq!(tracks.len(), 1);
        assert_eq!(tracks[0].title, "\"OH DOCTOR\"");
        assert_eq!(tracks[0].duration_ms, Some(127_450));
        let formats: Vec<_> = tracks[0].variants.iter().map(|v| (v.format, v.bitrate_kbps)).collect();
        assert_eq!(formats, [(super::Format::Flac, None), (super::Format::Mp3, Some(233))]);
    }

    #[test]
    fn added_tracks_play_flac_on_wifi_and_mp3_on_cellular() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());
        let album = core.online_album("archive.org".to_owned(), RECORD.to_owned()).unwrap();

        let ids = core.add_online_tracks("archive.org".to_owned(), album.clone()).unwrap();
        let again = core.add_online_tracks("archive.org".to_owned(), album).unwrap();

        assert_eq!(ids, again, "the same tracks are found, not added twice");
        let wifi = core.online_stream(ids[0], false).unwrap();
        let cellular = core.online_stream(ids[0], true).unwrap();
        assert!(wifi.url.starts_with(&format!("https://archive.org/download/{RECORD}/")), "{}", wifi.url);
        assert!(wifi.url.ends_with(".flac"), "{}", wifi.url);
        assert!(cellular.url.ends_with(".mp3"), "{}", cellular.url);
        assert!(wifi.headers.is_empty());
    }

    #[test]
    fn added_tracks_are_liked_and_listed_like_local_ones() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());
        let album = core.online_album("archive.org".to_owned(), "MIXG031".to_owned()).unwrap();
        let ids = core.add_online_tracks("archive.org".to_owned(), album).unwrap();

        core.like(ids[1]).unwrap();

        let liked: Vec<String> = core.liked_tracks().unwrap().into_iter().map(|row| row.title).collect();
        assert_eq!(liked, ["In My Dreams"]);
        assert!(
            core.tracks(plinth_library::db::query::TrackSort::Title, None).unwrap().is_empty(),
            "library tabs are on-device"
        );
    }

    #[test]
    fn a_local_track_has_no_stream() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());

        let missing = core.online_stream(plinth_types::TrackId::new(), false);

        assert!(matches!(missing, Err(CoreError::Unavailable { .. })));
    }

    #[test]
    fn broken_tracks_from_kotlin_are_refused() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());
        let mut album = core.online_album("archive.org".to_owned(), RECORD.to_owned()).unwrap();
        album[0].title = "  ".to_owned();

        assert!(matches!(core.add_online_tracks("archive.org".to_owned(), album), Err(CoreError::Parse { .. })));
        assert!(matches!(core.add_online_tracks("Bad Provider".to_owned(), Vec::new()), Err(CoreError::Parse { .. })));
    }

    #[test]
    fn kotlin_gets_the_policy_headers_and_timeout() {
        struct Spy(Mutex<Vec<NetRequest>>);
        impl NetTransport for Spy {
            fn get(&self, request: NetRequest) -> NetAnswer {
                self.0.lock().unwrap().push(request);
                NetAnswer::TooLarge
            }
        }
        let spy = Arc::new(Spy(Mutex::new(Vec::new())));
        let dir = Scratch::new();
        let core = online(&dir, spy.clone());

        let sections = core.online_search("piano".to_owned(), 3).unwrap();

        let requests = spy.0.lock().unwrap();
        assert_eq!(requests.len(), 1, "too large is not retried");
        assert_eq!(requests[0].timeout_ms, 15_000);
        assert!(requests[0].headers.iter().any(|h| h.name == "User-Agent" && h.value.starts_with("Plinth/")));
        assert!(requests[0].url.contains("rows=3"), "{}", requests[0].url);
        assert_eq!(sections[0].problem, Some(OnlineProblem::Broken));
    }
}
