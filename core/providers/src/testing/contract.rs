//! Общий контракт провайдера (E1): что обязан каждый, от фейка до YouTube.
//! Каждое правило — функция, которая возвращает нарушение текстом;
//! [`crate::provider_contract`] разворачивает их в тесты:
//!
//! ```ignore
//! plinth_providers::provider_contract!(fake,
//!     working: FakeProvider::sample("fake.test").unwrap(),
//!     offline: offline_fake(),
//!     case: ContractCase::new("fake", "zzz", "no-such-item"),
//! );
//! ```
//!
//! Провайдер на записанных ответах должен иметь записи для всех запросов
//! контракта: поиск `finds` с лимитом [`LIMIT`] и 1, поиск `finds_nothing`,
//! раскрытие каждого найденного, адреса всех вариантов, `gone`.

use plinth_types::CoreError;

use crate::Provider;
use crate::model::{ExternalId, ResultKind, SearchQuery, SearchResult};

/// Нарушение правила — текстом для сообщения теста.
pub type Verdict = Result<(), String>;

/// Лимит обычного поиска в контракте.
pub const LIMIT: u32 = 10;

/// Что нужно знать о каталоге провайдера, чтобы проверить контракт.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContractCase {
    /// Запрос, по которому находится больше одного результата.
    pub finds: String,
    /// Запрос, по которому не находится ничего.
    pub finds_nothing: String,
    /// Идентификатор, которого у провайдера нет.
    pub gone: String,
}

impl ContractCase {
    pub fn new(finds: &str, finds_nothing: &str, gone: &str) -> Self {
        Self { finds: finds.to_owned(), finds_nothing: finds_nothing.to_owned(), gone: gone.to_owned() }
    }
}

/// Пустой запрос — пустой список.
pub fn blank_query_finds_nothing(provider: &dyn Provider) -> Verdict {
    let found = search(provider, " \t ", LIMIT)?;
    expect(found.is_empty(), || format!("blank query found {} results", found.len()))
}

/// По `finds` находится больше одного результата.
pub fn search_finds(provider: &dyn Provider, case: &ContractCase) -> Verdict {
    let found = search(provider, &case.finds, LIMIT)?;
    expect(found.len() > 1, || format!("{:?} found {} results, expected more than one", case.finds, found.len()))
}

/// Не больше, чем просили.
pub fn search_respects_limit(provider: &dyn Provider, case: &ContractCase) -> Verdict {
    let found = search(provider, &case.finds, 1)?;
    expect(found.len() <= 1, || format!("limit 1, found {}", found.len()))
}

/// Ничего не нашлось — пустой список, а не ошибка.
pub fn nothing_found_is_empty(provider: &dyn Provider, case: &ContractCase) -> Verdict {
    let found = search(provider, &case.finds_nothing, LIMIT)?;
    expect(found.is_empty(), || format!("{:?} found {} results", case.finds_nothing, found.len()))
}

/// Тот же запрос — тот же ответ в том же порядке.
pub fn search_is_repeatable(provider: &dyn Provider, case: &ContractCase) -> Verdict {
    let first = search(provider, &case.finds, LIMIT)?;
    let second = search(provider, &case.finds, LIMIT)?;
    expect(first == second, || "the same query gave different results".to_owned())
}

/// Результаты — только объявленных в `capabilities` видов и с названием.
pub fn results_are_declared(provider: &dyn Provider, case: &ContractCase) -> Verdict {
    let capabilities = provider.capabilities();
    for result in search(provider, &case.finds, LIMIT)? {
        expect(capabilities.finds(result.kind), || format!("{:?} is not declared in capabilities", result.kind))?;
        expect(!result.title.trim().is_empty(), || format!("{} has no title", result.external))?;
    }
    Ok(())
}

/// Каждый результат раскрывается: у трека — ровно один трек, у собрания —
/// хотя бы один; у каждого трека есть название и варианты источника, и ни
/// один вариант не лучше объявленного качества.
pub fn results_resolve(provider: &dyn Provider, case: &ContractCase) -> Verdict {
    let best = provider.capabilities().best_quality;
    for result in search(provider, &case.finds, LIMIT)? {
        let tracks = provider.resolve(&result.external).map_err(|e| format!("resolve {}: {e}", result.external))?;
        match result.kind {
            ResultKind::Track => {
                expect(tracks.len() == 1, || format!("track {} resolved to {} tracks", result.external, tracks.len()))?
            }
            ResultKind::Album => expect(!tracks.is_empty(), || format!("{} resolved to no tracks", result.external))?,
        }
        for track in tracks {
            expect(!track.title.trim().is_empty(), || format!("{} has no title", track.external))?;
            expect(!track.sources.is_empty(), || format!("{} has no sources", track.external))?;
            for source in &track.sources {
                expect(source.tier() <= best, || {
                    format!("{} is {:?}, capabilities promise at most {best:?}", source.external, source.tier())
                })?;
            }
        }
    }
    Ok(())
}

/// Каждый вариант источника каждого найденного трека даёт адрес потока.
pub fn sources_stream(provider: &dyn Provider, case: &ContractCase) -> Verdict {
    for result in search(provider, &case.finds, LIMIT)? {
        let tracks = provider.resolve(&result.external).map_err(|e| format!("resolve {}: {e}", result.external))?;
        for source in tracks.iter().flat_map(|track| &track.sources) {
            provider.stream_url(&source.external).map_err(|e| format!("stream {}: {e}", source.external))?;
        }
    }
    Ok(())
}

/// Чего нет — `Unavailable`: реестр не считает это отказом провайдера.
pub fn gone_is_unavailable(provider: &dyn Provider, case: &ContractCase) -> Verdict {
    let gone = external(&case.gone)?;
    let resolved = provider.resolve(&gone).map(|tracks| tracks.len());
    expect(matches!(resolved, Err(CoreError::Unavailable { .. })), || format!("resolve of a gone item: {resolved:?}"))?;
    let streamed = provider.stream_url(&gone).map(|_| ());
    expect(matches!(streamed, Err(CoreError::Unavailable { .. })), || format!("stream of a gone source: {streamed:?}"))
}

/// Без сети — `Network` на всём, кроме пустого поиска.
pub fn offline_is_a_network_error(offline: &dyn Provider, case: &ContractCase) -> Verdict {
    let gone = external(&case.gone)?;
    let searched = offline.search(&SearchQuery::new(&case.finds, LIMIT)).map(|found| found.len());
    expect(matches!(searched, Err(CoreError::Network { .. })), || format!("offline search: {searched:?}"))?;
    let resolved = offline.resolve(&gone).map(|tracks| tracks.len());
    expect(matches!(resolved, Err(CoreError::Network { .. })), || format!("offline resolve: {resolved:?}"))?;
    let streamed = offline.stream_url(&gone).map(|_| ());
    expect(matches!(streamed, Err(CoreError::Network { .. })), || format!("offline stream: {streamed:?}"))?;
    blank_query_finds_nothing(offline)
}

fn search(provider: &dyn Provider, text: &str, limit: u32) -> Result<Vec<SearchResult>, String> {
    provider.search(&SearchQuery::new(text, limit)).map_err(|e| format!("search {text:?}: {e}"))
}

fn external(id: &str) -> Result<ExternalId, String> {
    ExternalId::new(id).map_err(|e| format!("case: {e}"))
}

fn expect(holds: bool, violation: impl FnOnce() -> String) -> Verdict {
    if holds { Ok(()) } else { Err(violation()) }
}

/// Общий контракт как тесты: модуль `$name` с тестом на каждое правило.
/// `working` и `offline` — выражения, которые дают провайдера (вычисляются в
/// каждом тесте заново); `offline` — тот же провайдер без сети.
#[macro_export]
macro_rules! provider_contract {
    ($name:ident, working: $working:expr, offline: $offline:expr, case: $case:expr $(,)?) => {
        mod $name {
            #[allow(unused_imports)]
            use super::*;
            use $crate::testing::contract;

            #[test]
            fn blank_query_finds_nothing() {
                contract::blank_query_finds_nothing(&$working).unwrap();
            }

            #[test]
            fn search_finds() {
                contract::search_finds(&$working, &$case).unwrap();
            }

            #[test]
            fn search_respects_limit() {
                contract::search_respects_limit(&$working, &$case).unwrap();
            }

            #[test]
            fn nothing_found_is_empty() {
                contract::nothing_found_is_empty(&$working, &$case).unwrap();
            }

            #[test]
            fn search_is_repeatable() {
                contract::search_is_repeatable(&$working, &$case).unwrap();
            }

            #[test]
            fn results_are_declared() {
                contract::results_are_declared(&$working, &$case).unwrap();
            }

            #[test]
            fn results_resolve() {
                contract::results_resolve(&$working, &$case).unwrap();
            }

            #[test]
            fn sources_stream() {
                contract::sources_stream(&$working, &$case).unwrap();
            }

            #[test]
            fn gone_is_unavailable() {
                contract::gone_is_unavailable(&$working, &$case).unwrap();
            }

            #[test]
            fn offline_is_a_network_error() {
                contract::offline_is_a_network_error(&$offline, &$case).unwrap();
            }
        }
    };
}
