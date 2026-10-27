//! Ю4а (`docs/work/yu4a.md`): «радио» — способность провайдера. Метод `Provider::radio` по умолчанию
//! «не умеет», `Registry::radio` спрашивает названного провайдера и считает исход в его здоровье, как
//! `resolve`. `RadioPage`, `Provider::radio` и `Registry::radio` — новый API (сигнатуры в файле задачи):
//! пока их нет, тесты крейта не собираются.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

use std::sync::{Arc, Mutex};
use std::time::Duration;

use plinth_providers::model::{
    Capabilities, ExternalId, Health, HealthPolicy, RadioPage, RemoteTrack, ResultKind, SearchQuery, SearchResult,
    SourceOption, StreamRequest,
};
use plinth_providers::{Provider, Registry};
use plinth_types::{Bitrate, CoreError, Format, ProviderId, QualityTier, Timestamp};

/// Затравка, которую знает `Radio`.
const SEED: &str = "dQw4w9WgXcQ";
/// Ключ второй страницы.
const KEY: &str = "page-2";

fn capabilities() -> Capabilities {
    Capabilities { finds: vec![ResultKind::Track], best_quality: QualityTier::Standard }
}

/// Провайдер, который радио не умеет: метод по умолчанию.
struct Silent {
    id: ProviderId,
}

impl Provider for Silent {
    fn id(&self) -> &ProviderId {
        &self.id
    }

    fn capabilities(&self) -> Capabilities {
        capabilities()
    }

    fn search(&self, _: &SearchQuery) -> Result<Vec<SearchResult>, CoreError> {
        Ok(Vec::new())
    }

    fn resolve(&self, _: &ExternalId) -> Result<Vec<RemoteTrack>, CoreError> {
        Ok(Vec::new())
    }

    fn stream_url(&self, _: &ExternalId) -> Result<StreamRequest, CoreError> {
        Err(CoreError::unavailable("none"))
    }
}

/// Провайдер с радио: по затравке `SEED` — две страницы (с ключом `KEY` между ними); `fail(true)` — сбой сети.
struct Radio {
    id: ProviderId,
    calls: Mutex<Vec<String>>,
    failing: Mutex<bool>,
}

impl Radio {
    fn new(name: &str) -> Arc<Self> {
        Arc::new(Self { id: id_of(name), calls: Mutex::new(Vec::new()), failing: Mutex::new(false) })
    }

    fn fail(&self, failing: bool) {
        *self.failing.lock().unwrap() = failing;
    }

    fn calls(&self) -> Vec<String> {
        self.calls.lock().unwrap().clone()
    }
}

impl Provider for Radio {
    fn id(&self) -> &ProviderId {
        &self.id
    }

    fn capabilities(&self) -> Capabilities {
        capabilities()
    }

    fn search(&self, _: &SearchQuery) -> Result<Vec<SearchResult>, CoreError> {
        Ok(Vec::new())
    }

    fn resolve(&self, _: &ExternalId) -> Result<Vec<RemoteTrack>, CoreError> {
        Ok(Vec::new())
    }

    fn stream_url(&self, _: &ExternalId) -> Result<StreamRequest, CoreError> {
        Err(CoreError::unavailable("none"))
    }

    fn radio(&self, seed: &ExternalId, page: Option<&ExternalId>) -> Result<RadioPage, CoreError> {
        let key = page.map_or_else(|| "-".to_owned(), ToString::to_string);
        self.calls.lock().unwrap().push(format!("{seed}:{key}"));
        if *self.failing.lock().unwrap() {
            return Err(CoreError::network("http 503"));
        }
        match (seed.as_str(), page.map(ExternalId::as_str)) {
            (SEED, None) => {
                Ok(RadioPage { tracks: vec![track("aaaaaaaaaaa"), track("bbbbbbbbbbb")], next: Some(external(KEY)) })
            }
            (SEED, Some(KEY)) => Ok(RadioPage { tracks: vec![track("ccccccccccc")], next: None }),
            _ => Err(CoreError::unavailable("no such seed")),
        }
    }
}

// --- Сам метод ------------------------------------------------------------------------

#[test]
fn a_provider_that_does_not_do_radio_says_unavailable() {
    let silent = Silent { id: id_of("silent.test") };

    let answer = silent.radio(&external(SEED), None);

    assert!(matches!(answer, Err(CoreError::Unavailable { .. })), "{answer:?}");
}

// --- Реестр ---------------------------------------------------------------------------

#[test]
fn the_registry_does_not_blame_a_provider_for_not_doing_radio() {
    let silent = id_of("silent.test");
    let registry = Registry::new(vec![Arc::new(Silent { id: silent.clone() })], HealthPolicy::default()).unwrap();

    for second in 0..5 {
        let answer = registry.radio(&silent, &external(SEED), None, at(second));
        assert!(matches!(answer, Err(CoreError::Unavailable { .. })), "{answer:?}");
    }

    assert!(matches!(registry.health(&silent), Some(Health::Ok)), "Unavailable провайдеру не в укор");
}

#[test]
fn the_registry_asks_the_named_provider_and_hands_the_pages_over() {
    let radio = Radio::new("radio.test");
    let registry = registry_of(&radio);

    let first = registry.radio(&radio.id, &external(SEED), None, at(0)).unwrap();
    let key = first.next.clone().expect("the first page has a next one");
    let second = registry.radio(&radio.id, &external(SEED), Some(&key), at(1)).unwrap();

    assert_eq!(first.tracks, vec![track("aaaaaaaaaaa"), track("bbbbbbbbbbb")]);
    assert_eq!(key, external(KEY));
    assert_eq!(second.tracks, vec![track("ccccccccccc")]);
    assert_eq!(second.next, None, "последняя страница ключа не даёт");
    assert_eq!(radio.calls(), [format!("{SEED}:-"), format!("{SEED}:{KEY}")], "затравка и ключ — дословно");
}

#[test]
fn a_stranger_is_unavailable_and_nobody_is_asked() {
    let radio = Radio::new("radio.test");
    let registry = registry_of(&radio);

    let answer = registry.radio(&id_of("stranger.test"), &external(SEED), None, at(0));

    assert!(matches!(answer, Err(CoreError::Unavailable { .. })), "{answer:?}");
    assert!(radio.calls().is_empty());
}

#[test]
fn radio_failures_count_against_the_provider_but_a_resting_one_is_still_asked() {
    let radio = Radio::new("radio.test");
    radio.fail(true);
    let registry = registry_of(&radio);

    for second in 0..3 {
        let answer = registry.radio(&radio.id, &external(SEED), None, at(second));
        assert!(matches!(answer, Err(CoreError::Network { .. })), "{answer:?}");
    }
    assert!(
        matches!(registry.health(&radio.id), Some(health) if health.is_down_at(at(3))),
        "три сбоя подряд — отдых провайдера"
    );

    radio.fail(false);
    let page = registry.radio(&radio.id, &external(SEED), None, at(4)).unwrap();

    assert_eq!(page.tracks.len(), 2);
    assert_eq!(radio.calls().len(), 4, "отдыхающего спрашивают: это действие человека над треком");
    assert!(matches!(registry.health(&radio.id), Some(Health::Ok)), "удача лечит сразу");
}

// --- Помощники ------------------------------------------------------------------------

fn registry_of(radio: &Arc<Radio>) -> Registry {
    Registry::new(vec![radio.clone()], HealthPolicy::default()).unwrap()
}

fn track(video: &str) -> RemoteTrack {
    RemoteTrack {
        external: external(video),
        title: format!("Song {video}"),
        artist: Some("Artist".to_owned()),
        album: None,
        disc: None,
        number: None,
        year: None,
        duration: Some(Duration::from_secs(200)),
        mbid: None,
        sources: vec![SourceOption {
            external: external(&format!("{video}/251")),
            format: Format::Opus,
            bitrate: Some(Bitrate::kbps(160)),
        }],
    }
}

fn external(text: &str) -> ExternalId {
    ExternalId::new(text).unwrap()
}

fn id_of(name: &str) -> ProviderId {
    ProviderId::new(name).unwrap()
}

fn at(seconds: i64) -> Timestamp {
    Timestamp::from_millis(seconds * 1000)
}
