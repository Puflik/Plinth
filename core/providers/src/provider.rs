use plinth_types::{CoreError, ProviderId};

use crate::model::{Capabilities, ExternalId, RadioPage, RemoteTrack, SearchQuery, SearchResult, StreamRequest};

/// Источник музыки в сети (E1.1, plan.md 6.1, docs/adr/0008-provider-trait.md).
///
/// В v0.2 — только то, что нужно для «найти и сыграть». `radio` пришёл в Ю4а;
/// `download` и `browse` придут с Bandcamp (v0.5). Все они — методы по умолчанию
/// «не умеет»: готовые провайдеры это не сломает.
///
/// Вызовы синхронные и блокируют: Kotlin зовёт ядро из IO-потока, как скан.
/// Сеть — через [`crate::http::HttpClient`] поверх транспорта платформы.
///
/// Ошибки: сеть или сервер не ответили — `Network`; ответ не разобрать —
/// `Parse`; такого у провайдера нет — `Unavailable`. Реестр считает здоровье
/// по ним: `Unavailable` провайдеру не в укор. В тексте ошибки нет ни
/// запроса, ни адреса, ни названий — только что сломалось.
///
/// Что обязан каждый провайдер, проверяет общий контракт
/// (`testing::contract`, макрос [`crate::provider_contract`]).
pub trait Provider: Send + Sync {
    fn id(&self) -> &ProviderId;

    fn capabilities(&self) -> Capabilities;

    /// Не больше `query.limit()` результатов. Пустой запрос — пустой список
    /// без сети; ничего не нашлось — тоже пустой список, а не ошибка.
    fn search(&self, query: &SearchQuery) -> Result<Vec<SearchResult>, CoreError>;

    /// Треки результата поиска: у собрания — все, у трека — он один. У
    /// каждого трека есть хотя бы один вариант источника.
    fn resolve(&self, item: &ExternalId) -> Result<Vec<RemoteTrack>, CoreError>;

    /// Адрес потока варианта источника из `resolve`. Берётся перед каждым
    /// воспроизведением.
    fn stream_url(&self, source: &ExternalId) -> Result<StreamRequest, CoreError>;

    /// Радио от трека: подборка похожего. `seed` — идентификатор трека у провайдера: `external`
    /// трека из `resolve`/поиска **или** источник из его вариантов (`SourceOption::external`) —
    /// провайдер принимает оба, потому что каталог ядра хранит только источники. `page` — `next`
    /// прошлой страницы, `None` — первая. По умолчанию провайдер радио не умеет: `Unavailable`,
    /// и провайдеру это не в укор.
    fn radio(&self, seed: &ExternalId, page: Option<&ExternalId>) -> Result<RadioPage, CoreError> {
        let _ = (seed, page);
        Err(CoreError::unavailable("radio is not supported"))
    }
}
