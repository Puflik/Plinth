//! Ю4г (`docs/work/yu4g.md`): поиск у одного провайдера. `Registry::search_in` спрашивает только названного —
//! остальные не получают запроса и не меняются в здоровье, — а срок, отмена и пауза (`Down`) у него те же, что у
//! `Registry::search`. Затравка радио ищется у YouTube, и запрос не уходит в Internet Archive.
//!
//! `Registry::search_in` — новый API (сигнатура в файле задачи): пока его нет, тесты крейта не собираются.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use plinth_providers::http::{Cancel, Limits};
use plinth_providers::model::{
    Capabilities, ExternalId, Health, HealthPolicy, RemoteTrack, ResultKind, SearchQuery, SearchResult, StreamRequest,
};
use plinth_providers::{Provider, Registry};
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

    /// Internet Archive: сильнее по качеству, поэтому реестр ставит его первым.
    fn archive(act: impl Fn() -> Result<Vec<SearchResult>, CoreError> + Send + Sync + 'static) -> Arc<Self> {
        Self::new("archive.org", QualityTier::Lossless, act)
    }

    fn youtube(act: impl Fn() -> Result<Vec<SearchResult>, CoreError> + Send + Sync + 'static) -> Arc<Self> {
        Self::new("youtube", QualityTier::Standard, act)
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
        Capabilities { finds: vec![ResultKind::Track], best_quality: self.best }
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

/// Одна неудача — и провайдер `Down` на минуту: любая лишняя запись в здоровье видна сразу.
const STRICT: HealthPolicy = HealthPolicy { down_after: 1, down_for: Duration::from_secs(60) };

fn registry(providers: &[&Arc<Scripted>]) -> Registry {
    let providers = providers.iter().map(|provider| Arc::clone(provider) as Arc<dyn Provider>).collect();
    Registry::new(providers, STRICT).unwrap()
}

/// Реестр из Internet Archive и YouTube, у которого YouTube отвечает на поиск `answer`.
fn both(answer: Result<Vec<SearchResult>, CoreError>) -> (Registry, Arc<Scripted>) {
    let archive = Scripted::archive(|| Ok(vec![song("ia-1")]));
    let youtube = Scripted::youtube(move || answer.clone());
    (registry(&[&archive, &youtube]), youtube)
}

fn song(video: &str) -> SearchResult {
    SearchResult {
        external: ExternalId::new(video).unwrap(),
        kind: ResultKind::Track,
        title: format!("Song {video}"),
        artist: Some("Band".to_owned()),
        album: None,
        year: None,
        duration: Some(Duration::from_secs(200)),
    }
}

fn query() -> SearchQuery {
    SearchQuery::new("band song", 10)
}

fn within(limits: Limits) -> SearchQuery {
    query().within(limits)
}

fn at(seconds: i64) -> Timestamp {
    Timestamp::from_millis(1_000_000 + seconds * 1000)
}

fn name(text: &str) -> ProviderId {
    ProviderId::new(text).unwrap()
}

#[test]
fn only_the_named_provider_is_asked() {
    let archive = Scripted::archive(|| Ok(vec![song("ia-1")]));
    let youtube = Scripted::youtube(|| Ok(vec![song("yt-1")]));
    let registry = registry(&[&archive, &youtube]);

    let found = registry.search_in(youtube.id(), &query(), at(0)).unwrap();

    assert_eq!(found.provider, *youtube.id());
    assert_eq!(found.outcome, Ok(vec![song("yt-1")]));
    assert_eq!((archive.calls(), youtube.calls()), (0, 1), "Internet Archive gets no request");
}

#[test]
fn the_first_provider_of_the_order_can_be_asked_alone_too() {
    let archive = Scripted::archive(|| Ok(vec![song("ia-1")]));
    let youtube = Scripted::youtube(|| Ok(vec![song("yt-1")]));
    let registry = registry(&[&youtube, &archive]);

    let found = registry.search_in(archive.id(), &query(), at(0)).unwrap();

    assert_eq!(found.provider, *archive.id());
    assert_eq!(found.outcome, Ok(vec![song("ia-1")]));
    assert_eq!((archive.calls(), youtube.calls()), (1, 0));
}

/// Секция одного провайдера — та же, что он даёт в поиске всех: ничего не добавлено и не потеряно.
#[test]
fn the_answer_is_the_one_the_search_of_all_gives_that_provider() {
    for answer in [Ok(vec![song("yt-1"), song("yt-2")]), Ok(Vec::new()), Err(CoreError::network("http 503"))] {
        let (alone_in, youtube) = both(answer.clone());
        let alone = alone_in.search_in(youtube.id(), &query(), at(0)).unwrap();

        let (all_in, youtube) = both(answer.clone());
        let all = all_in.search(&query(), at(0));

        assert_eq!(all.iter().find(|one| one.provider == *youtube.id()), Some(&alone), "{answer:?}");
    }
}

#[test]
fn a_provider_the_registry_does_not_have_gives_nothing_and_nobody_is_asked() {
    let archive = Scripted::archive(|| Ok(vec![song("ia-1")]));
    let youtube = Scripted::youtube(|| Ok(vec![song("yt-1")]));
    let registry = registry(&[&archive, &youtube]);

    assert_eq!(registry.search_in(&name("bandcamp"), &query(), at(0)), None);

    assert_eq!((archive.calls(), youtube.calls()), (0, 0));
}

#[test]
fn a_blank_query_asks_nobody() {
    let youtube = Scripted::youtube(|| Ok(vec![song("yt-1")]));
    let registry = registry(&[&youtube]);

    assert_eq!(registry.search_in(youtube.id(), &SearchQuery::new("  \t ", 10), at(0)), None);

    assert_eq!(youtube.calls(), 0);
}

/// Страж: у других провайдеров поиск одного ничего не пишет в здоровье. Internet Archive, спрошенный хоть раз, ушёл
/// бы в `Down` (`STRICT`); он не спрошен ни разу — и здоров. Последний шаг показывает, что отказывает он по-настоящему.
#[test]
fn the_others_are_not_touched_not_even_their_health() {
    let archive = Scripted::archive(|| Err(CoreError::network("http 503")));
    let youtube = Scripted::youtube(|| Ok(vec![song("yt-1")]));
    let registry = registry(&[&archive, &youtube]);

    for second in 0..3 {
        registry.search_in(youtube.id(), &query(), at(second)).unwrap();
    }

    assert_eq!(archive.calls(), 0);
    assert_eq!(registry.health(archive.id()), Some(Health::Ok));
    assert_eq!(registry.health(youtube.id()), Some(Health::Ok));

    registry.search(&query(), at(3));
    assert_eq!(archive.calls(), 1, "asked by the search of all, it fails");
    assert!(matches!(registry.health(archive.id()), Some(Health::Down { .. })));
}

/// Исход идёт в здоровье спрошенного, как у поиска всех: отказ отправляет его на отдых, отдыхающего не спрашивают, а
/// когда отдых кончился, спрашивают снова и удача возвращает здоровье.
#[test]
fn the_outcome_goes_into_the_health_of_the_asked_provider_and_a_resting_one_is_not_asked() {
    let broken = Arc::new(AtomicBool::new(true));
    let youtube = Scripted::youtube({
        let broken = Arc::clone(&broken);
        move || if broken.load(Ordering::SeqCst) { Err(CoreError::network("http 503")) } else { Ok(vec![song("yt-1")]) }
    });
    let registry = registry(&[&youtube]);

    let first = registry.search_in(youtube.id(), &query(), at(0)).unwrap();
    assert_eq!(first.outcome, Err(CoreError::network("http 503")));
    assert!(matches!(registry.health(youtube.id()), Some(Health::Down { .. })));

    broken.store(false, Ordering::SeqCst);
    let resting = registry.search_in(youtube.id(), &query(), at(30)).unwrap();
    assert!(matches!(resting.outcome, Err(CoreError::Unavailable { .. })), "{resting:?}");
    assert_eq!(youtube.calls(), 1, "a resting provider is not asked");

    let after = registry.search_in(youtube.id(), &query(), at(61)).unwrap();
    assert_eq!(after.outcome, Ok(vec![song("yt-1")]));
    assert_eq!(youtube.calls(), 2);
    assert_eq!(registry.health(youtube.id()), Some(Health::Ok));
}

/// Отменённый во время вопроса поиск — не вина провайдера (иначе три поиска, отменённых новым нажатием «Радио»,
/// отправляли бы YouTube на отдых): секции нет, здоровье цело.
#[test]
fn a_search_in_cancelled_while_asking_is_not_held_against_the_provider() {
    let slot: Arc<Mutex<Option<Cancel>>> = Arc::default();
    let youtube = Scripted::youtube({
        let slot = Arc::clone(&slot);
        move || match slot.lock().unwrap().take() {
            Some(cancel) => {
                cancel.cancel();
                Err(CoreError::network("socket closed"))
            }
            None => Ok(vec![song("yt-1")]),
        }
    });
    let registry = registry(&[&youtube]);

    for _ in 0..3 {
        let cancel = Cancel::new();
        *slot.lock().unwrap() = Some(cancel.clone());
        let found = registry.search_in(youtube.id(), &within(Limits::none().cancelled_by(&cancel)), at(0));
        assert_eq!(found, None, "a cancelled search has nothing to show");
    }

    assert_eq!(registry.health(youtube.id()), Some(Health::Ok));
    let after = registry.search_in(youtube.id(), &within(Limits::none()), at(0)).unwrap();
    assert_eq!(youtube.calls(), 4, "the provider is still asked");
    assert_eq!(after.outcome, Ok(vec![song("yt-1")]));
}

#[test]
fn a_search_in_cancelled_before_it_starts_asks_nobody() {
    let youtube = Scripted::youtube(|| Ok(vec![song("yt-1")]));
    let registry = registry(&[&youtube]);
    let cancel = Cancel::new();
    cancel.cancel();

    let found = registry.search_in(youtube.id(), &within(Limits::none().cancelled_by(&cancel)), at(0));

    assert_eq!(found, None);
    assert_eq!(youtube.calls(), 0);
    assert_eq!(registry.health(youtube.id()), Some(Health::Ok));
}

#[test]
fn a_search_in_with_no_time_at_all_asks_nobody_and_blames_nobody() {
    let youtube = Scripted::youtube(|| Ok(vec![song("yt-1")]));
    let registry = registry(&[&youtube]);

    let found = registry.search_in(youtube.id(), &within(Limits::within(Duration::ZERO)), at(0)).unwrap();

    assert_eq!(found.outcome, Err(CoreError::network("timed out")));
    assert_eq!(youtube.calls(), 0);
    assert_eq!(registry.health(youtube.id()), Some(Health::Ok));
}
