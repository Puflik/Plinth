use std::collections::BTreeMap;
use std::sync::{Arc, Mutex, PoisonError};

use plinth_types::{CoreError, ProviderId, Timestamp};

use crate::Provider;
use crate::model::{ExternalId, Health, HealthPolicy, RemoteTrack, SearchQuery, SearchResult, StreamRequest};

/// Ответ одного провайдера на поиск.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProviderSearch {
    pub provider: ProviderId,
    /// `Down` — `Unavailable` без обращения к провайдеру.
    pub outcome: Result<Vec<SearchResult>, CoreError>,
}

/// Провайдеры по порядку и их здоровье (E1.1, plan.md 6.4).
///
/// - порядок — по лучшему качеству, при равном — как зарегистрированы;
///   скорость ответа добавится, когда провайдеров станет больше одного;
/// - поиск спрашивает всех, кроме `Down`, по одному; отказ одного не прячет
///   ответы остальных;
/// - `resolve` и `stream` — действие человека над конкретным треком: их
///   провайдер спрашивается и в `Down`, только в `stream` — последним;
/// - здоровье — по исходам вызовов ([`Health::after`]); замок держится
///   только на чтение и запись здоровья, не на время вызова провайдера.
pub struct Registry {
    providers: Vec<Arc<dyn Provider>>,
    health: Mutex<BTreeMap<ProviderId, Health>>,
    policy: HealthPolicy,
}

impl Registry {
    /// Один провайдер дважды — ошибка сборки реестра (`Internal`).
    pub fn new(mut providers: Vec<Arc<dyn Provider>>, policy: HealthPolicy) -> Result<Self, CoreError> {
        let mut health = BTreeMap::new();
        for provider in &providers {
            if health.insert(provider.id().clone(), Health::Ok).is_some() {
                return Err(CoreError::internal(format!("provider {} registered twice", provider.id())));
            }
        }
        providers.sort_by_key(|provider| std::cmp::Reverse(provider.capabilities().best_quality));
        Ok(Self { providers, health: Mutex::new(health), policy })
    }

    pub fn order(&self) -> Vec<ProviderId> {
        self.providers.iter().map(|provider| provider.id().clone()).collect()
    }

    /// `None` — такого провайдера нет.
    pub fn health(&self, provider: &ProviderId) -> Option<Health> {
        self.health.lock().unwrap_or_else(PoisonError::into_inner).get(provider).copied()
    }

    /// Ответ каждого провайдера по порядку; пустой запрос никого не спрашивает.
    pub fn search(&self, query: &SearchQuery, now: Timestamp) -> Vec<ProviderSearch> {
        if query.is_blank() {
            return Vec::new();
        }
        self.providers
            .iter()
            .map(|provider| {
                let id = provider.id().clone();
                let outcome = if self.is_down(&id, now) {
                    Err(CoreError::unavailable(format!("provider {id} is down")))
                } else {
                    self.observe(provider.as_ref(), now, provider.search(query))
                };
                ProviderSearch { provider: id, outcome }
            })
            .collect()
    }

    pub fn resolve(
        &self,
        provider: &ProviderId,
        item: &ExternalId,
        now: Timestamp,
    ) -> Result<Vec<RemoteTrack>, CoreError> {
        let provider = self.find(provider)?;
        self.observe(provider.as_ref(), now, provider.resolve(item))
    }

    /// Адрес потока первого источника, который ответил (plan.md 6.4: отказ —
    /// к следующему). Источники — по порядку провайдеров, `Down` — в конце,
    /// незнакомые провайдеры пропускаются. Отдаёт индекс выбранного в
    /// `candidates`; все отказали — последняя ошибка.
    pub fn stream(
        &self,
        candidates: &[(ProviderId, ExternalId)],
        now: Timestamp,
    ) -> Result<(usize, StreamRequest), CoreError> {
        let mut ordered: Vec<(bool, usize, usize, &Arc<dyn Provider>)> = candidates
            .iter()
            .enumerate()
            .filter_map(|(index, (id, _))| {
                let rank = self.providers.iter().position(|provider| provider.id() == id)?;
                Some((self.is_down(id, now), rank, index, &self.providers[rank]))
            })
            .collect();
        ordered.sort_by_key(|&(down, rank, index, _)| (down, rank, index));
        let mut last = CoreError::unavailable("no source to stream");
        for (_, _, index, provider) in ordered {
            // Адрес потока строится без сети: удача ничего не говорит о живости
            // провайдера и не снимает бан, неудача — говорит.
            match provider.stream_url(&candidates[index].1) {
                Ok(stream) => return Ok((index, stream)),
                Err(error) => {
                    if let Err(observed) = self.observe::<StreamRequest>(provider.as_ref(), now, Err(error)) {
                        last = observed;
                    }
                }
            }
        }
        Err(last)
    }

    fn find(&self, id: &ProviderId) -> Result<&Arc<dyn Provider>, CoreError> {
        self.providers
            .iter()
            .find(|provider| provider.id() == id)
            .ok_or_else(|| CoreError::unavailable(format!("unknown provider {id}")))
    }

    fn is_down(&self, id: &ProviderId, now: Timestamp) -> bool {
        self.health(id).is_some_and(|health| health.is_down_at(now))
    }

    /// Записывает исход вызова в здоровье и отдаёт его дальше.
    fn observe<T>(
        &self,
        provider: &dyn Provider,
        now: Timestamp,
        outcome: Result<T, CoreError>,
    ) -> Result<T, CoreError> {
        let mut health = self.health.lock().unwrap_or_else(PoisonError::into_inner);
        let entry = health.entry(provider.id().clone()).or_insert(Health::Ok);
        let before = *entry;
        *entry = before.after(outcome.as_ref().map(|_| ()), self.policy, now);
        match (before, *entry) {
            (Health::Ok | Health::Degraded { .. }, Health::Down { failures, .. }) => {
                log::warn!("provider {}: down for {:?} after {failures} failures", provider.id(), self.policy.down_for);
            }
            (Health::Degraded { .. } | Health::Down { .. }, Health::Ok) => {
                log::info!("provider {}: healthy again", provider.id());
            }
            _ => {}
        }
        outcome
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::time::Duration;

    use plinth_types::{CoreError, ProviderId, QualityTier, Timestamp};

    use super::{ProviderSearch, Registry};
    use crate::Provider;
    use crate::model::{Capabilities, ExternalId, Health, HealthPolicy, ResultKind, SearchQuery};
    use crate::testing::FakeProvider;

    const POLICY: HealthPolicy = HealthPolicy { down_after: 2, down_for: Duration::from_secs(60) };

    fn at(seconds: i64) -> Timestamp {
        Timestamp::from_millis(seconds * 1000)
    }

    fn id(name: &str) -> ProviderId {
        ProviderId::new(name).unwrap()
    }

    fn external(text: &str) -> ExternalId {
        ExternalId::new(text).unwrap()
    }

    fn query(text: &str) -> SearchQuery {
        SearchQuery::new(text, 10)
    }

    /// Пустой провайдер с объявленным качеством.
    fn empty(name: &str, best: QualityTier) -> Arc<FakeProvider> {
        let capabilities = Capabilities { finds: vec![ResultKind::Album], best_quality: best };
        Arc::new(FakeProvider::new(name, capabilities).unwrap())
    }

    fn sample(name: &str) -> Arc<FakeProvider> {
        Arc::new(FakeProvider::sample(name).unwrap())
    }

    fn registry(providers: &[Arc<FakeProvider>]) -> Registry {
        let providers = providers.iter().map(|p| Arc::clone(p) as Arc<dyn Provider>).collect();
        Registry::new(providers, POLICY).unwrap()
    }

    fn titles(search: &ProviderSearch) -> Vec<String> {
        search.outcome.as_ref().unwrap().iter().map(|result| result.title.clone()).collect()
    }

    #[test]
    fn best_quality_first_then_as_registered() {
        let registry = registry(&[
            empty("soundcloud", QualityTier::Standard),
            empty("bandcamp", QualityTier::HighLossy),
            empty("youtube", QualityTier::Standard),
            empty("archive.org", QualityTier::Lossless),
        ]);

        assert_eq!(registry.order(), [id("archive.org"), id("bandcamp"), id("soundcloud"), id("youtube")]);
    }

    #[test]
    fn the_same_provider_twice_is_a_bug() {
        let twice: Vec<Arc<dyn Provider>> = vec![sample("fake.test"), sample("fake.test")];

        assert!(matches!(Registry::new(twice, POLICY), Err(CoreError::Internal { .. })));
    }

    #[test]
    fn search_asks_every_provider_in_order() {
        let registry = registry(&[empty("empty.test", QualityTier::Standard), sample("fake.test")]);

        let found = registry.search(&query("fake"), at(0));

        assert_eq!(found.len(), 2);
        assert_eq!(found[0].provider, id("fake.test"));
        assert_eq!(titles(&found[0]), ["Live at Fake Hall 1977", "Fake Solo Take"]);
        assert_eq!(found[1], ProviderSearch { provider: id("empty.test"), outcome: Ok(Vec::new()) });
    }

    #[test]
    fn blank_query_asks_nobody() {
        let fake = sample("fake.test");
        let registry = registry(&[Arc::clone(&fake)]);

        assert!(registry.search(&query("  "), at(0)).is_empty());
        assert_eq!(fake.requests(), 0);
    }

    #[test]
    fn one_failing_provider_does_not_hide_the_others() {
        let broken = sample("broken.test");
        broken.fail_with(Some(CoreError::network("offline")));
        let registry = registry(&[broken, sample("fake.test")]);

        let found = registry.search(&query("fake"), at(0));

        assert_eq!(found[0].outcome, Err(CoreError::network("offline")));
        assert_eq!(titles(&found[1]).len(), 2);
        assert_eq!(registry.health(&id("broken.test")), Some(Health::Degraded { failures: 1 }));
        assert_eq!(registry.health(&id("fake.test")), Some(Health::Ok));
    }

    #[test]
    fn down_provider_is_left_out_of_search_until_the_deadline() {
        let fake = sample("fake.test");
        fake.fail_with(Some(CoreError::parse("html instead of json")));
        let registry = registry(&[Arc::clone(&fake)]);
        registry.search(&query("fake"), at(0));
        registry.search(&query("fake"), at(1));
        assert_eq!(registry.health(&id("fake.test")), Some(Health::Down { failures: 2, until: at(61) }));

        let while_down = registry.search(&query("fake"), at(30));

        assert!(matches!(while_down[0].outcome, Err(CoreError::Unavailable { .. })));
        assert_eq!(fake.requests(), 2);

        fake.fail_with(None);
        let after = registry.search(&query("fake"), at(61));

        assert_eq!(titles(&after[0]).len(), 2);
        assert_eq!(fake.requests(), 3);
        assert_eq!(registry.health(&id("fake.test")), Some(Health::Ok));
    }

    #[test]
    fn unknown_health_before_the_first_call() {
        let registry = registry(&[sample("fake.test")]);

        assert_eq!(registry.health(&id("fake.test")), Some(Health::Ok));
        assert_eq!(registry.health(&id("nobody.test")), None);
    }

    #[test]
    fn resolve_goes_to_the_named_provider_even_when_down() {
        let fake = sample("fake.test");
        fake.fail_with(Some(CoreError::network("offline")));
        let registry = registry(&[empty("other.test", QualityTier::Lossless), Arc::clone(&fake)]);
        registry.search(&query("fake"), at(0));
        registry.search(&query("fake"), at(1));
        fake.fail_with(None);

        let tracks = registry.resolve(&id("fake.test"), &external("fake-hall-1977"), at(2)).unwrap();

        assert_eq!(tracks.len(), 2);
        assert_eq!(registry.health(&id("fake.test")), Some(Health::Ok));
    }

    #[test]
    fn missing_item_keeps_the_provider_healthy() {
        let registry = registry(&[sample("fake.test")]);

        let gone = registry.resolve(&id("fake.test"), &external("no-such-item"), at(0));

        assert!(matches!(gone, Err(CoreError::Unavailable { .. })));
        assert_eq!(registry.health(&id("fake.test")), Some(Health::Ok));
    }

    #[test]
    fn unknown_provider_is_unavailable() {
        let registry = registry(&[sample("fake.test")]);

        let resolved = registry.resolve(&id("nobody.test"), &external("x"), at(0));
        let streamed = registry.stream(&[(id("nobody.test"), external("x"))], at(0));

        assert!(matches!(resolved, Err(CoreError::Unavailable { .. })));
        assert!(matches!(streamed, Err(CoreError::Unavailable { .. })));
    }

    #[test]
    fn stream_takes_the_first_source_that_answers() {
        let broken = sample("broken.test");
        broken.fail_with(Some(CoreError::network("offline")));
        let registry = registry(&[broken, sample("fake.test")]);
        let candidates = [
            (id("broken.test"), external("single-42.mp3")),
            (id("fake.test"), external("no-such-source")),
            (id("fake.test"), external("single-42.mp3")),
        ];

        let (chosen, stream) = registry.stream(&candidates, at(0)).unwrap();

        assert_eq!(chosen, 2);
        assert_eq!(stream.url(), "https://fake.test/stream/single-42.mp3");
        assert_eq!(registry.health(&id("broken.test")), Some(Health::Degraded { failures: 1 }));
    }

    #[test]
    fn stream_tries_down_providers_last() {
        let flaky = sample("flaky.test");
        flaky.fail_with(Some(CoreError::network("offline")));
        let registry = registry(&[Arc::clone(&flaky), sample("fake.test")]);
        registry.search(&query("fake"), at(0));
        registry.search(&query("fake"), at(1));
        flaky.fail_with(None);
        let requests_before = flaky.requests();
        let candidates = [(id("flaky.test"), external("single-42.mp3")), (id("fake.test"), external("single-42.mp3"))];

        let (chosen, _) = registry.stream(&candidates, at(2)).unwrap();

        assert_eq!(chosen, 1);
        assert_eq!(flaky.requests(), requests_before);
    }

    #[test]
    fn building_a_stream_address_does_not_lift_a_ban() {
        let fake = sample("fake.test");
        fake.fail_with(Some(CoreError::network("http 503")));
        let registry = registry(&[Arc::clone(&fake)]);
        registry.search(&query("fake"), at(0));
        registry.search(&query("fake"), at(1));
        fake.fail_with(None);

        registry.stream(&[(id("fake.test"), external("single-42.mp3"))], at(2)).unwrap();

        assert!(matches!(registry.health(&id("fake.test")), Some(Health::Down { .. })));
    }

    #[test]
    fn stream_asks_a_down_provider_when_nobody_else_has_the_track() {
        let fake = sample("fake.test");
        fake.fail_with(Some(CoreError::network("offline")));
        let registry = registry(&[Arc::clone(&fake)]);
        registry.search(&query("fake"), at(0));
        registry.search(&query("fake"), at(1));
        fake.fail_with(None);

        let streamed = registry.stream(&[(id("fake.test"), external("single-42.mp3"))], at(2));

        assert_eq!(streamed.map(|(chosen, _)| chosen), Ok(0));
    }

    #[test]
    fn stream_without_candidates_or_all_failing() {
        let fake = sample("fake.test");
        let registry = registry(&[Arc::clone(&fake)]);

        assert!(matches!(registry.stream(&[], at(0)), Err(CoreError::Unavailable { .. })));

        fake.fail_with(Some(CoreError::network("offline")));
        let failed = registry.stream(&[(id("fake.test"), external("single-42.mp3"))], at(0));

        assert_eq!(failed.map(|(chosen, _)| chosen), Err(CoreError::network("offline")));
    }
}
