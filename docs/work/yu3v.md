# yu3v — YouTube подключён к ядру и к плееру: внешний провайдер, адаптер, поток с заголовками (Ю3в)

> Файл задачи для исполнителя (Sonnet). Пишет планировщик; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

- **Ветка:** `work/yu3v` · **Ворктри:** `C:\claude\Plinth-yu3v`
- **Основа:** `main` на `f678d68` (Ю2 и Ю3б в `main`). **Коммиты тестов:** `a51f0ef` (Rust), `4bd51b1` (Kotlin, плюс `rustfmt` Rust-тестов) и
  `5176622` (один тест усилен после мутации) — защищённые тесты лежат в них; сверять с последним (`5176622`).
- **Откуда:** `tasks.md`, строка 389 (Ю3); `docs/work/yu1.md`, разделы 3.1 (п. 4–5) и 6; `docs/work/yu3a.md`, разделы 3.3, 4,
  5 (п. 10) и 8; `docs/work/yu3b.md`, «Осталось Ю3в» в отчёте исполнителя.
- **Данные пользователя трогает.** Сетевые треки YouTube попадают в каталог и журнал (лайки, «В плейлист…»), а имя
  провайдера `youtube` и идентификаторы вариантов `<видео>/<itag>` — их ключи, которыми трек находится после
  переустановки. **До исполнителя план читает Opus** — раздел «На что смотреть при чтении плана (Opus)».

## Цель

Когда задача сделана:

1. **В ядре есть внешний `trait` `ExternalProvider`** (`#[uniffi::export(with_foreign)]`, как `NetTransport`) и Rust-адаптер
   `ExternalAdapter`, который делает из него обычный `Provider`. `Core::connect_online(transport, providers)` принимает
   внешних провайдеров; порядок, здоровье и переход к следующему источнику — в реестре, как у Internet Archive.
   Общий контракт `provider_contract!` проходит на фейковом внешнем провайдере.
2. **Приложение отдаёт ядру YouTube.** `YoutubeProvider` — `YoutubeSource` (Ю3б) на языке `OnlineProvider`; `OnlineModule`
   передаёт `onlineProviders()` репозиторию, репозиторий — в `connect`. Имя и формат идентификаторов дословно те, что
   хранятся в каталоге и журнале.
3. **Плеер читает поток YouTube так, как его выдали.** `User-Agent` клиента, выдавшего ссылку, и `Range: bytes=0-` доходят
   до сети (`StreamRequest` → `StreamAddress` → `StreamLookup` → `OnlineResolver` → Media3); ссылка не живёт в памяти
   загрузки дольше своего срока; на 403 при чтении загрузка получает один повторный `resolve`.
4. **ADR 0008 поправлен:** провайдер может жить на платформе, сеть по-прежнему у платформы.

## Контекст

- `docs/work/yu1.md`: 3.1 (п. 4 внешний `trait`, п. 5 воспроизведение), 6 (состав Ю3в). `docs/work/yu3a.md`: 3.3 (скорость
  потока и `Range`), 4 (заголовки), 5 п. 10 и 8 (403 на телефоне: два запуска из шести, ссылка привязана к IP).
  `docs/work/yu3b.md`: разделы «Е» (`stream`, заготовка) и «Ж» (беды), отчёт исполнителя — «Осталось Ю3в».
- Ядро: `core/providers/src/provider.rs` (`Provider`), `registry.rs` (порядок, здоровье, `stream`), `model/*.rs`
  (`SearchResult`, `RemoteTrack`, `SourceOption`, `StreamRequest`, `ExternalId`, `Health`), `testing/contract.rs` и
  `fake.rs` (`provider_contract!`); `core/ffi/src/api/online_api.rs` (`connect_online`, `registry`, `online_stream`,
  `NetTransport`, `OnlineResult`, `OnlineTrackInfo`, `StreamAddress`), `core/ffi/src/panic.rs` (`guard`), `session.rs`.
- Каталог (читать, не менять): `core/library/src/db/repo/online_repo.rs` — `known_online` находит трек **по первому
  совпавшему** источнику `(provider, external_id)`, `save_online_sources` дописывает недостающие варианты.
- Kotlin: `ffi/CoreOnline.kt`, `online/CoreOnlineRepository.kt`, `online/youtube/YoutubeSource.kt` и
  `NewPipeYoutubeSource.kt` (читать, не менять), `audio/media3/OnlineResolver.kt`, `ExoPlayerFactory.kt`,
  `audio/engine/StreamResolver.kt`, `di/OnlineModule.kt`.
- ADR: `docs/adr/0008-provider-trait.md`.
- Образцы тестов: `core/ffi/src/api/r1_11d_tests.rs`, `app/src/androidTest/.../audio/media3/OnlineResolverTest.kt`.

## Решение

### А. Граница задачи

Делает: внешний `trait` и адаптер (Б–В), `connect_online` и срок адреса (Г), Kotlin-границу и `YoutubeProvider` (Д–Ж),
плеер (З), поправку ADR (И).

Не делает (это Ю3г, Ю4–Ю6; не трогать): flavor `github`/`fdroid`, метка `NonFreeNet`, переключатель по провайдерам,
`-keep`-правила R8 и `NOTICE` (Ю3г); радио и метод `related` (Ю4); название и значок «YouTube» в `providerName`,
экраны, строки (Ю5); итоговое сообщение человеку и классификацию окончательного 403 в `PlaybackError`, смену формата
после отказа, предзагрузку следующего трека, минуту игры на телефоне (Ю6). `CHANGELOG.md`, `tasks*.md`,
`docs/decisions.md` правит Opus.

### Б. Ядро: `core/ffi/src/api/external_api.rs` (новый файл; в `api/mod.rs` — строка `mod external_api;`)

Сигнатуры дословно (комментарии — по-русски, в стиле `online_api.rs`):

```rust
// plinth-types уже знает QualityTier; ffi объявляет его копию для генератора, как Format в online_api.rs.
#[uniffi::remote(Enum)]
pub enum QualityTier { Standard, HighLossy, Lossless }

#[uniffi::export(with_foreign)]
pub trait ExternalProvider: Send + Sync {
    fn id(&self) -> String;                                   // ProviderId: [a-z0-9.-]{1,64}; уходит в каталог и журнал
    fn capabilities(&self) -> ExternalCapabilities;
    fn search(&self, query: String, limit: u32) -> ExternalSearch;
    fn resolve(&self, item: String) -> ExternalResolve;
    fn stream(&self, source: String) -> ExternalStream;
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ExternalCapabilities { pub finds: Vec<OnlineKind>, pub best_quality: QualityTier }

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ExternalFailure {
    Network { text: String }, Parse { text: String }, Unavailable { text: String }, Internal { text: String },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ExternalSearch { Found { results: Vec<OnlineResult> }, Failed { failure: ExternalFailure } }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ExternalResolve { Found { tracks: Vec<OnlineTrackInfo> }, Failed { failure: ExternalFailure } }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ExternalStream { Found { address: StreamAddress }, Failed { failure: ExternalFailure } }

impl From<ExternalFailure> for CoreError { … }                // вид в вид, текст как есть

pub(crate) struct ExternalAdapter { id: ProviderId, capabilities: Capabilities, platform: Arc<dyn ExternalProvider> }
impl ExternalAdapter { pub(crate) fn new(platform: Arc<dyn ExternalProvider>) -> Result<Self, CoreError>; }
impl Provider for ExternalAdapter { … }
```

Данные — готовые `OnlineResult`, `OnlineTrackInfo`, `OnlineVariant`, `StreamAddress`, `NetHeader`, `OnlineKind` из `online_api.rs`:
второй формат ответа не заводится. **Исключение через границу не идёт** — ответ `Found` или `Failed` (как `NetAnswer`):
`CoreError` у UniFFI плоская (`flat_error`), и подать её Kotlin'у в возврате нельзя, а ответ-перечисление проще и один
для всех.

### В. Правила адаптера

1. **Имя и возможности спрашиваются один раз** — в `ExternalAdapter::new`, каждый вызов платформы в `panic::guard`.
   Негодное имя (`ProviderId::new`) и пустой `finds` — `Parse` (провайдер в реестр не попадёт); паника платформы — `Internal`.
2. **`search`.** Пустой запрос — `Ok(vec![])`, платформа не зовётся. Платформе уходят `query.text()` и `query.limit()` как есть
   (запрос уже обрезан, лимит уже 1..=100). Лишнее сверх лимита отрезается **с конца до** разбора. Результат: `ExternalId::new(external)`,
   название — `trim()` и не пусто, пустая подпись исполнителя — `None`, `year == 0` — `None`, `duration_ms` — миллисекунды,
   `album: None`.
3. **`resolve`.** Идентификатор платформе — `item.as_str()` **дословно**. Трек: те же правила названия и подписей, `number == 0`,
   `year == 0`, `bitrate_kbps == 0` — `None`, непонятный MBID — `None` (не ошибка), `disc: None`; варианты — в порядке платформы,
   каждый через `ExternalId::new`; **ни одного варианта — `Parse`** («у трека есть хотя бы один»).
4. **`stream_url`.** Идентификатор варианта — дословно. Адрес и заголовки — через `StreamRequest::new` (его проверки общие);
   `valid_for_ms` → `with_valid_for(Duration::from_millis(..))`; нет срока — адрес не стареет.
5. **Ничего не переписывается**: ни регистр, ни `-`/`_` в идентификаторах, ни порядок вариантов и заголовков.
6. **Нарушение формата платформой** — `Parse` с текстом, в котором нет ничего из её данных (ни идентификатора, ни названия, ни адреса).
7. **Беда платформы** (`Failed`) — `CoreError` того же вида и с **тем же текстом**: текст читает здоровье (`NetFailure::of` —
   «http 503», «http 429, retry after 30 s», «timed out»; прочее — «нет соединения»).
8. **Паника платформы** (UniFFI превращает непойманное исключение Kotlin в панику) — `Internal` этого вызова. `online_search`
   отдаёт такому провайдеру `problem = Broken`, остальные секции целы; `online_album` — `Err(Internal)`.
9. **Здоровье считает реестр** (`Registry` не меняется): отказ — `Degraded`, подряд — `Down`; `Unavailable` и 4xx кроме 429 провайдеру
   не в укор; 429 со сроком — пауза ровно на срок.

### Г. `connect_online`, срок адреса

`core/ffi/src/api/online_api.rs`:

```rust
pub fn connect_online(&self, transport: Arc<dyn NetTransport>, providers: Vec<Arc<dyn ExternalProvider>>) -> Result<(), CoreError>
pub(crate) fn registry_with(transport: Arc<dyn HttpTransport>, pause: Option<fn(Duration)>, external: Vec<Arc<dyn Provider>>) -> Result<Registry, CoreError>
// registry(transport, pause) остаётся для старых тестов: `#[cfg(test)]` и вызов registry_with(.., Vec::new()) —
// без `#[cfg(test)]` clippy -D warnings падает на «function `registry` is never used» (в коде приложения её больше никто не зовёт).
pub struct StreamAddress { pub url: String, pub headers: Vec<NetHeader>, pub valid_for_ms: Option<u64> }   // новое поле
```

Порядок в реестре: Internet Archive, затем внешние в порядке аргумента; реестр сортирует по `best_quality` устойчиво, так что
внешний с `Lossless` встаёт после Internet Archive. **Негодный провайдер** (имя, пустой `finds`, повтор имени — реестр
отвечает `Internal`) — ошибка; **прежнее состояние остаётся** (`set_online` вызывается только после того, как реестр собран).
Повторный вызов заменяет транспорт и провайдеров и сбрасывает здоровье. `online_stream` переносит `StreamRequest::valid_for()`
в `valid_for_ms`.

`core/providers/src/model/stream.rs`: поле `valid_for: Option<Duration>`; `StreamRequest::new` даёт `None`;
`#[must_use] pub fn with_valid_for(self, valid_for: Duration) -> Self`; `pub fn valid_for(&self) -> Option<Duration>`.
Равенство учитывает срок.

Единственная сигнатура FFI, что меняется для Kotlin, — `connectOnline(transport, providers)` и поле `validForMs` у
`StreamAddress`; других вызовов старого API нет (`CoreOnline.connect` — единственный).

### Д. Kotlin: граница (`ffi/CoreOnlineProvider.kt` — новый файл; `ffi/CoreOnline.kt`)

Сгенерированное не выходит из пакета `ffi` (`FfiBoundaryTest`), поэтому приложение видит свои типы:

```kotlin
interface OnlineProvider {
    val id: String                         // [a-z0-9.-]{1,64}; уходит в каталог и журнал, не меняется никогда
    val finds: Set<OnlineKind>             // не пусто
    val bestQuality: OnlineQuality
    fun search(query: String, limit: Int): List<OnlineResult>
    fun resolve(item: String): List<OnlineTrack>
    fun stream(source: String): StreamAddress
}
enum class OnlineQuality { STANDARD, HIGH_LOSSY, LOSSLESS }
sealed class ProviderException(val text: String, cause: Throwable? = null) : Exception(text, cause) {
    class Network(text: String, cause: Throwable? = null) : ProviderException(text, cause)
    class Parse(…) ; class Unavailable(…)            // тот же вид, что у YoutubeException
}
internal fun OnlineProvider.toRust(): <сгенерированный ExternalProvider>
```

- `CoreOnline.connect(transport: NetTransport, providers: List<OnlineProvider> = emptyList())` → `connectOnline(transport.toRust(), providers.map(OnlineProvider::toRust))`.
- `StreamAddress(url, headers = emptyList(), validFor: Duration? = null)`; `RustStreamAddress.toApp()` переносит `validForMs`
  (0 — это нуль, а не «нет»).
- **`toRust()` исключение через границу не пропускает**: `search/resolve/stream` ловят `Exception` (`@Suppress("TooGenericExceptionCaught")` с
  пояснением, как в `NetTransport.toRust()`); `ProviderException` → `Failed(Network|Parse|Unavailable(text))`; **любое другое исключение →
  `Failed(Internal(<имя класса без пакета>))`**, а у анонимного класса — его двоичное имя без пакета; **сообщение исключения не берётся
  никогда** (в нём бывают адрес и запрос, текст уходит в ошибку ядра и в журнал). `id()` и `capabilities()` при исключении
  не бросают: пустое имя и пустой `finds` дают ядру `Parse`, и подключение отказывает понятно.
- Тексты `Network` — грамматика ядра: Kotlin их не строит и не разбирает (как в Ю3б).

### Е. `YoutubeProvider` (`online/YoutubeProvider.kt` — новый; не в `online/youtube/`: пакет источника ядра не знает, `YoutubeBoundaryTest`)

`class YoutubeProvider(source: YoutubeSource, wallClock: () -> Long = System::currentTimeMillis) : OnlineProvider`.

- `id = "youtube"`, `finds = {TRACK, ALBUM}`, `bestQuality = STANDARD` (Opus 160, AAC 128).
- **Идентичность (данные пользователя).** Результат: `external = YoutubeResult.id` (видео или `OLAK5uy_…`). Трек: `external = YoutubeTrack.id`,
  варианты — `YoutubeVariant.id` как есть (`<видео>/251`, `<видео>/140`), `OPUS → AudioFormat.OPUS`, `AAC → AudioFormat.AAC`, битрейт как есть,
  Opus раньше AAC. Ничего не нормализуется и не переписывается. Вариант чужого видео трек склеил бы с другим (`known_online`):
  тест требует, чтобы каждый `variant.external` был `<id трека>/<itag>`.
- `OnlineResult`: `year = null`; `OnlineTrack`: `year = null`, `mbid = null`, `album`, `number`, `duration` как у источника.
- **Беды.** `YoutubeException.{Network,Parse,Unavailable}` → `ProviderException.*` с тем же текстом и исходным исключением в `cause`; прочее
  идёт наружу как есть (его возьмёт `toRust()`).
- **Срок ссылки.** `validFor` берётся из самой ссылки: `expire=` в строке запроса (до `#`), только цифры, не длиннее 12 знаков, секунды Unix.
  `validFor = (expire − сейчас) − 10 минут`, не меньше нуля и **не больше 2 часов**; нет внятного `expire` (нет параметра, пуст, не число,
  слишком длинный, чужое имя вроде `xexpire`) — **30 минут**. «Сейчас» — `wallClock()` (миллисекунды). Часы устройства могут врать, поэтому
  срок — совет плееру, а страховка от просроченной ссылки — повтор после 403 (З).

### Ж. DI

`online/OnlineProviders.kt`: `internal fun onlineProviders(statusTable: HttpStatusTable = CoreHttpStatusTable): List<OnlineProvider> = listOf(YoutubeProvider(NewPipeYoutubeSource(statusTable)))`.
`CoreOnlineRepository(core, transport, io, providers: List<OnlineProvider> = emptyList())` — параметр **в конце и с умолчанием**
(старые тесты собираются без правок); `setEnabled(true)` зовёт `core.online.connect(transport, providers)`;
`stream()` кладёт `address.validFor` в `StreamLookup.Found`. `OnlineModule.provideOnlineRepository` строит репозиторий с `onlineProviders()` (один раз:
источник YouTube держит заготовку потоков между `resolve` и `stream`). `StreamLookup.Found(url, headers = emptyMap(), validFor: Duration? = null)`.

### З. Плеер (`audio/media3`)

1. **`User-Agent`.** `DefaultHttpDataSource.Factory().setUserAgent(x)` в Media3 1.11.1 ставит заголовок в запрос **последним** и перекрывает
   `User-Agent`, который `OnlineResolver` положил в `DataSpec.httpRequestHeaders` (проверено по байт-коду `makeConnection`: свойства по
   умолчанию → свойства экземпляра → заголовки `DataSpec` → `Range` → `userAgent`). Так что клиентский `User-Agent` YouTube до сети не дошёл бы.
   Фабрика: `DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("User-Agent" to userAgent))` — умолчание приложения,
   которое заголовок провайдера перекрывает. `Range: bytes=0-` с позиции 0 проходит (`DefaultHttpDataSource` свой `Range` при позиции 0 и неизвестной
   длине не ставит); после перемотки он ставит свой и затирает этот.
2. **`OnlineResolver(clock: () -> Long = System::nanoTime, streams: StreamResolver)`** — `clock` **первым параметром**: так `OnlineResolver { track -> … }`
   (замыкание — последний параметр) собирается, как собирался (`OnlineResolverTest`). Загрузка помнит адрес, когда он получен и был ли отказ. Адрес
   живёт, пока `validFor == null` или `clock() − получен < validFor`; иначе следующее `resolveDataSpec` спрашивает заново (на границе — спрашивает).
   Неудача по-прежнему не запоминается. LRU на 4 загрузки как был.
3. **`fun refused(link: Uri): Boolean`** — сервер ответил 403 на адрес загрузки. Не сетевой трек — `false`. **Первый отказ этой загрузки** (даже если адреса в памяти нет):
   адрес забывается, загрузка помечается «отказ был» — `true`. **Повторный** — `false`, адрес остаётся. Метка переживает замену адреса и сбой вопроса; срок её
   не расходует; у каждой загрузки (ссылка `load=N`) своя.
4. **`ReResolvingDataSource(upstream: DataSource, resolver: OnlineResolver) : DataSource`** — на `HttpDataSource.InvalidResponseCodeException` с кодом **403** и
   `resolver.refused(dataSpec.uri) == true`: `upstream.close()`, затем `upstream.open(dataSpec)` ещё раз (адрес спросит `ResolvingDataSource` под ним). Другой
   статус, второй отказ загрузки, не сетевая ссылка — исключение наружу как есть. Остальные методы (`read`, `getUri`, `getResponseHeaders`, `close`,
   `addTransferListener`) — в `upstream`.
5. **`onlineDataSourceFactory(context: Context, userAgent: String, resolver: OnlineResolver): DataSource.Factory`** (`OnlineDataSources.kt`) собирает
   `ReResolvingDataSource(ResolvingDataSource(DefaultDataSource(context, http), resolver), resolver)` с фабрикой из п. 1. `ExoPlayerFactory.create` берёт её и
   `OnlineResolver(streams = streams)`.

**Один повторный `resolve` на загрузку, а не на каждый `open`:** после повтора загрузчик ExoPlayer ещё трижды откроет поток сам; по 403 каждый раз
идти за новым извлечением значило бы бить в сервер, который уже сказал «нет», и ждать по 2–3 с на каждое.

### И. ADR 0008 — поправка

В `docs/adr/0008-provider-trait.md` (раздел «Решение», новый пункт; в «Последствиях» — строка): **провайдер может жить на платформе** (Ю3в, 2026-10-20).
Ядро держит внешний `trait` `ExternalProvider` (`with_foreign`), адаптер `ExternalAdapter` делает из него `Provider`; реестр, здоровье, модель ответа и каталог
те же. Сеть по-прежнему у платформы: провайдер на Kotlin ходит в сеть своим загрузчиком, а «что значит статус» решает та же таблица `check_status`. Ответы без
исключений (`Found`/`Failed` с видом и текстом в грамматике `Network`); данные платформы проверяются на входе, ничего не переписывается (идентификаторы лежат в
каталоге и журнале); паника — `Internal` одного провайдера; `StreamRequest` получил срок годности. Заголовок и первый абзац «Решения» остаются верными.

### К. R8

Пока источник был ни к чему не подключён, R8 вырезал его целиком; теперь `OnlineModule` его держит, а CI гоняет `assembleGithubRelease` на каждом пуше.
После подключения **`.\gradlew.bat minifyGithubReleaseWithR8`** — если падает на отсутствующих классах, в `app/proguard-rules.pro` добавляются **только `-dontwarn`**
для них (список — в отчёт). `-keep`-правила (Rhino, `timeago.patterns`, protobuf), сужение и прогон release на устройстве — Ю3г; **release-сборку до Ю3г не выпускать**.

### Принятые ограничения

- `Registry::stream` не записывает успех в здоровье (адрес Internet Archive строится без сети), а у YouTube `stream` — настоящее извлечение: отказ в здоровье
  идёт, успех не лечит. Лечат поиск и `resolve`. Не менять — это общее поведение реестра.
- Окончательный 403 (после повтора) доходит как `ERROR_CODE_IO_BAD_HTTP_STATUS` → `PlaybackError.Network` (очередь встаёт). Понятное сообщение и пропуск — Ю5/Ю6.
- Смены формата после отказа (AAC после Opus) нет: повтор — тот же вариант со свежей ссылкой.
- Срок ссылки по часам устройства; страховка — 403. Предзагрузки нет (ExoPlayer готовит следующий элемент сам, `KEPT_LOADS = 4`).
- Ядро не передаёт провайдеру `Limits` (срок и отмену): `YoutubeSource` держит свои сроки (поиск 10 с, извлечение 25 с, меньше 15 с срока поиска ядра).
- Поиск идёт по провайдерам **по очереди** (ADR 0008, «Последствия»): Internet Archive первым, и если он ушёл на весь срок поиска (15 с) — YouTube получит секцию
  «не отвечает» без попытки. Параллельный опрос — отдельная задача; здесь не трогать (`Registry`).
- В поиске YouTube пока называется «youtube», а его песни — строками-альбомами (экран альбома с одним треком): название и вид — Ю5.

### Отвергнуто

- Ошибка в возврате колбэка (`Result<_, CoreError>`): `CoreError` плоская, лифтить её из Kotlin нельзя; ответ-перечисление — как у `NetTransport`.
- Адаптер в `plinth-providers`: крейт об UniFFI не знает. Провайдер Kotlin прямо на сгенерированном интерфейсе: сгенерированное не выходит из `ffi`.
- Нормализация идентификаторов в адаптере (регистр): это данные пользователя, провайдер им хозяин.
- Повтор на каждый `open`; фиксированный срок в резолвере: срок знает провайдер, а повторять нужно один раз.
- Свой `DataSource` вместо обёртки над `ResolvingDataSource`: больше кода, тот же контракт.

## Инварианты

- Имя `youtube` и идентификаторы `<видео>`, `<видео>/<itag>`, `OLAK5uy_…` идут из источника в каталог и обратно **дословно**; адаптер и провайдер их не меняют.
- Провайдер платформы не роняет ни процесс, ни секции других провайдеров: исключение и паника — `Internal`/`Broken` его секции.
- Данные платформы проверяются; в тексте ошибки нет ничего из них (адреса, идентификаторов, названий).
- Негодное подключение не меняет прежнего: `connect_online` кончилась ошибкой — реестр прежний.
- Internet Archive работает как раньше (его тесты проходят без правок, кроме одного вызова `connect_online` в `r1_11a_tests.rs`, уже правленного в коммите тестов).
- Заголовки потока и их порядок доходят до Media3; `User-Agent` провайдера сильнее умолчания приложения; без него — умолчание.
- Адрес не живёт в памяти загрузки дольше срока; один повторный `resolve` на загрузку; повтор — только на 403 сетевого трека.
- Трек провайдера, которого сейчас нет в реестре, остаётся в «Любимом» и плейлистах и не играет (`Unavailable`), а не пропадает.
- `core/library`, схема базы и формат журнала не меняются.

## Файлы

- **Менять (новые):** `core/ffi/src/api/external_api.rs`; `app/src/main/kotlin/io/github/puflik/plinth/ffi/CoreOnlineProvider.kt`;
  `…/online/YoutubeProvider.kt`; `…/online/OnlineProviders.kt`; `…/audio/media3/ReResolvingDataSource.kt`; `…/audio/media3/OnlineDataSources.kt`.
- **Менять (существующие):** `core/providers/src/model/stream.rs`; `core/ffi/src/api/online_api.rs`; `core/ffi/src/api/mod.rs` (только `mod external_api;`);
  `…/ffi/CoreOnline.kt`; `…/audio/engine/StreamResolver.kt`; `…/online/CoreOnlineRepository.kt`; `…/audio/media3/OnlineResolver.kt`;
  `…/audio/media3/ExoPlayerFactory.kt`; `…/di/OnlineModule.kt`; `docs/adr/0008-provider-trait.md`; `app/proguard-rules.pro` — только `-dontwarn` и только по п. К.
- **Не трогать:** `core/library/**`, `core/types/**`, `core/providers/src/registry.rs`, `health.rs`, `http/**`, `internet_archive/**`, `testing/**`;
  `…/online/youtube/**` (Ю3б — ни строчки); `FakeOnlineRepository`, `OnlineStreams`, `GatedOnlineRepository`, `OnlineSwitch`, `DeviceDecoders`; `ui/**`,
  `strings.xml`; `app/src/main/AndroidManifest.xml`, `build.gradle.kts`, `libs.versions.toml`, `settings.gradle.kts`; `CHANGELOG.md`, `tasks*.md`,
  `docs/decisions.md`, `CLAUDE.md`, план; чужие тесты.
- **Защищённые тесты** (из коммитов тестов `a51f0ef`, `4bd51b1`, `5176622`; не менять, не удалять, не глушить):
  - `core/ffi/src/api/yu3v_tests.rs` (и строка `mod yu3v_tests;` в `api/mod.rs`; правка вызова в `r1_11a_tests.rs`)
  - `core/providers/tests/yu3v_stream_validity.rs`
  - `app/src/test/kotlin/io/github/puflik/plinth/online/YoutubeProviderTest.kt`, `OnlineProvidersTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/ffi/ExternalProviderMappingTest.kt`; одна строка в старом `CoreOnlineMappingTest.kt` (`RustStreamAddress(…, null)`:
    у сгенерированной записи появилось поле `validForMs`)
  - `app/src/androidTest/kotlin/io/github/puflik/plinth/ffi/ExternalProviderCoreTest.kt`
  - `app/src/androidTest/kotlin/io/github/puflik/plinth/audio/media3/OnlineResolverExpiryTest.kt`, `ReResolvingDataSourceTest.kt`, `OnlineStreamPathTest.kt`,
    `ExoPlayerStreamTest.kt`, помощник `RangedReply.kt`
  - помощники: `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/FakeYoutubeSource.kt`, `TinyHttpServer.kt` (перенесён из `src/test`);
    `app/src/debug/AndroidManifest.xml` и `app/src/debug/res/xml/network_security_config.xml` (HTTP к `127.0.0.1` только в debug — для инструментальных тестов)

## Тесты (написаны в коммитах тестов)

На базовом коде **не собираются** — задача вводит новый API (сигнатуры — выше): Rust — нет `external_api`, `with_valid_for`; Kotlin — нет
`OnlineProvider`, `YoutubeProvider`, `onlineProviders`, `OnlineResolver(clock, …)`, `ReResolvingDataSource`.

**`core/ffi/src/api/yu3v_tests.rs`** (52 теста; платформа — `Platform` в памяти с ручками «отказ», «паника», «свой ответ»; Internet Archive — записанные ответы):
- контракт `provider_contract!` (10 правил) на `ExternalAdapter` над фейковым внешним провайдером, в том числе «без сети — `Network`»;
- поиск: все поля результата, идентификаторы дословно (`-a_B9xYz01q`, кириллица), обрезка названия, пустые подписи/год 0 — `None`; пустой запрос не доходит до
  платформы; платформа получает обрезанный текст и лимит 1..=100; лишнее сверх лимита отрезается; треки: поля, варианты в порядке платформы, нули — `None`, плохой MBID
  отброшен; поток: адрес, заголовки по порядку, срок; без срока — `None`;
- чужие данные: плохие результаты, треки, адреса и заголовки — `Parse`, и в тексте нет `SECRET`; беды — вид и текст дословно во всех трёх вызовах; паника в каждом из
  трёх вызовов и в `id()`/`capabilities()` — `Internal`, потом платформа снова работает; имя и возможности спрашиваются один раз; негодные имя и `finds`;
- здоровье в реестре: «http 503» дважды → `Down` до срока, в бане платформу не спрашивают, потом спрашивают; «http 429, retry after 30 s» → пауза ровно на 30 с с первого отказа;
  `Unavailable` и «http 403» здоровье не трогают, `Parse` считается, успех лечит; формат без потока → следующий источник;
- `connect_online`: секция платформы после `archive.org` (и при заявленном `Lossless`); без внешних — один `archive.org`; беды секции (`NoNetwork`, `ProviderDown`,
  `RateLimited`, `Broken`) — только у неё; паника не съедает чужие секции; альбом с номерами и вариантами; потерянный альбом — `ProviderDown`, паника — `Err(Internal)`;
  `archive.org` рядом работает как раньше; повторное подключение заменяет провайдеров и сбрасывает здоровье; `disconnect_online`; негодный/занятый/повторный провайдер —
  ошибка и прежнее состояние;
- игра: Opus первым, заголовки и срок до Kotlin; `undecodable` отодвигает Opus; нет Opus у видео — AAC; вид беды доходит до плеера;
- **идентичность:** одно видео — один трек, как бы оно ни пришло (песней или в альбоме), и недостающий вариант дописывается; платформе уходят ровно те идентификаторы,
  что в каталоге; трек исчезнувшего провайдера остаётся в «Любимом» и снова играет, когда провайдер вернулся.

**`core/providers/tests/yu3v_stream_validity.rs`** (4): срок есть только если назван; едет вместе с адресом и не меняет его; последний выигрывает, копия хранит; различие в сроке — различие запросов.

**Kotlin, JVM** (`testGithubDebugUnitTest`):
- `YoutubeProviderTest` (13): имя/виды/качество; текст и лимит источнику как есть; поля результатов; трек видео и трек альбома (номера, оба формата, Opus первым);
  **каждый вариант — `<id трека>/<itag>`**; заголовки потока как у источника; срок: таблица остатков (2 ч потолок, граница 7 800/7 799 с, запас 10 минут, нуль), часы приложения,
  30 минут без внятного `expire`, `expire` в любом месте строки запроса; беды — вид, текст, `cause`; чужое исключение идёт как есть.
- `ExternalProviderMappingTest` (10): что ядру говорят об имени и возможностях; провайдер, не умеющий назвать имя, не бросает; поиск/треки/поток — поля, порядок, `validForMs`
  (в том числе нуль и «нет»); `RustStreamAddress.toApp()` переносит срок; три вида бед — вид и текст; **чужое исключение — `Internal(<имя класса>)`, без сообщения**.
- `OnlineProvidersTest` (3): приложение отдаёт ядру ровно `youtube`; `OnlineModule` зовёт `onlineProviders(`.

**Kotlin, эмулятор** (`connectedGithubDebugAndroidTest`, по пакету):
- `ExternalProviderCoreTest` (9, пакет `ffi`): настоящее ядро и настоящий UniFFI — секция YouTube после `archive.org`; альбом, добавление, поток с заголовками и сроком 2 ч;
  AAC после Opus и повторное добавление находит те же треки; одно видео — один трек; беды источника → беды секции (`NO_NETWORK`, `PROVIDER_DOWN`, `RATE_LIMITED`, `BROKEN`);
  `IllegalStateException` источника не роняет процесс; `connect` без провайдеров; негодное имя и исключение в `id` → `CoreFailure`.
- `OnlineResolverExpiryTest` (12, пакет `audio.media3`): срок до наносекунды, отсчёт от получения, без срока не стареет, нулевой срок, у каждой загрузки свой; `refused`: первый — да,
  второй — нет, сам ничего не спрашивает, у каждой загрузки свой, срок его не расходует, сбой вопроса после отказа не забывается как «повтор ещё есть», не сетевая ссылка — нет.
- `ReResolvingDataSourceTest` (11): 403 → новый адрес один раз, прежнее чтение закрывается до нового, позиция/длина/заголовки сохраняются; второй 403 наружу со статусом;
  повторы загрузчика после него ничего не спрашивают; у каждой загрузки свой повтор; 404/410/416/429/500/503 без повтора; чужой адрес без повтора; сбой вопроса на повторе — причина, следующий `open` спрашивает; `read`, `uri`, `close`, слушатели — в `upstream`.
- `OnlineStreamPathTest` (6): путь целиком — ядро + `YoutubeProvider` + `OnlineStreams` + `OnlineResolver` + Media3 + HTTP на петле: сервер видит `User-Agent` клиента и `Range: bytes=0-`; перемотка
  идёт с `Range: bytes=N-` и тем же `User-Agent`; без заголовков провайдера — `User-Agent` приложения и без `Range`; отказ → один новый вопрос и поток играет; повторный отказ — после двух запросов и двух вопросов,
  загрузчик потом не спрашивает; ссылка, прожившая свой срок (2 ч), не используется.
- `ExoPlayerStreamTest` (4): настоящий ExoPlayer из `ExoPlayerFactory` готов к игре по ссылке сервера с заголовками провайдера; без `User-Agent` провайдера — приложения; 403 → один вопрос и готов;
  повторный 403 → `ERROR_CODE_IO_BAD_HTTP_STATUS` после двух вопросов.

Мутации — «Временная реализация и мутации».

## Проверки

```text
cd core && cargo test --workspace && cargo clippy --workspace --all-targets -- -D warnings && cargo fmt --all -- --check      (на Windows fmt шумит CRLF — смотреть только настоящие «Diff in»)
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"; $env:CARGO_TARGET_DIR = "C:\claude\Plinth-yu3v\core\target"
.\gradlew.bat ktlintCheck detekt testGithubDebugUnitTest testFdroidDebugUnitTest lintGithubDebug compileGithubDebugAndroidTestKotlin
.\gradlew.bat minifyGithubReleaseWithR8
```

Эмулятор `Medium_Phone_API_36.0`, `$env:ANDROID_SERIAL = "emulator-5554"` — **в той же команде, что Gradle**; фильтр по пакету, не списком классов (запятые теряются):

```text
.\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.package=io.github.puflik.plinth.audio.media3"
.\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.package=io.github.puflik.plinth.ffi"
.\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.package=io.github.puflik.plinth.online"
```

Число тестов сверять по `app/build/outputs/androidTest-results/connected/**/TEST-*.xml`. Пакет `audio.media3` включает и старые тесты плеера (`ExoPlayerFactoryTest`,
`FormatSupportTest`, `LocalPathPlaybackTest`, `Media3EngineContractTest`, `PlaybackServiceTest`) — они сторожат, что локальные файлы играют через новый источник данных. Эмулятор занят —
только сборка (`compileGithubDebugAndroidTestKotlin`), прогон оставить Opus.
Известное: на Windows падают 4 теста `DataStore*SettingsTest` — не из-за задачи; в CI на Linux проходят. Подводные камни: строка не длиннее 120 (ktlint/detekt, в том числе комментарии);
`ThrowsCount` — не больше двух `throw` в функции; `catch (e: Exception)` — только с `@Suppress("TooGenericExceptionCaught")` и пояснением; `unwrap`/`expect`/`panic!` в коде ядра запрещены (clippy).
`cargo fmt` без `--check` правит CRLF по всему крейту — не запускать; свои файлы — `rustfmt --check --edition 2024 <файл>`.
Подводные камни, найденные на временной реализации: (1) `registry()` без `#[cfg(test)]` — clippy `dead_code`; (2) `DefaultDataSource.open` требует закрытого предыдущего
чтения (`checkState`), поэтому `ReResolvingDataSource` закрывает `upstream` перед повтором, а тест закрывает источник между двумя `open`; (3) `catch` с возвращаемым
значением по умолчанию — detekt `SwallowedException`: `@Suppress("TooGenericExceptionCaught", "SwallowedException")` с пояснением; (4) ktlint `chain-method-continuation`:
цепочка из трёх и более вызовов — по строке на вызов; (5) порядок импортов в `CoreOnlineRepository.kt`; (6) `InetAddress.getLoopbackAddress()` на эмуляторе — `::1`,
поэтому сервер в тестах слушает `127.0.0.1` явно (уже в `TinyHttpServer`).

## Готово, когда

- все «Проверки» проходят; защищённые тесты не тронуты (`git diff <коммит тестов> HEAD -- <их файлы>` пуст); `grep -rn "org.schabi" app/src/main` — по-прежнему только `online/youtube/`;
- `connect_online` в Kotlin одна: `CoreOnline.connect`; `grep -rn "setUserAgent" app/src/main` — пусто; `ExoPlayerFactory` собирает источник данных через `onlineDataSourceFactory`;
- `minifyGithubReleaseWithR8` проходит (если пришлось добавить `-dontwarn` — они перечислены в отчёте);
- ADR 0008 содержит поправку (п. И);
- в отчёте: что с R8, чем не воспроизвелось, что осталось Ю3г.

## Остановиться и написать, если

- защищённый тест кажется неверным; UniFFI называет сгенерированные типы иначе, чем `ExternalProvider`, `ExternalSearch.Found/Failed`, `ExternalFailure.Network/…/Internal`, `QualityTier.STANDARD/…`;
- для решения нужно менять файл вне списка «Менять» (в том числе `online/youtube/**`, `Registry`, `core/library`);
- `Vec<Arc<dyn ExternalProvider>>` как аргумент `connect_online` UniFFI не принимает (на эталоне принимал: UniFFI 0.32.2);
- `minifyGithubReleaseWithR8` просит больше, чем `-dontwarn`;
- проверки упали дважды подряд (одна попытка исправить, потом стоп).

## На что смотреть при чтении плана (Opus)

1. **Что навсегда попадёт в данные пользователя:** имя провайдера `youtube` (`source.provider`, паспорт журнала) и `external_id` вариантов `<видео>/251`, `<видео>/140` (и `AudioSpec`: Opus 160, AAC 128). Менять
   после выпуска нельзя без миграции. Идентификаторы идут сквозь адаптер **дословно** (тесты на регистр, `-`, `_`, кириллицу в названии; мутации «в нижний регистр» пойманы).
2. **Склейка треков.** `known_online` берёт **первый** совпавший источник: вариант чужого видео в списке трека приклеил бы к нему чужой трек (и записал бы ему лишний источник). Защита — в
   Kotlin-тесте (`every variant id is the id of its own track`) и в том, что адаптер ничего не переписывает; одно видео при любом пути — один трек (тест на ядре).
3. **Номинальные варианты альбома.** Альбом называет оба формата у каждого трека (формат узнаётся при игре): у видео без Opus `stream(…/251)` даёт `Unavailable`, и ядро идёт к AAC (тест). Стоит одного
   вопроса источнику (заготовка Ю3б не даёт второго извлечения).
4. **Отказ провайдера не ломает остальных.** Исключение и паника Kotlin — `Internal` одного вызова (тесты: паника в каждом из трёх вызовов и при подключении); секция Internet Archive цела.
   `toRust()` не берёт сообщение исключения: в нём бывают адрес и запрос.
5. **`connect_online` меняет сигнатуру** (один вызов в старых тестах правлен в коммите тестов). Негодное подключение не меняет прежнего; повтор сбрасывает здоровье; дубль имени — ошибка.
6. **`Registry` не менялся.** `Registry::stream` не лечит здоровье успехом (принятое ограничение); 4xx кроме 429 провайдеру не в укор (поэтому 403 на извлечении не кладёт YouTube в `Down`).
7. **Найденный по ходу дефект:** `DefaultHttpDataSource.Factory.setUserAgent` перекрывал `User-Agent` провайдера — без правки клиентский `User-Agent` YouTube до сети не доходил бы (тест: сервер на петле).
8. **403.** Один повтор на загрузку (а не на `open`): ExoPlayer ещё трижды повторит сам, и каждый раз идти за новым извлечением — бить в сервер, который сказал «нет». Итог повторного 403 —
   `PlaybackError.Network` (очередь встаёт); это принято до Ю6.
9. **Срок ссылки** — по `expire=` и часам устройства (потолок 2 ч, запас 10 минут); при кривых часах страховка — 403. Без `expire` — 30 минут.
10. **Сборка.** YouTube теперь достижим из `OnlineModule`: CI `assembleGithubRelease` может потребовать `-dontwarn`; рабочие `-keep`-правила и прогон release — Ю3г, **до него релиз не выпускать**.
11. Отключённый/отсутствующий провайдер: сохранённый трек остаётся в «Любимом» и не играет (`Unavailable`); возвращается — играет (тест).

## Временная реализация и мутации

Реализация нужна была только для проверки тестов: **в репозиторий не коммитилась**. Патч — в заметках планировщика (`C:\Users\User\AppData\Roaming\Claude\side-session-notes\local_6a10ff5e-f13f-4d35-84fc-3a8758d8fb83\yu3v-reference-implementation.patch`);
исполнитель его не видит и пишет своё по разделу «Решение»; Opus при ревью может сверить.

**На патче зелёные:** `cargo test -p plinth-ffi --lib yu3v` (52) и `-p plinth-providers --test yu3v_stream_validity` (4); `cargo clippy --workspace --all-targets -- -D warnings`;
`ktlintCheck`, `detekt`, `lintGithubDebug`, `compileGithubDebugAndroidTestKotlin`; `testGithubDebugUnitTest` целиком (красные только четыре известных `DataStore*SettingsTest`);
на эмуляторе `audio.media3` — 103 теста, `ffi` — 31, `online` — 17, без падений. Патч — 14 файлов (6 новых, 8 существующих), ложится на `main` `f678d68`; временное
сужение `abiFilters` в `app/build.gradle.kts` (только эмулятор) в него не входит.

**Мутации — 113, все пойманы:** Rust — 54 (`yu3v-mutate-rust.py`: срок адреса, поиск и лимит, поля результатов и треков, нули и пустые подписи, порядок вариантов и заголовков,
вид и текст бед, паника в каждом вызове, имя и возможности, регистр идентификаторов, порядок реестра, замена подключения), Kotlin JVM — 52 (`yu3v-mutate-kotlin.py`: имя/виды/качество,
поля и варианты, идентификаторы, срок ссылки — запас, потолок, умолчание, часы, `expire=`, бедa и её причина, `toRust()`, провода DI), эмулятор — 7 (`yu3v-mutate-instrumented.py`:
`setUserAgent` вместо умолчания — **найденный дефект**, нет умолчания, повтор на любой статус, повтор без предела, метка отказа не переживает замену адреса, срок игнорируется, старый
стек в `ExoPlayerFactory`). На первом прогоне выжили и закрыты тестами: паника при вопросе об имени и о возможностях (`connect_online` прятала её собственным `guard` — добавлен тест
на прямой `ExternalAdapter::new`) и жёсткий лимит 20 в `toRust()` (тест просил ровно 20 — теперь 7). Не прогонялось на патче (это «Проверки» исполнителя): `testFdroidDebugUnitTest`,
`minifyGithubReleaseWithR8` (п. К), `cargo test --workspace` целиком; остальные инструментальные мутации (повтор без `close`, метка в `refused`, срок `<=`, чужая ссылка) оставлены Opus
по желанию — скрипт в заметках.

## Отчёт исполнителя

_Заполняет исполнитель: что сделано, что не вышло и почему, коммиты, вывод проверок (коротко)._
