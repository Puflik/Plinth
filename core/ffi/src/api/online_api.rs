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
use plinth_providers::http::{
    Cancel, HttpRequest, HttpResponse, HttpTransport, Limits, NetFailure, check_status, parse_retry_after, timed_out,
};
use plinth_providers::model::{
    ExternalId, HealthPolicy, Network, RemoteTrack, ResultKind, SearchQuery, SourceOption, playback_order,
};
use plinth_providers::{InternetArchive, Provider, ProviderSearch, Registry};
use plinth_types::{Bitrate, CoreError, Format, Mbid, ProviderId, Timestamp, TrackId};

use super::external_api::{ExternalAdapter, ExternalProvider};
use crate::panic;
use crate::session::Core;

/// Сеть платформы: один GET без повторов. Ответ с любым статусом —
/// `Response`, с заголовками (`Retry-After`); соединения не вышло (нет сети,
/// оборвалось) — `NoAnswer` с причиной без адреса; не дождались ответа —
/// `TimedOut`; тело длиннее `max_body_bytes` — `TooLarge`.
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
    Response { status: u16, body: Vec<u8>, headers: Vec<NetHeader> },
    NoAnswer { reason: String },
    TimedOut,
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

/// Почему секции провайдера (и экрану альбома) нечего показать. Три разные беды:
/// соединения не вышло (нет сети, DNS) — `NoNetwork`, и только тогда говорят «нет
/// сети»; сервер отказал статусом, не дождались ответа или провайдер в паузе
/// (не отвечал несколько раз подряд, plan.md 6.4) — `ProviderDown`; сервер просит
/// подождать (429) — `RateLimited`; ответ не понять — `Broken`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum OnlineProblem {
    NoNetwork,
    ProviderDown,
    RateLimited,
    Broken,
}

/// Секция поиска: провайдер и его результаты или беда.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OnlineSearch {
    pub provider: String,
    pub results: Vec<OnlineResult>,
    pub problem: Option<OnlineProblem>,
}

/// Экран альбома: треки собрания или беда (`tracks` пуст, когда `problem` есть).
/// Провайдер, который не ответил, — это ответ экрану, а не ошибка вызова.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OnlineAlbumReply {
    pub tracks: Vec<OnlineTrackInfo>,
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

/// Что отдать Media3: адрес и заголовки. `valid_for_ms` — сколько адрес годен с получения
/// (у YouTube — часы); `None` — не стареет.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct StreamAddress {
    pub url: String,
    pub headers: Vec<NetHeader>,
    pub valid_for_ms: Option<u64>,
}

#[uniffi::export]
impl Core {
    /// Включает онлайн-источники поверх сети Kotlin: Internet Archive и провайдеры платформы
    /// (`providers`, YouTube) в порядке аргумента. Повторный вызов заменяет транспорт и
    /// провайдеров и сбрасывает здоровье. Негодный провайдер (имя, пустой `finds`, повтор
    /// имени) — ошибка, и прежнее состояние остаётся.
    pub fn connect_online(
        &self,
        transport: Arc<dyn NetTransport>,
        providers: Vec<Arc<dyn ExternalProvider>>,
    ) -> Result<(), CoreError> {
        panic::guard(|| {
            let external = providers
                .into_iter()
                .map(|platform| ExternalAdapter::new(platform).map(|adapter| Arc::new(adapter) as Arc<dyn Provider>))
                .collect::<Result<Vec<_>, _>>()?;
            let registry = registry_with(Arc::new(PlatformTransport(transport)), None, external)?;
            self.set_online(Some(Arc::new(registry)));
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
    /// запрос пуст — секций нет. Блокирует на время ответа сети, но не дольше
    /// [`SEARCH_BUDGET`]; `cancel.cancel()` из другого потока обрывает поиск
    /// между шагами — ответом будет то, что собрано до отмены (обычно ничего).
    pub fn online_search(
        &self,
        query: String,
        limit: u32,
        cancel: Arc<OnlineCancel>,
    ) -> Result<Vec<OnlineSearch>, CoreError> {
        self.online_search_within(query, limit, &cancel, SEARCH_BUDGET)
    }

    /// Треки собрания `item` провайдера `provider` (экран альбома). Провайдер,
    /// который не ответил (`Network`, `Unavailable`, `Parse`), — `problem` в ответе, а не
    /// ошибка, как в секции поиска. Источники выключены, неверный ID, `Internal`, `Storage` —
    /// ошибка вызова.
    pub fn online_album(&self, provider: String, item: String) -> Result<OnlineAlbumReply, CoreError> {
        panic::guard(|| {
            let registry = self.online().ok_or_else(off)?;
            let answer = registry.resolve(&ProviderId::new(&provider)?, &ExternalId::new(&item)?, Timestamp::now());
            match answer {
                Ok(tracks) => {
                    Ok(OnlineAlbumReply { tracks: tracks.into_iter().map(track_info).collect(), problem: None })
                }
                Err(error @ (CoreError::Network { .. } | CoreError::Unavailable { .. } | CoreError::Parse { .. })) => {
                    Ok(OnlineAlbumReply { tracks: Vec::new(), problem: Some(problem_of(&error)) })
                }
                Err(error) => Err(error),
            }
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

    /// Какие из треков провайдера уже в каталоге — ID или `None`, в том же
    /// порядке; каталог не меняется. Экрану альбома — лайк до первой игры.
    pub fn known_online_tracks(
        &self,
        provider: String,
        tracks: Vec<OnlineTrackInfo>,
    ) -> Result<Vec<Option<TrackId>>, CoreError> {
        panic::guard(|| {
            let provider = ProviderId::new(&provider)?;
            let tracks: Vec<OnlineTrack> =
                tracks.into_iter().map(|track| online_track(&provider, track)).collect::<Result<_, _>>()?;
            self.with(|state| tracks.iter().map(|track| state.db.find_online_track(track)).collect())
        })
    }

    /// Адрес потока трека каталога — в момент загрузки: вариант по сети
    /// (`metered` — сотовая: MP3; иначе лучший), первый ответивший
    /// провайдер. `undecodable` — форматы без декодера на устройстве (FLAC на
    /// Android 8.0): они пробуются последними. Источники выключены или
    /// сетевых вариантов нет — `Unavailable`.
    pub fn online_stream(
        &self,
        track: TrackId,
        metered: bool,
        undecodable: Vec<Format>,
    ) -> Result<StreamAddress, CoreError> {
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
            let candidates: Vec<(ProviderId, ExternalId)> = playback_order(&options, network, &undecodable)
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
                valid_for_ms: stream.valid_for().map(millis),
            })
        })
    }
}

impl Core {
    /// `online_search` со своим сроком: тесты берут короткий.
    pub(crate) fn online_search_within(
        &self,
        query: String,
        limit: u32,
        cancel: &OnlineCancel,
        budget: Duration,
    ) -> Result<Vec<OnlineSearch>, CoreError> {
        panic::guard(|| {
            let Some(registry) = self.online() else { return Ok(Vec::new()) };
            let query = SearchQuery::new(&query, limit).within(Limits::within(budget).cancelled_by(&cancel.cancel));
            Ok(registry.search(&query, Timestamp::now()).into_iter().map(section).collect())
        })
    }
}

/// Срок одного онлайн-поиска вместе с повторами и паузами: одна попытка политики
/// (15 с, `config.json`). `advancedsearch` Internet Archive на пробе 2026-10-15
/// отвечал за 1,9–5,9 с, поэтому 10 с резали бы медленный, но живой ответ.
pub(crate) const SEARCH_BUDGET: Duration = Duration::from_secs(15);

/// Отмена онлайн-поиска. Kotlin заводит по одной на вызов и зовёт `cancel`,
/// когда ответ уже не нужен. Ядро проверяет её между шагами: идущий запрос
/// сети оно не прерывает, но после него не делает ничего.
#[derive(uniffi::Object, Default)]
pub struct OnlineCancel {
    cancel: Cancel,
}

#[uniffi::export]
impl OnlineCancel {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn cancel(&self) {
        self.cancel.cancel();
    }
}

/// Реестр онлайн-источников: Internet Archive, затем провайдеры платформы (`external`) в своём
/// порядке; реестр ставит их по лучшему качеству устойчиво, так что `Lossless` у платформы
/// встаёт после Internet Archive. `pause` — паузы между повторами (тесты не ждут); `None` —
/// настоящие.
pub(crate) fn registry_with(
    transport: Arc<dyn HttpTransport>,
    pause: Option<fn(Duration)>,
    external: Vec<Arc<dyn Provider>>,
) -> Result<Registry, CoreError> {
    let mut archive = InternetArchive::new(transport)?;
    if let Some(pause) = pause {
        archive = archive.with_pause(pause);
    }
    let mut providers: Vec<Arc<dyn Provider>> = vec![Arc::new(archive)];
    providers.extend(external);
    Registry::new(providers, HealthPolicy::default())
}

/// Реестр из одного Internet Archive — для тестов, что платформенных провайдеров не знают.
#[cfg(test)]
pub(crate) fn registry(transport: Arc<dyn HttpTransport>, pause: Option<fn(Duration)>) -> Result<Registry, CoreError> {
    registry_with(transport, pause, Vec::new())
}

fn off() -> CoreError {
    CoreError::unavailable("online sources are off")
}

/// Таблица «статус HTTP → ошибка» ядра (`plinth_providers::http::check_status`) для
/// Kotlin: второй таблицы нет. `retry_after` — значение заголовка `Retry-After`, как
/// прислал сервер; не целое число секунд (дата, мусор) срока не даёт.
#[uniffi::export]
pub fn check_http_status(status: u16, retry_after: Option<String>) -> Result<(), CoreError> {
    panic::guard(|| check_status(status, retry_after.as_deref().and_then(parse_retry_after)))
}

/// Транспорт Kotlin как транспорт ядра.
pub(crate) struct PlatformTransport(pub(crate) Arc<dyn NetTransport>);

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
            NetAnswer::Response { status, body, headers } => Ok(HttpResponse {
                status,
                body,
                headers: headers.into_iter().map(|NetHeader { name, value }| (name, value)).collect(),
            }),
            NetAnswer::NoAnswer { reason } => Err(CoreError::network(reason)),
            NetAnswer::TimedOut => Err(timed_out()),
            NetAnswer::TooLarge => Err(CoreError::parse("response is too large")),
        }
    }
}

fn section(search: ProviderSearch) -> OnlineSearch {
    let provider = search.provider.to_string();
    match search.outcome {
        Ok(results) => OnlineSearch { provider, results: results.into_iter().map(result).collect(), problem: None },
        Err(error) => OnlineSearch { provider, results: Vec::new(), problem: Some(problem_of(&error)) },
    }
}

/// Беда секции поиска и экрана альбома — одна для обоих. Что случилось с сетевым вызовом,
/// читает `NetFailure::of`: отказ статусом и «не дождались» — провайдер не отвечает (429 —
/// просит подождать), «нет сети» — только когда соединения не вышло. `Unavailable` — провайдер
/// в паузе или элемента нет; остальное — ответ не понять.
fn problem_of(error: &CoreError) -> OnlineProblem {
    match NetFailure::of(error) {
        Some(NetFailure::Status { status: 429, .. }) => OnlineProblem::RateLimited,
        Some(NetFailure::Status { .. } | NetFailure::TimedOut) => OnlineProblem::ProviderDown,
        Some(NetFailure::NoConnection) => OnlineProblem::NoNetwork,
        None => match error {
            CoreError::Unavailable { .. } => OnlineProblem::ProviderDown,
            _ => OnlineProblem::Broken,
        },
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
    use std::fs;
    use std::io::{Seek, SeekFrom, Write};
    use std::path::Path;
    use std::sync::{Arc, Mutex};

    use plinth_providers::http::{HttpRequest, HttpTransport};
    use plinth_providers::testing::FixtureTransport;
    use plinth_types::{CoreError, Format};

    use super::{
        NetAnswer, NetHeader, NetRequest, NetTransport, OnlineCancel, OnlineKind, OnlineProblem, PlatformTransport,
        registry,
    };
    use crate::session::{Core, DATABASE, JOURNAL};
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
                Ok(response) => NetAnswer::Response {
                    status: response.status,
                    body: response.body,
                    headers: response.headers.into_iter().map(|(name, value)| NetHeader { name, value }).collect(),
                },
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

        let sections = core.online_search("Piano".to_owned(), 10, OnlineCancel::new()).unwrap();

        assert_eq!(sections.len(), 1);
        assert_eq!(sections[0].provider, "archive.org");
        assert_eq!(sections[0].problem, None);
        assert_eq!(sections[0].results.len(), 5);
        assert_eq!(sections[0].results[0].external, "MIXG031");
        assert_eq!(sections[0].results[0].kind, OnlineKind::Album);
        assert!(
            core.online_search("  ".to_owned(), 10, OnlineCancel::new()).unwrap().is_empty(),
            "a blank query asks nobody"
        );
    }

    #[test]
    fn switched_off_there_are_no_sections_and_no_streams() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());
        let album = core.online_album("archive.org".to_owned(), RECORD.to_owned()).unwrap().tracks;
        let ids = core.add_online_tracks("archive.org".to_owned(), album).unwrap();

        core.disconnect_online().unwrap();

        assert!(core.online_search("piano".to_owned(), 10, OnlineCancel::new()).unwrap().is_empty());
        assert!(matches!(core.online_stream(ids[0], false, Vec::new()), Err(CoreError::Unavailable { .. })));
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

        let sections = core.online_search("piano".to_owned(), 10, OnlineCancel::new()).unwrap();

        assert_eq!(sections[0].problem, Some(OnlineProblem::NoNetwork));
        assert!(sections[0].results.is_empty());
    }

    #[test]
    fn album_tracks_carry_their_variants() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());

        let tracks = core.online_album("archive.org".to_owned(), RECORD.to_owned()).unwrap().tracks;

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
        let album = core.online_album("archive.org".to_owned(), RECORD.to_owned()).unwrap().tracks;

        let ids = core.add_online_tracks("archive.org".to_owned(), album.clone()).unwrap();
        let again = core.add_online_tracks("archive.org".to_owned(), album).unwrap();

        assert_eq!(ids, again, "the same tracks are found, not added twice");
        let wifi = core.online_stream(ids[0], false, Vec::new()).unwrap();
        let cellular = core.online_stream(ids[0], true, Vec::new()).unwrap();
        assert!(wifi.url.starts_with(&format!("https://archive.org/download/{RECORD}/")), "{}", wifi.url);
        assert!(wifi.url.ends_with(".flac"), "{}", wifi.url);
        assert!(cellular.url.ends_with(".mp3"), "{}", cellular.url);
        assert!(wifi.headers.is_empty());
    }

    /// Android 8.0 не декодирует FLAC: по Wi-Fi — MP3, а не «формат не поддерживается».
    #[test]
    fn a_format_without_a_decoder_is_not_picked_while_there_is_another() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());
        let album = core.online_album("archive.org".to_owned(), RECORD.to_owned()).unwrap().tracks;
        let ids = core.add_online_tracks("archive.org".to_owned(), album).unwrap();

        let wifi = core.online_stream(ids[0], false, vec![Format::Flac]).unwrap();

        assert!(wifi.url.ends_with(".mp3"), "{}", wifi.url);
    }

    #[test]
    fn known_tracks_are_found_without_adding_the_rest() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());
        let album = core.online_album("archive.org".to_owned(), "MIXG031".to_owned()).unwrap().tracks;
        let added = core.add_online_tracks("archive.org".to_owned(), vec![album[1].clone()]).unwrap();

        let known = core.known_online_tracks("archive.org".to_owned(), album.clone()).unwrap();

        assert_eq!(known.len(), album.len());
        assert_eq!(known[1], Some(added[0]));
        assert!(known.iter().enumerate().all(|(i, id)| i == 1 || id.is_none()), "{known:?}");
        let again = core.known_online_tracks("archive.org".to_owned(), album).unwrap();
        assert_eq!(again, known, "looking does not add");
    }

    #[test]
    fn added_tracks_are_liked_and_listed_like_local_ones() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());
        let album = core.online_album("archive.org".to_owned(), "MIXG031".to_owned()).unwrap().tracks;
        let ids = core.add_online_tracks("archive.org".to_owned(), album).unwrap();

        core.like(ids[1]).unwrap();

        let liked: Vec<String> = core.liked_tracks().unwrap().into_iter().map(|row| row.title).collect();
        assert_eq!(liked, ["In My Dreams"]);
        assert!(
            core.tracks(plinth_library::db::query::TrackSort::Title, None).unwrap().is_empty(),
            "library tabs are on-device"
        );
    }

    /// Сетевой трек альбома MIXG031 в «Любимом» — установка, которую дальше портят.
    fn liked_online_track(dir: &Scratch) {
        let core = online(dir, recorded());
        let album = core.online_album("archive.org".to_owned(), "MIXG031".to_owned()).unwrap().tracks;
        let ids = core.add_online_tracks("archive.org".to_owned(), album).unwrap();
        core.like(ids[1]).unwrap();
    }

    fn liked(core: &Core) -> Vec<String> {
        core.liked_tracks().unwrap().into_iter().map(|row| row.title).collect()
    }

    /// Испорченная база пересобирается из журнала при открытии, и сетевой
    /// трек в «Любимом» виден сразу: скана, который бы его перепривязал, у
    /// человека без разрешения на музыку нет вовсе.
    #[test]
    fn a_liked_online_track_is_back_right_after_a_damaged_database() {
        let dir = Scratch::new();
        liked_online_track(&dir);
        let mut file = fs::OpenOptions::new().write(true).open(dir.0.join(DATABASE)).unwrap();
        file.seek(SeekFrom::Start(0)).unwrap();
        file.write_all(&[0xde; 4096]).unwrap();
        drop(file);

        let core = Core::open(dir.path()).unwrap();

        assert!(core.startup_report().unwrap().database_recovered);
        assert_eq!(liked(&core), ["In My Dreams"]);
    }

    /// Auto Backup возвращает только журнал (C4): новая установка открывает
    /// его без базы и без `device` — сетевой трек в «Любимом» сразу.
    #[test]
    fn a_liked_online_track_comes_back_with_a_journal_from_the_backup() {
        let old = Scratch::new();
        liked_online_track(&old);
        let dir = Scratch::new();
        copy_dir(&old.0.join(JOURNAL), &dir.0.join(JOURNAL));

        let core = Core::open(dir.path()).unwrap();

        assert!(core.startup_report().unwrap().restored_from_journal);
        assert_eq!(liked(&core), ["In My Dreams"]);
    }

    fn copy_dir(from: &Path, to: &Path) {
        fs::create_dir_all(to).unwrap();
        for entry in fs::read_dir(from).unwrap() {
            let path = entry.unwrap().path();
            let target = to.join(path.file_name().unwrap());
            if path.is_dir() {
                copy_dir(&path, &target);
            } else {
                fs::copy(&path, &target).unwrap();
            }
        }
    }

    #[test]
    fn a_local_track_has_no_stream() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());

        let missing = core.online_stream(plinth_types::TrackId::new(), false, Vec::new());

        assert!(matches!(missing, Err(CoreError::Unavailable { .. })));
    }

    #[test]
    fn broken_tracks_from_kotlin_are_refused() {
        let dir = Scratch::new();
        let core = online(&dir, recorded());
        let mut album = core.online_album("archive.org".to_owned(), RECORD.to_owned()).unwrap().tracks;
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

        let sections = core.online_search("piano".to_owned(), 3, OnlineCancel::new()).unwrap();

        let requests = spy.0.lock().unwrap();
        assert_eq!(requests.len(), 1, "too large is not retried");
        assert!((14_000..=15_000).contains(&requests[0].timeout_ms));
        assert!(requests[0].headers.iter().any(|h| h.name == "User-Agent" && h.value.starts_with("Plinth/")));
        assert!(requests[0].url.contains("rows=3"), "{}", requests[0].url);
        assert_eq!(sections[0].problem, Some(OnlineProblem::Broken));
    }
}
