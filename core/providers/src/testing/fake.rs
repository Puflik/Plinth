use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Mutex, PoisonError};
use std::time::Duration;

use plinth_types::{Bitrate, CoreError, Format, ProviderId, QualityTier};

use crate::Provider;
use crate::http::encode_component;
use crate::model::{
    Capabilities, ExternalId, RemoteTrack, ResultKind, SearchQuery, SearchResult, SourceOption, StreamRequest,
};

/// Провайдер в памяти — для контракта, реестра и тестов тех, кто зовёт
/// провайдеров. Ищет подстроку без учёта регистра в названии, исполнителе
/// и названиях треков. Умеет отказывать по команде и считает обращения «в
/// сеть» — всё, кроме пустого поиска и `id`/`capabilities`.
pub struct FakeProvider {
    id: ProviderId,
    capabilities: Capabilities,
    items: Vec<(SearchResult, Vec<RemoteTrack>)>,
    failure: Mutex<Option<CoreError>>,
    requests: AtomicUsize,
}

impl FakeProvider {
    pub fn new(id: &str, capabilities: Capabilities) -> Result<Self, CoreError> {
        Ok(Self {
            id: ProviderId::new(id)?,
            capabilities,
            items: Vec::new(),
            failure: Mutex::new(None),
            requests: AtomicUsize::new(0),
        })
    }

    pub fn with_item(mut self, result: SearchResult, tracks: Vec<RemoteTrack>) -> Self {
        self.items.push((result, tracks));
        self
    }

    /// Каталог для контракта: концерт «The Fakes» из двух треков (FLAC и
    /// MP3 320) и отдельный трек без исполнителя (MP3 128). По «fake»
    /// находится и то и другое.
    pub fn sample(id: &str) -> Result<Self, CoreError> {
        let capabilities =
            Capabilities { finds: vec![ResultKind::Album, ResultKind::Track], best_quality: QualityTier::Lossless };
        let hall = SearchResult {
            external: ExternalId::new("fake-hall-1977")?,
            kind: ResultKind::Album,
            title: "Live at Fake Hall 1977".to_owned(),
            artist: Some("The Fakes".to_owned()),
            album: None,
            year: Some(1977),
            duration: None,
        };
        let hall_tracks = vec![
            concert_track("fake-hall-1977/01", "Opening Jam", 1, 300)?,
            concert_track("fake-hall-1977/02", "Кино на бис", 2, 240)?,
        ];
        let single = SearchResult {
            external: ExternalId::new("single-42")?,
            kind: ResultKind::Track,
            title: "Fake Solo Take".to_owned(),
            artist: None,
            album: None,
            year: None,
            duration: Some(Duration::from_secs(180)),
        };
        let single_track = RemoteTrack {
            external: ExternalId::new("single-42")?,
            title: "Fake Solo Take".to_owned(),
            artist: None,
            album: None,
            disc: None,
            number: None,
            year: None,
            duration: Some(Duration::from_secs(180)),
            mbid: None,
            sources: vec![source("single-42.mp3", Format::Mp3, Some(128))?],
        };
        Ok(Self::new(id, capabilities)?.with_item(hall, hall_tracks).with_item(single, vec![single_track]))
    }

    /// Дальше каждое обращение в сеть — эта ошибка; `None` — снова работает.
    pub fn fail_with(&self, error: Option<CoreError>) {
        *self.failure.lock().unwrap_or_else(PoisonError::into_inner) = error;
    }

    /// Сколько раз провайдер обратился бы в сеть.
    pub fn requests(&self) -> usize {
        self.requests.load(Ordering::SeqCst)
    }

    fn request(&self) -> Result<(), CoreError> {
        self.requests.fetch_add(1, Ordering::SeqCst);
        match self.failure.lock().unwrap_or_else(PoisonError::into_inner).as_ref() {
            Some(error) => Err(error.clone()),
            None => Ok(()),
        }
    }

    fn tracks(&self) -> impl Iterator<Item = &RemoteTrack> {
        self.items.iter().flat_map(|(_, tracks)| tracks)
    }
}

impl Provider for FakeProvider {
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
        self.request()?;
        let needle = query.text().to_lowercase();
        let hit = |text: &str| text.to_lowercase().contains(&needle);
        let limit = usize::try_from(query.limit()).unwrap_or(usize::MAX);
        Ok(self
            .items
            .iter()
            .filter(|(result, tracks)| {
                hit(&result.title)
                    || result.artist.as_deref().is_some_and(hit)
                    || tracks.iter().any(|track| hit(&track.title))
            })
            .map(|(result, _)| result.clone())
            .take(limit)
            .collect())
    }

    fn resolve(&self, item: &ExternalId) -> Result<Vec<RemoteTrack>, CoreError> {
        self.request()?;
        if let Some((_, tracks)) = self.items.iter().find(|(result, _)| &result.external == item) {
            return Ok(tracks.clone());
        }
        match self.tracks().find(|track| &track.external == item) {
            Some(track) => Ok(vec![track.clone()]),
            None => Err(CoreError::unavailable("fake: no such item")),
        }
    }

    fn stream_url(&self, source: &ExternalId) -> Result<StreamRequest, CoreError> {
        self.request()?;
        if !self.tracks().flat_map(|track| &track.sources).any(|option| &option.external == source) {
            return Err(CoreError::unavailable("fake: no such source"));
        }
        StreamRequest::new(&format!("https://{}/stream/{}", self.id, encode_component(source.as_str())), Vec::new())
    }
}

fn concert_track(external: &str, title: &str, number: u16, seconds: u64) -> Result<RemoteTrack, CoreError> {
    Ok(RemoteTrack {
        external: ExternalId::new(external)?,
        title: title.to_owned(),
        artist: Some("The Fakes".to_owned()),
        album: Some("Live at Fake Hall 1977".to_owned()),
        disc: None,
        number: Some(number),
        year: Some(1977),
        duration: Some(Duration::from_secs(seconds)),
        mbid: None,
        sources: vec![
            source(&format!("{external}.flac"), Format::Flac, None)?,
            source(&format!("{external}.mp3"), Format::Mp3, Some(320))?,
        ],
    })
}

fn source(external: &str, format: Format, kbps: Option<u32>) -> Result<SourceOption, CoreError> {
    Ok(SourceOption { external: ExternalId::new(external)?, format, bitrate: kbps.map(Bitrate::kbps) })
}

#[cfg(test)]
mod tests {
    use plinth_types::CoreError;

    use super::FakeProvider;
    use crate::Provider;
    use crate::model::{ExternalId, SearchQuery};

    #[test]
    fn finds_by_title_artist_and_track_regardless_of_case() {
        let fake = FakeProvider::sample("fake.test").unwrap();
        let titles = |text: &str| -> Vec<String> {
            fake.search(&SearchQuery::new(text, 10)).unwrap().into_iter().map(|r| r.title).collect()
        };

        assert_eq!(titles("FAKE"), ["Live at Fake Hall 1977", "Fake Solo Take"]);
        assert_eq!(titles("the fakes"), ["Live at Fake Hall 1977"]);
        assert_eq!(titles("кино"), ["Live at Fake Hall 1977"]);
        assert!(titles("nothing like this").is_empty());
    }

    #[test]
    fn stream_address_names_the_source() {
        let fake = FakeProvider::sample("fake.test").unwrap();

        let stream = fake.stream_url(&ExternalId::new("fake-hall-1977/02.flac").unwrap()).unwrap();

        assert_eq!(stream.url(), "https://fake.test/stream/fake-hall-1977%2F02.flac");
    }

    #[test]
    fn counts_requests_and_fails_on_command() {
        let fake = FakeProvider::sample("fake.test").unwrap();
        fake.search(&SearchQuery::new("  ", 10)).unwrap();
        assert_eq!(fake.requests(), 0);

        fake.fail_with(Some(CoreError::network("offline")));
        assert_eq!(fake.search(&SearchQuery::new("fake", 10)), Err(CoreError::network("offline")));
        assert_eq!(fake.requests(), 1);

        fake.fail_with(None);
        assert_eq!(fake.resolve(&ExternalId::new("single-42").unwrap()).map(|tracks| tracks.len()), Ok(1));
        assert_eq!(fake.requests(), 2);
    }
}
