//! Контракт ловит нарушения: фейк с одним изъяном нарушает ровно то
//! правило, которое этот изъян описывает. Иначе зелёный контракт ничего не
//! говорил бы о провайдере.

use std::sync::atomic::{AtomicUsize, Ordering};

use plinth_types::{CoreError, ProviderId, QualityTier};

use super::FakeProvider;
use super::contract::{self, ContractCase, Verdict};
use crate::Provider;
use crate::model::{Capabilities, ExternalId, RemoteTrack, ResultKind, SearchQuery, SearchResult, StreamRequest};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Flaw {
    BlankFindsAll,
    FindsOne,
    IgnoresLimit,
    NothingIsError,
    Unstable,
    UndeclaredKind,
    BlankTitle,
    TrackResolvesToTwo,
    TrackWithoutSources,
    BetterThanDeclared,
    StreamFails,
    GoneResolveIsParse,
    GoneStreamIsParse,
    OfflineIsParse,
}

struct Flawed {
    inner: FakeProvider,
    flaw: Flaw,
    searches: AtomicUsize,
}

impl Flawed {
    fn new(inner: FakeProvider, flaw: Flaw) -> Self {
        Self { inner, flaw, searches: AtomicUsize::new(0) }
    }

    /// `gone` — изъян, который превращает «нет такого» этого метода в `Parse`.
    fn distort<T>(&self, gone: Flaw, outcome: Result<T, CoreError>) -> Result<T, CoreError> {
        match (self.flaw, outcome) {
            (flaw, Err(CoreError::Unavailable { .. })) if flaw == gone => Err(CoreError::parse("flawed")),
            (Flaw::OfflineIsParse, Err(CoreError::Network { .. })) => Err(CoreError::parse("flawed")),
            (_, outcome) => outcome,
        }
    }
}

impl Provider for Flawed {
    fn id(&self) -> &ProviderId {
        self.inner.id()
    }

    fn capabilities(&self) -> Capabilities {
        let mut capabilities = self.inner.capabilities();
        match self.flaw {
            Flaw::UndeclaredKind => capabilities.finds = vec![ResultKind::Album],
            Flaw::BetterThanDeclared => capabilities.best_quality = QualityTier::HighLossy,
            _ => {}
        }
        capabilities
    }

    fn search(&self, query: &SearchQuery) -> Result<Vec<SearchResult>, CoreError> {
        let searches = self.searches.fetch_add(1, Ordering::SeqCst);
        let mut found = match self.flaw {
            Flaw::BlankFindsAll if query.is_blank() => self.inner.search(&SearchQuery::new("fake", query.limit()))?,
            Flaw::IgnoresLimit => self.inner.search(&SearchQuery::new(query.text(), SearchQuery::MAX_LIMIT))?,
            _ => self.distort(Flaw::OfflineIsParse, self.inner.search(query))?,
        };
        match self.flaw {
            Flaw::FindsOne => found.truncate(1),
            Flaw::NothingIsError if found.is_empty() && !query.is_blank() => {
                return Err(CoreError::unavailable("flawed: nothing"));
            }
            Flaw::Unstable if searches % 2 == 1 => found.reverse(),
            Flaw::BlankTitle => found.iter_mut().for_each(|result| result.title = " ".to_owned()),
            _ => {}
        }
        Ok(found)
    }

    fn resolve(&self, item: &ExternalId) -> Result<Vec<RemoteTrack>, CoreError> {
        let mut tracks = self.distort(Flaw::GoneResolveIsParse, self.inner.resolve(item))?;
        match self.flaw {
            Flaw::TrackResolvesToTwo if tracks.len() == 1 => tracks.push(tracks[0].clone()),
            Flaw::TrackWithoutSources => tracks.iter_mut().for_each(|track| track.sources.clear()),
            _ => {}
        }
        Ok(tracks)
    }

    fn stream_url(&self, source: &ExternalId) -> Result<StreamRequest, CoreError> {
        if matches!(self.flaw, Flaw::StreamFails) && source.as_str().ends_with(".mp3") {
            return Err(CoreError::network("flawed: no stream"));
        }
        self.distort(Flaw::GoneStreamIsParse, self.inner.stream_url(source))
    }
}

type Check = fn(&dyn Provider, &ContractCase) -> Verdict;

fn checks() -> [(&'static str, Check); 10] {
    [
        ("blank_query_finds_nothing", |p, _| contract::blank_query_finds_nothing(p)),
        ("search_finds", contract::search_finds),
        ("search_respects_limit", contract::search_respects_limit),
        ("nothing_found_is_empty", contract::nothing_found_is_empty),
        ("search_is_repeatable", contract::search_is_repeatable),
        ("results_are_declared", contract::results_are_declared),
        ("results_resolve", contract::results_resolve),
        ("sources_stream", contract::sources_stream),
        ("gone_is_unavailable", contract::gone_is_unavailable),
        ("offline_is_a_network_error", contract::offline_is_a_network_error),
    ]
}

fn case() -> ContractCase {
    ContractCase::new("fake", "zzz nothing here", "no-such-item")
}

fn fake() -> FakeProvider {
    FakeProvider::sample("fake.test").unwrap()
}

fn offline() -> FakeProvider {
    let fake = fake();
    fake.fail_with(Some(CoreError::network("offline")));
    fake
}

/// Правила, которые нарушает провайдер: `offline_is_a_network_error`
/// проверяется на его отключённой от сети копии.
fn broken_rules(working: &dyn Provider, offline: &dyn Provider) -> Vec<&'static str> {
    checks()
        .into_iter()
        .filter(|(name, check)| {
            let provider = if *name == "offline_is_a_network_error" { offline } else { working };
            check(provider, &case()).is_err()
        })
        .map(|(name, _)| name)
        .collect()
}

#[test]
fn the_fake_keeps_the_contract() {
    assert_eq!(broken_rules(&fake(), &offline()), Vec::<&str>::new());
}

#[test]
fn each_flaw_breaks_its_rule() {
    let cases = [
        // Без сети пустой запрос, ушедший в сеть, ещё и падает.
        (Flaw::BlankFindsAll, vec!["blank_query_finds_nothing", "offline_is_a_network_error"]),
        (Flaw::FindsOne, vec!["search_finds"]),
        (Flaw::IgnoresLimit, vec!["search_respects_limit"]),
        (Flaw::NothingIsError, vec!["nothing_found_is_empty"]),
        (Flaw::Unstable, vec!["search_is_repeatable"]),
        (Flaw::UndeclaredKind, vec!["results_are_declared"]),
        (Flaw::BlankTitle, vec!["results_are_declared"]),
        (Flaw::TrackResolvesToTwo, vec!["results_resolve"]),
        (Flaw::TrackWithoutSources, vec!["results_resolve"]),
        (Flaw::BetterThanDeclared, vec!["results_resolve"]),
        (Flaw::StreamFails, vec!["sources_stream"]),
        (Flaw::GoneResolveIsParse, vec!["gone_is_unavailable"]),
        (Flaw::GoneStreamIsParse, vec!["gone_is_unavailable"]),
        (Flaw::OfflineIsParse, vec!["offline_is_a_network_error"]),
    ];

    for (flaw, broken) in cases {
        let working = Flawed::new(fake(), flaw);
        let offline = Flawed::new(offline(), flaw);

        assert_eq!(broken_rules(&working, &offline), broken, "{flaw:?}");
    }
}
