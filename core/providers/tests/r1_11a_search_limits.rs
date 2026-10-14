//! Р1.11а (`docs/work/r1-11a.md`, находка 10 ревью v0.2): срок и отмена поиска
//! в реестре. Отменённый поиск — не отказ провайдера (иначе три поиска,
//! отменённых набором следующей буквы, отправляли бы Internet Archive в
//! `Down`), а не успевший по сроку провайдер, которого не спросили, — не его вина.
//!
//! `SearchQuery::within` и `SearchQuery::limits` — новый API (сигнатуры в
//! `docs/work/r1-11a.md`): пока их нет, тесты крейта не собираются.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use plinth_providers::http::{Cancel, Limits};
use plinth_providers::model::{
    Capabilities, ExternalId, Health, HealthPolicy, RemoteTrack, ResultKind, SearchQuery, SearchResult, StreamRequest,
};
use plinth_providers::testing::FixtureTransport;
use plinth_providers::{InternetArchive, Provider, Registry};
use plinth_types::{CoreError, ProviderId, QualityTier, Timestamp};

type Act = Box<dyn Fn() -> Result<Vec<SearchResult>, CoreError> + Send + Sync>;

/// Провайдер, у которого поиск делает `act`; считает, сколько раз его спросили.
struct Scripted {
    id: ProviderId,
    best: QualityTier,
    calls: AtomicUsize,
    act: Act,
}

impl Scripted {
    fn new(
        name: &str,
        best: QualityTier,
        act: impl Fn() -> Result<Vec<SearchResult>, CoreError> + Send + Sync + 'static,
    ) -> Arc<Self> {
        Arc::new(Self { id: ProviderId::new(name).unwrap(), best, calls: AtomicUsize::new(0), act: Box::new(act) })
    }

    fn calls(&self) -> usize {
        self.calls.load(Ordering::SeqCst)
    }
}

impl Provider for Scripted {
    fn id(&self) -> &ProviderId {
        &self.id
    }

    fn capabilities(&self) -> Capabilities {
        Capabilities { finds: vec![ResultKind::Album], best_quality: self.best }
    }

    fn search(&self, _: &SearchQuery) -> Result<Vec<SearchResult>, CoreError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        (self.act)()
    }

    fn resolve(&self, _: &ExternalId) -> Result<Vec<RemoteTrack>, CoreError> {
        Err(CoreError::unavailable("scripted"))
    }

    fn stream_url(&self, _: &ExternalId) -> Result<StreamRequest, CoreError> {
        Err(CoreError::unavailable("scripted"))
    }
}

/// Одна неудача — и провайдер `Down`: любая лишняя запись в здоровье видна сразу.
const STRICT: HealthPolicy = HealthPolicy { down_after: 1, down_for: Duration::from_secs(60) };

fn registry(providers: &[&Arc<Scripted>]) -> Registry {
    let providers = providers.iter().map(|provider| Arc::clone(provider) as Arc<dyn Provider>).collect();
    Registry::new(providers, STRICT).unwrap()
}

fn query(limits: Limits) -> SearchQuery {
    SearchQuery::new("dead", 10).within(limits)
}

fn now() -> Timestamp {
    Timestamp::from_millis(1_000_000)
}

#[test]
fn a_search_cancelled_while_asking_is_not_held_against_the_provider() {
    let slot: Arc<Mutex<Option<Cancel>>> = Arc::default();
    let provider = Scripted::new("archive.org", QualityTier::Lossless, {
        let slot = Arc::clone(&slot);
        move || match slot.lock().unwrap().take() {
            Some(cancel) => {
                cancel.cancel();
                Err(CoreError::network("socket closed"))
            }
            None => Ok(Vec::new()),
        }
    });
    let registry = registry(&[&provider]);

    for _ in 0..3 {
        let cancel = Cancel::new();
        *slot.lock().unwrap() = Some(cancel.clone());
        let sections = registry.search(&query(Limits::none().cancelled_by(&cancel)), now());
        assert!(sections.is_empty(), "a cancelled search has nothing to show: {sections:?}");
    }

    assert_eq!(registry.health(provider.id()), Some(Health::Ok));
    let after = registry.search(&query(Limits::none()), now());
    assert_eq!(provider.calls(), 4, "the provider is still asked");
    assert_eq!(after.len(), 1);
    assert_eq!(after[0].outcome, Ok(Vec::new()));
}

#[test]
fn a_search_cancelled_before_it_starts_asks_nobody() {
    let provider = Scripted::new("archive.org", QualityTier::Lossless, || Ok(Vec::new()));
    let registry = registry(&[&provider]);
    let cancel = Cancel::new();
    cancel.cancel();

    let sections = registry.search(&query(Limits::none().cancelled_by(&cancel)), now());

    assert!(sections.is_empty());
    assert_eq!(provider.calls(), 0);
    assert_eq!(registry.health(provider.id()), Some(Health::Ok));
}

#[test]
fn cancelled_in_the_first_provider_the_next_is_not_asked() {
    let cancel = Cancel::new();
    let first = Scripted::new("archive.org", QualityTier::Lossless, {
        let cancel = cancel.clone();
        move || {
            cancel.cancel();
            Ok(Vec::new())
        }
    });
    let second = Scripted::new("other", QualityTier::Standard, || Ok(Vec::new()));
    let registry = registry(&[&first, &second]);

    let sections = registry.search(&query(Limits::none().cancelled_by(&cancel)), now());

    assert!(sections.is_empty(), "{sections:?}");
    assert_eq!((first.calls(), second.calls()), (1, 0));
}

#[test]
fn a_spent_budget_is_nobodys_fault() {
    let first = Scripted::new("archive.org", QualityTier::Lossless, || {
        std::thread::sleep(Duration::from_millis(250));
        Ok(Vec::new())
    });
    let second = Scripted::new("other", QualityTier::Standard, || Ok(Vec::new()));
    let registry = registry(&[&first, &second]);

    let sections = registry.search(&query(Limits::within(Duration::from_millis(100))), now());

    assert_eq!(sections.len(), 2);
    assert_eq!(sections[0].outcome, Ok(Vec::new()));
    assert_eq!(sections[1].provider, *second.id());
    assert_eq!(sections[1].outcome, Err(CoreError::network("timed out")));
    assert_eq!(second.calls(), 0, "no time left: not asked");
    assert_eq!(registry.health(second.id()), Some(Health::Ok), "and not blamed");
    assert_eq!(registry.health(first.id()), Some(Health::Ok));
}

#[test]
fn a_search_with_no_time_at_all_asks_nobody() {
    let provider = Scripted::new("archive.org", QualityTier::Lossless, || Ok(Vec::new()));
    let registry = registry(&[&provider]);

    let sections = registry.search(&query(Limits::within(Duration::ZERO)), now());

    assert_eq!(sections.len(), 1);
    assert_eq!(sections[0].outcome, Err(CoreError::network("timed out")));
    assert_eq!(provider.calls(), 0);
    assert_eq!(registry.health(provider.id()), Some(Health::Ok));
}

/// Страж: без срока и отмены поиск идёт как раньше, отказ засчитывается.
#[test]
fn an_unlimited_search_works_as_before() {
    let provider = Scripted::new("archive.org", QualityTier::Lossless, || Err(CoreError::network("http 503")));
    let registry = registry(&[&provider]);

    let sections = registry.search(&SearchQuery::new("dead", 10), now());

    assert_eq!(sections[0].outcome, Err(CoreError::network("http 503")));
    assert!(matches!(registry.health(provider.id()), Some(Health::Down { .. })));
}

#[test]
fn internet_archive_hands_the_budget_to_the_network() {
    let transport = Arc::new(FixtureTransport::new());
    let archive = InternetArchive::new(transport.clone()).unwrap().with_pause(|_| {});

    let _ = archive.search(&query(Limits::within(Duration::from_secs(2))));

    let timeouts: Vec<Duration> = transport.requests().iter().map(|request| request.timeout).collect();
    assert!(!timeouts.is_empty());
    assert!(timeouts.iter().all(|timeout| *timeout <= Duration::from_secs(2)), "{timeouts:?}");
}

#[test]
fn internet_archive_cancelled_asks_the_network_nothing() {
    let transport = Arc::new(FixtureTransport::new());
    let archive = InternetArchive::new(transport.clone()).unwrap().with_pause(|_| {});
    let cancel = Cancel::new();
    cancel.cancel();

    let result = archive.search(&query(Limits::none().cancelled_by(&cancel)));

    assert_eq!(result, Err(CoreError::network("cancelled")));
    assert!(transport.requests().is_empty());
}
