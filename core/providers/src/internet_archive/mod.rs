//! Internet Archive (E2, plan.md 6.2) — первый провайдер: открытый API,
//! концерты Live Music Archive, нетлейблы, пластинки 78 об., часто FLAC.
//!
//! - поиск — элементы (концерты, альбомы, пластинки), только музыка;
//! - `resolve` — метаданные элемента → треки: исходник и его производные
//!   (ответ автора, E2); ID трека — `{элемент}/{исходник}`, варианта —
//!   `{элемент}/{файл}`;
//! - `stream_url` строит адрес `download/{элемент}/{файл}` сам, без сети:
//!   пропавший файл увидит плеер.
//!
//! Эндпоинты, фильтр коллекций, имена полей и метки форматов — во
//! встроенном [`CONFIG`] (E1.3).

mod mapping;
mod metadata;
mod search;
mod values;

use std::sync::Arc;
use std::time::Duration;

use plinth_types::{CoreError, ProviderId, QualityTier};

use crate::Provider;
use crate::config::ProviderConfig;
use crate::http::{HttpClient, HttpTransport};
use crate::model::{Capabilities, ExternalId, RemoteTrack, ResultKind, SearchQuery, SearchResult, StreamRequest};

/// Встроенный конфиг Internet Archive.
pub const CONFIG: &str = include_str!("config.json");

pub struct InternetArchive {
    config: ProviderConfig,
    http: HttpClient,
}

impl InternetArchive {
    pub fn new(transport: Arc<dyn HttpTransport>) -> Result<Self, CoreError> {
        let config = ProviderConfig::parse(CONFIG)?;
        let http = HttpClient::new(transport, config.http.clone());
        Ok(Self { config, http })
    }

    /// Паузы между повторами — через `pause` (тесты не ждут).
    pub fn with_pause(mut self, pause: impl Fn(Duration) + Send + Sync + 'static) -> Self {
        self.http = self.http.with_pause(pause);
        self
    }

    fn get(&self, endpoint: &str, vars: &[(&str, &str)]) -> Result<Vec<u8>, CoreError> {
        let url = self.config.endpoint(endpoint)?.url(vars)?;
        self.http.get(&url, &self.config.headers)
    }
}

impl Provider for InternetArchive {
    fn id(&self) -> &ProviderId {
        &self.config.provider
    }

    fn capabilities(&self) -> Capabilities {
        Capabilities { finds: vec![ResultKind::Album], best_quality: QualityTier::Lossless }
    }

    fn search(&self, query: &SearchQuery) -> Result<Vec<SearchResult>, CoreError> {
        let text = search::search_text(query.text());
        if text.is_empty() {
            return Ok(Vec::new());
        }
        let limit = query.limit().to_string();
        let body = self.get("search", &[("query", &text), ("limit", &limit)])?;
        search::results(&body, &self.config, query.limit())
    }

    /// Элемент — все его треки; трек или вариант (`элемент/файл`) — один трек.
    fn resolve(&self, item: &ExternalId) -> Result<Vec<RemoteTrack>, CoreError> {
        let (identifier, file) = split(item);
        if identifier.is_empty() {
            return Err(CoreError::unavailable("archive.org: no item in the id"));
        }
        let body = self.get("metadata", &[("id", identifier)])?;
        let tracks = mapping::tracks(&metadata::parse(&body, &self.config)?, identifier);
        match file {
            None if tracks.is_empty() => Err(CoreError::unavailable("archive.org: no playable audio in the item")),
            None => Ok(tracks),
            Some(_) => tracks
                .into_iter()
                .find(|track| &track.external == item || track.sources.iter().any(|source| &source.external == item))
                .map(|track| vec![track])
                .ok_or_else(|| CoreError::unavailable("archive.org: no such track in the item")),
        }
    }

    fn stream_url(&self, source: &ExternalId) -> Result<StreamRequest, CoreError> {
        let (identifier, Some(file)) = split(source) else {
            return Err(CoreError::unavailable("archive.org: not a file"));
        };
        if identifier.is_empty() || file.is_empty() {
            return Err(CoreError::unavailable("archive.org: not a file"));
        }
        let url = self.config.endpoint("download")?.url(&[("id", identifier), ("file", file)])?;
        StreamRequest::new(&url, Vec::new())
    }
}

/// `элемент` или `элемент/файл` (файл — с подкаталогами).
fn split(id: &ExternalId) -> (&str, Option<&str>) {
    match id.as_str().split_once('/') {
        Some((identifier, file)) => (identifier, Some(file)),
        None => (id.as_str(), None),
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use plinth_types::{CoreError, ProviderId};

    use super::{CONFIG, InternetArchive};
    use crate::Provider;
    use crate::config::ProviderConfig;
    use crate::http::HttpResponse;
    use crate::model::{ExternalId, SearchQuery};
    use crate::testing::FixtureTransport;

    fn archive(transport: FixtureTransport) -> (InternetArchive, Arc<FixtureTransport>) {
        let transport = Arc::new(transport);
        let archive = InternetArchive::new(transport.clone()).unwrap().with_pause(|_| {});
        (archive, transport)
    }

    fn id(text: &str) -> ExternalId {
        ExternalId::new(text).unwrap()
    }

    #[test]
    fn embedded_config_is_valid() {
        let config = ProviderConfig::parse(CONFIG).unwrap();

        assert_eq!(config.provider, ProviderId::new("archive.org").unwrap());
        for endpoint in ["search", "metadata", "download"] {
            assert!(config.endpoint(endpoint).is_ok(), "{endpoint}");
        }
    }

    #[test]
    fn search_asks_for_music_by_artist_and_title() {
        let (archive, transport) = archive(FixtureTransport::new());

        let _ = archive.search(&SearchQuery::new("Grateful Dead", 7));

        let url = &transport.requests()[0].url;
        assert!(url.starts_with("https://archive.org/advancedsearch.php?"), "{url}");
        assert!(url.contains("q=%28creator%3A%28grateful%20dead%29%20OR%20title%3A%28grateful%20dead%29%29"), "{url}");
        assert!(url.contains("collection%3A%28etree%20OR%20audio_music%20OR%20netlabels%20OR%2078rpm%29"), "{url}");
        assert!(url.contains("&rows=7&"), "{url}");
        assert!(url.contains("sort%5B%5D=downloads%20desc"), "{url}");
    }

    #[test]
    fn query_of_nothing_but_syntax_asks_nobody() {
        let (archive, transport) = archive(FixtureTransport::new());

        assert_eq!(archive.search(&SearchQuery::new("():*", 10)), Ok(Vec::new()));
        assert!(transport.requests().is_empty());
    }

    #[test]
    fn stream_address_keeps_folders_and_encodes_names() {
        let (archive, transport) = archive(FixtureTransport::new());

        let stream = archive.stream_url(&id("78_oh/disc 1/\"OH DOCTOR\" - H. Pearl.flac")).unwrap();

        assert_eq!(stream.url(), "https://archive.org/download/78_oh/disc%201/%22OH%20DOCTOR%22%20-%20H.%20Pearl.flac");
        assert!(stream.headers().is_empty());
        assert!(transport.requests().is_empty(), "the address is built without the network");
    }

    #[test]
    fn stream_of_something_that_is_not_a_file_is_unavailable() {
        let (archive, _) = archive(FixtureTransport::new());

        for bad in ["just-an-item", "item/", "/file.mp3"] {
            assert!(matches!(archive.stream_url(&id(bad)), Err(CoreError::Unavailable { .. })), "{bad}");
        }
    }

    #[test]
    fn resolve_of_a_track_or_a_variant_gives_that_track() {
        let body = br#"{ "metadata": { "title": "Show" }, "files": [
            { "name": "01.flac", "format": "Flac", "title": "One", "track": "1" },
            { "name": "01.mp3", "format": "VBR MP3", "original": "01.flac" },
            { "name": "02.flac", "format": "Flac", "title": "Two", "track": "2" }
        ] }"#;
        let url = "https://archive.org/metadata/show";
        let (archive, _) = archive(FixtureTransport::new().on(url, [Ok(HttpResponse::ok(&body[..]))]));

        let whole = archive.resolve(&id("show")).unwrap();
        let by_track = archive.resolve(&id("show/02.flac")).unwrap();
        let by_variant = archive.resolve(&id("show/01.mp3")).unwrap();
        let missing = archive.resolve(&id("show/03.flac"));

        assert_eq!(whole.len(), 2);
        assert_eq!(by_track.iter().map(|t| t.title.as_str()).collect::<Vec<_>>(), ["Two"]);
        assert_eq!(by_variant.iter().map(|t| t.title.as_str()).collect::<Vec<_>>(), ["One"]);
        assert!(matches!(missing, Err(CoreError::Unavailable { .. })));
    }

    #[test]
    fn item_without_playable_audio_is_unavailable() {
        let body = br#"{ "metadata": { "title": "Scans" }, "files": [ { "name": "a.jpg", "format": "JPEG" } ] }"#;
        let (archive, _) = archive(
            FixtureTransport::new().on("https://archive.org/metadata/scans", [Ok(HttpResponse::ok(&body[..]))]),
        );

        assert!(matches!(archive.resolve(&id("scans")), Err(CoreError::Unavailable { .. })));
    }

    #[test]
    fn requests_carry_the_config_headers() {
        let (archive, transport) = archive(FixtureTransport::new());

        let _ = archive.resolve(&id("x"));

        let headers = &transport.requests()[0].headers;
        assert!(headers.contains(&("Accept".to_owned(), "application/json".to_owned())), "{headers:?}");
        assert!(
            headers.iter().any(|(name, value)| name == "User-Agent" && value.starts_with("Plinth/")),
            "{headers:?}"
        );
    }
}
