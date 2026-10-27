//! Провайдер, который живёт на платформе (Ю3в, docs/adr/0008-provider-trait.md, поправка).
//!
//! Сеть по-прежнему у платформы, но целый провайдер — YouTube на NewPipeExtractor — написан на
//! Kotlin: [`ExternalProvider`] — его граница с ядром, как [`super::online_api::NetTransport`] —
//! граница сети. [`ExternalAdapter`] делает из него обычный [`Provider`], и реестр (порядок,
//! здоровье, переход к следующему источнику) разницы не видит.
//!
//! - ответы — те же `OnlineResult`, `OnlineTrackInfo`, `StreamAddress`, что у экранов: второго
//!   формата нет;
//! - исключение через границу не идёт: `CoreError` у UniFFI плоская, и Kotlin'у её в возврате не
//!   подать, поэтому ответ — `Found` или `Failed` (как `NetAnswer`);
//! - идентификаторы идут сквозь адаптер дословно: они уходят в каталог и журнал, и провайдер им
//!   хозяин;
//! - данные платформы проверяются, в тексте ошибки нет ничего из них (адреса, идентификаторов,
//!   названий);
//! - паника платформы (UniFFI превращает непойманное исключение Kotlin в панику) — `Internal`
//!   этого вызова: остальные провайдеры и их секции целы.

use std::sync::Arc;
use std::time::Duration;

use plinth_providers::Provider;
use plinth_providers::model::{
    Capabilities, ExternalId, RadioPage, RemoteTrack, ResultKind, SearchQuery, SearchResult, SourceOption,
    StreamRequest,
};
use plinth_types::{Bitrate, CoreError, Mbid, ProviderId, QualityTier};

use super::online_api::{NetHeader, OnlineKind, OnlineResult, OnlineTrackInfo, StreamAddress};
use crate::panic;

// `plinth-types` уже знает `QualityTier`; ffi объявляет его копию для генератора, как `Format`
// в `online_api.rs`.
#[uniffi::remote(Enum)]
pub enum QualityTier {
    Standard,
    HighLossy,
    Lossless,
}

/// Провайдер на платформе. Вызовы синхронные и блокирующие, как у `Provider`: Kotlin зовёт ядро
/// из IO-потока. Сеть провайдер ведёт сам; срок и отмену ядро ему не передаёт.
#[uniffi::export(with_foreign)]
pub trait ExternalProvider: Send + Sync {
    /// `ProviderId`: `[a-z0-9.-]{1,64}`; уходит в каталог и журнал.
    fn id(&self) -> String;

    fn capabilities(&self) -> ExternalCapabilities;

    /// Не больше `limit` результатов; пустой запрос до платформы не доходит.
    fn search(&self, query: String, limit: u32) -> ExternalSearch;

    /// Треки результата поиска: у собрания — все, у трека — он один; у каждого есть вариант.
    fn resolve(&self, item: String) -> ExternalResolve;

    /// Адрес потока варианта источника из `resolve`.
    fn stream(&self, source: String) -> ExternalStream;

    /// Радио от трека `seed` (id трека или его источника); `page` — ключ из прошлого ответа.
    /// Не умеет — `Failed { Unavailable }`: у внешнего трейта UniFFI методов по умолчанию не бывает.
    fn radio(&self, seed: String, page: Option<String>) -> ExternalRadio;
}

/// Больше треков на странице радио ядро не берёт: платформа не должна раздувать ответ.
const RADIO_PAGE_MAX: usize = 100;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ExternalCapabilities {
    pub finds: Vec<OnlineKind>,
    pub best_quality: QualityTier,
}

/// Беда платформы — того же вида, что `CoreError`, и с тем же текстом: текст читает здоровье
/// (`NetFailure::of` — «http 503», «http 429, retry after 30 s», «timed out»).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ExternalFailure {
    Network { text: String },
    Parse { text: String },
    Unavailable { text: String },
    Internal { text: String },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ExternalSearch {
    Found { results: Vec<OnlineResult> },
    Failed { failure: ExternalFailure },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ExternalResolve {
    Found { tracks: Vec<OnlineTrackInfo> },
    Failed { failure: ExternalFailure },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ExternalStream {
    Found { address: StreamAddress },
    Failed { failure: ExternalFailure },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ExternalRadio {
    Found { tracks: Vec<OnlineTrackInfo>, next: Option<String> },
    Failed { failure: ExternalFailure },
}

impl From<ExternalFailure> for CoreError {
    fn from(failure: ExternalFailure) -> Self {
        match failure {
            ExternalFailure::Network { text } => Self::network(text),
            ExternalFailure::Parse { text } => Self::parse(text),
            ExternalFailure::Unavailable { text } => Self::unavailable(text),
            ExternalFailure::Internal { text } => Self::internal(text),
        }
    }
}

/// Провайдер платформы как `Provider`. Имя и возможности спрашиваются один раз, при создании.
pub(crate) struct ExternalAdapter {
    id: ProviderId,
    capabilities: Capabilities,
    platform: Arc<dyn ExternalProvider>,
}

impl ExternalAdapter {
    /// Негодное имя и пустой `finds` — `Parse` (в реестр такой провайдер не попадёт); паника
    /// платформы при вопросе — `Internal`. Имя в текст ошибки не попадает.
    pub(crate) fn new(platform: Arc<dyn ExternalProvider>) -> Result<Self, CoreError> {
        let name = panic::guard(|| Ok(platform.id()))?;
        let id = ProviderId::new(&name).map_err(|_| CoreError::parse("external provider: the name is not allowed"))?;
        let declared = panic::guard(|| Ok(platform.capabilities()))?;
        if declared.finds.is_empty() {
            return Err(CoreError::parse("external provider: finds nothing"));
        }
        let capabilities = Capabilities {
            finds: declared.finds.into_iter().map(result_kind).collect(),
            best_quality: declared.best_quality,
        };
        Ok(Self { id, capabilities, platform })
    }
}

impl Provider for ExternalAdapter {
    fn id(&self) -> &ProviderId {
        &self.id
    }

    fn capabilities(&self) -> Capabilities {
        self.capabilities.clone()
    }

    fn search(&self, query: &SearchQuery) -> Result<Vec<SearchResult>, CoreError> {
        if query.is_blank() {
            return Ok(Vec::new());
        }
        let answer = panic::guard(|| Ok(self.platform.search(query.text().to_owned(), query.limit())))?;
        match answer {
            ExternalSearch::Failed { failure } => Err(failure.into()),
            ExternalSearch::Found { results } => {
                // Лишнее сверх лимита отрезается с конца до разбора: провайдер не отдаёт больше, чем просили.
                let limit = usize::try_from(query.limit()).unwrap_or(usize::MAX);
                results.into_iter().take(limit).map(search_result).collect()
            }
        }
    }

    fn resolve(&self, item: &ExternalId) -> Result<Vec<RemoteTrack>, CoreError> {
        let answer = panic::guard(|| Ok(self.platform.resolve(item.as_str().to_owned())))?;
        match answer {
            ExternalResolve::Failed { failure } => Err(failure.into()),
            ExternalResolve::Found { tracks } => tracks.into_iter().map(remote_track).collect(),
        }
    }

    fn stream_url(&self, source: &ExternalId) -> Result<StreamRequest, CoreError> {
        let answer = panic::guard(|| Ok(self.platform.stream(source.as_str().to_owned())))?;
        match answer {
            ExternalStream::Failed { failure } => Err(failure.into()),
            ExternalStream::Found { address } => {
                let headers = address.headers.into_iter().map(|NetHeader { name, value }| (name, value)).collect();
                let request = StreamRequest::new(&address.url, headers)?;
                Ok(match address.valid_for_ms {
                    Some(millis) => request.with_valid_for(Duration::from_millis(millis)),
                    None => request,
                })
            }
        }
    }

    fn radio(&self, seed: &ExternalId, page: Option<&ExternalId>) -> Result<RadioPage, CoreError> {
        let answer = panic::guard(|| {
            Ok(self.platform.radio(seed.as_str().to_owned(), page.map(|key| key.as_str().to_owned())))
        })?;
        match answer {
            ExternalRadio::Failed { failure } => Err(failure.into()),
            ExternalRadio::Found { tracks, next } => {
                let offered = tracks.len().min(RADIO_PAGE_MAX);
                // Негодный трек (пустышка вместо удалённого видео) не убивает страницу.
                let usable: Vec<RemoteTrack> =
                    tracks.into_iter().take(RADIO_PAGE_MAX).filter_map(|track| remote_track(track).ok()).collect();
                if usable.is_empty() && offered > 0 {
                    return Err(CoreError::parse("external radio: no usable track"));
                }
                let next = next.map(|key| ExternalId::new(&key)).transpose()?;
                Ok(RadioPage { tracks: usable, next })
            }
        }
    }
}

fn result_kind(kind: OnlineKind) -> ResultKind {
    match kind {
        OnlineKind::Track => ResultKind::Track,
        OnlineKind::Album => ResultKind::Album,
    }
}

fn search_result(found: OnlineResult) -> Result<SearchResult, CoreError> {
    Ok(SearchResult {
        external: ExternalId::new(&found.external)?,
        kind: result_kind(found.kind),
        title: title_of(&found.title, "external result: no title")?,
        artist: caption(found.artist),
        album: None,
        year: found.year.filter(|year| *year > 0),
        duration: found.duration_ms.map(Duration::from_millis),
    })
}

fn remote_track(track: OnlineTrackInfo) -> Result<RemoteTrack, CoreError> {
    let sources = track
        .variants
        .into_iter()
        .map(|variant| {
            Ok(SourceOption {
                external: ExternalId::new(&variant.external)?,
                format: variant.format,
                bitrate: variant.bitrate_kbps.filter(|kbps| *kbps > 0).map(Bitrate::kbps),
            })
        })
        .collect::<Result<Vec<_>, CoreError>>()?;
    if sources.is_empty() {
        return Err(CoreError::parse("external track: no variants"));
    }
    Ok(RemoteTrack {
        external: ExternalId::new(&track.external)?,
        title: title_of(&track.title, "external track: no title")?,
        artist: caption(track.artist),
        album: caption(track.album),
        disc: None,
        number: track.number.filter(|number| *number > 0),
        year: track.year.filter(|year| *year > 0),
        duration: track.duration_ms.map(Duration::from_millis),
        // Непонятный MBID — не ошибка: трек узнаётся и без него.
        mbid: track.mbid.and_then(|mbid| mbid.parse::<Mbid>().ok()),
        sources,
    })
}

/// Название без пробелов по краям; пустое — ошибка разбора без самого названия.
fn title_of(title: &str, error: &'static str) -> Result<String, CoreError> {
    let title = title.trim();
    if title.is_empty() { Err(CoreError::parse(error)) } else { Ok(title.to_owned()) }
}

/// Пустая подпись (исполнитель, альбом) — `None`.
fn caption(text: Option<String>) -> Option<String> {
    text.filter(|text| !text.trim().is_empty())
}
