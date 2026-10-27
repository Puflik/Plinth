# yu4a — радио как способность провайдера: ядро и мост (Ю4, часть 1 из 3)

> Файл задачи для исполнителя (Sonnet). Пишет планировщик; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

> **Данные пользователя: план до исполнителя читает Opus.** Сама yu4a данных не пишет —
> `online_radio` и `online_origins` только читают. Но решения этого файла («Решение», таблица
> «Ю4 целиком») определяют, что yu4c запишет в очередь (`queue.bin`: новый вид контекста) и в
> каталог (треки радио заводятся через `add`, как треки альбома). Вопросы и сомнения —
> в «На что смотреть при чтении плана (Opus)».

- **Ветка:** `work/yu4a` · **Ворктри:** `C:\claude\Plinth-yu4a`
- **Основа:** `main` на `e95c935` · **Коммит тестов:** `72fe4cd` (последний: правка Rust-тестов по ответу Opus о здоровье; Kotlin-тесты — `d719377`, остальные Rust-тесты — `4509c9c`; до них в ветке только черновики этого файла)
- **Откуда:** `tasks.md`, Ю4 «Радио от трека»; спайк `docs/work/yu3a.md` п. 6; ADR 0008 (метод `related`
  «придёт позже методом по умолчанию»); план 6.1, 9.2, 11.1.

## Цель

Ядро умеет отдавать радио от трека — страницами, у любого провайдера, который это умеет, а
приложение получает его через `OnlineRepository.radio(provider, seed, page)` и узнаёт по
`TrackId`, у какого провайдера трек играется (`origins`). YouTube радио пока не умеет (это yu4b),
очередь и меню — yu4c. Для человека после yu4a ничего не меняется; всё новое проверено фейками и
одним инструментальным тестом через настоящую границу Rust ↔ Kotlin.

## Контекст

Прочитать (диапазонами, не целиком):

- `docs/work/README.md` — «Что исполнитель знает без памяти»; `CLAUDE.md` — «Работа Opus и Sonnet».
- `core/providers/src/provider.rs` (весь, 40 строк), `core/providers/src/registry.rs` 1–140
  (`resolve`, `stream`, `observe`), `core/providers/src/model/mod.rs`, `external_id.rs`, `source_option.rs`
  (`RemoteTrack`).
- `core/ffi/src/api/external_api.rs` (весь), `core/ffi/src/api/online_api.rs` 77–152 (записи ответов),
  153–315 (`online_album`, `add_online_tracks`, `online_stream`), 395–430 (`problem_of`, `track_info`).
- `core/library/src/db/repo/online_repo.rs` 86–100 (`online_sources`) — откуда берутся источники трека.
- Образец тестов: `core/ffi/src/api/yu3v_tests.rs` 1–130, 357–372 (`Platform`, `connected`).
- Kotlin: `ffi/CoreOnline.kt` (весь), `ffi/CoreOnlineProvider.kt`, `online/OnlineRepository.kt`,
  `online/CoreOnlineRepository.kt`, `online/GatedOnlineRepository.kt`,
  `app/src/sharedTest/.../online/FakeOnlineRepository.kt`.
- Образцы тестов: `ExternalProviderMappingTest.kt`, `GatedOnlineRepositoryTest.kt`,
  `androidTest/.../ffi/ExternalProviderCoreTest.kt`.

## Решение

### Ю4 целиком (выбор и причина)

| Вопрос | Решение | Почему |
|---|---|---|
| **Где живёт радио** | **В ядре:** новый метод `Provider::radio` (по умолчанию «не умеет»), страница `RadioPage`, `Registry::radio`, два вызова `Core`. Реализует YouTube в Kotlin (`YoutubeProvider.radio` → `YoutubeSource.radio` → Mix NewPipeExtractor — yu4b) | Единственный путь экранов и плеера к провайдерам — реестр ядра: он считает здоровье, различает «нет сети / не отвечает / не понять» (`NetFailure`), уважает переключатели источников. «Только Kotlin» обошёл бы его и продублировал разбор бед. ADR 0008 место под метод оставил: `related` придёт «методом по умолчанию „не умеет“» — готовые провайдеры это не сломает. Цена мала: метод трейта, метод внешнего трейта, два вызова |
| **Имя и форма** | `radio(seed, page) → RadioPage { tracks: Vec<RemoteTrack>, next: Option<ExternalId> }`, а не `related(id) → [SearchResult]` из плана 6.1 | Результат поиска без вариантов источника нельзя играть: на каждый трек пришлось бы `resolve` (7–8 запросов, 2–3 с — `NewPipeYoutubeSource`). Mix отдаёт 24–25 треков за 0,8–0,9 с, у всех название, исполнитель, длительность (спайк, п. 6), а варианты у YouTube номинальные, как у треков альбома. Подборка идёт страницами с ключом продолжения: Mix динамичный, поэтому смещение (`offset`) не годится |
| **От каких треков «Радио»** (yu4c) | 1) трек YouTube в каталоге — по источнику из каталога (`online_origins` → `online_radio("youtube", источник)`; YouTube понимает и id видео, и вариант `<видео>/<itag>`); 2) трек YouTube на экране провайдера (ещё не в каталоге) — по `external` результата; 3) **трек с диска и из Internet Archive — через поиск на YouTube** по «исполнитель название» (нет названия — пункта нет), затравка — первая песня секции YouTube | Автор слушает в основном файлы с телефона: пункт, молча пропадающий на большинстве треков, выглядит сломанным. Цена — один поисковый запрос к YouTube с тегами трека. Он по действию человека, а не фоном; та же утечка, что у любого поиска, YouTube включён по умолчанию (решение автора 2026-10-25), план 9.5 требует лишь сказать, что провайдер видит запрос — это скажет подпись радио. Неточное совпадение допустимо: радио ищет похожее, а не тот же файл; само совпавшее видео в подборку не попадает (затравка — сам трек, а не его копия) |
| **Подгрузка и повторы** (yu4c) | Контекст `QueueContext.Radio`; когда впереди по порядку обхода осталось ≤ 5 треков контекста, `RadioFeeder` просит следующую страницу, заводит её треки в каталог (`add`) и дописывает в конец контекста. Повторы выбрасываются по ID каталога (то же видео) и по паре «название + исполнитель» (без регистра и знаков) — против всего, что уже в очереди: сыгранное, текущее, ручной блок, остаток; затравка (первый трек подборки) выбрасывается. Страница из одних повторов — берётся следующая, не больше трёх подряд. Предел — 300 треков в контексте. Один запрос за раз | 5 треков — 15–20 минут запаса при ответе страницы за 1 с. Сыгранное остаётся в контексте («назад» работает), поэтому и дедуп идёт по нему. Одно видео в Mix бывает под разными id (клип, lyric video) — для этого вторая пара. Предел защищает файл очереди и каталог от многочасового радио |
| **Выключен YouTube / нет сети** (yu4c) | Пункт «Радио» виден всегда, где виден «Играть следом»; нажатие без источников или с выключенным YouTube — короткое сообщение («Радио работает через YouTube — включите его в настройках»), без сети — «Нет сети», пустой ответ — «Радио для этого трека не нашлось»; **очередь не меняется, пока первая страница не пришла**. Радио уже идёт, а YouTube выключили или сеть пропала: доигрывает загруженное, ничего не подгружается, конец загруженного — обычный конец очереди и одно сообщение «Радио остановилось» | Пустая очередь после неудачного нажатия — потеря. Прятать пункт при выключенном YouTube — лишняя реактивная проводка по всем меню ради подсказки, которую даёт сообщение |
| **Сохраняется ли очередь с радио** (yu4c) | Да. Загруженные треки — обычные элементы (`AudioSource.Online`) в `queue.bin`; контекст пишется новым видом `radio` (затравка — `TrackId`, подпись) **в той же версии формата 2**. Ключ страницы не пишется: после запуска первая же подгрузка открывает радио заново от затравки и выбрасывает всё, что в очереди уже есть | Поднять версию значило бы не прочесть у пользователя очередь версии 2 при обновлении. Неизвестный вид контекста у старой версии — `IOException` → «ничего не сохранено» (так файл уже устроен, `FileQueueStore`): очередь пропадёт только при понижении версии приложения. Ключ — в памяти источника, переживать запуск ему незачем |
| **Нарезка** | yu4a — ядро и мост (эта задача); yu4b — YouTube Mix через NewPipeExtractor; yu4c — очередь, подгрузка, пункт меню | Ядро и мост проверяются фейками и за день; Mix упирается в записанные ответы (их пишет планировщик yu4b); очередь — отдельная работа с данными |

### А. Ядро: `plinth-providers`

Новый файл `core/providers/src/model/radio.rs`, `pub use radio::RadioPage;` в `model/mod.rs`:

```rust
/// Страница радио от трека (Ю4а): треки подборки по порядку и ключ следующей страницы.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RadioPage {
    /// Может быть пустой, и тогда `next` решает, есть ли что-то дальше.
    pub tracks: Vec<RemoteTrack>,
    /// Ключ следующей страницы; `None` — подборка кончилась. Для ядра и приложения строка
    /// непрозрачна, устроена по форме как `ExternalId` (непустая, до 1024 байт, без управляющих).
    pub next: Option<ExternalId>,
}
```

В `trait Provider` (`provider.rs`) — метод **с телом по умолчанию**; в шапке трейта слова «`related` придут
с Bandcamp» заменить на «`radio` пришёл в Ю4а; `download`, `browse` — с Bandcamp»:

```rust
    /// Радио от трека: подборка похожего. `seed` — идентификатор трека у провайдера: `external`
    /// трека из `resolve`/поиска **или** источник из его вариантов (`SourceOption::external`) —
    /// провайдер принимает оба, потому что каталог ядра хранит только источники. `page` — `next`
    /// прошлой страницы, `None` — первая. По умолчанию провайдер радио не умеет: `Unavailable`,
    /// и провайдеру это не в укор.
    fn radio(&self, seed: &ExternalId, page: Option<&ExternalId>) -> Result<RadioPage, CoreError> {
        let _ = (seed, page);
        Err(CoreError::unavailable("radio is not supported"))
    }
```

В `Registry` (`registry.rs`) — как `resolve` по доступности (действие человека над конкретным треком: провайдера
спрашивают и в `Down`), **но в здоровье исход не идёт — ни успех, ни сбой** (ответ Opus, 2026-10-27). У Mix свой эндпоинт и
свой повтор в приложении (yu4c: не чаще раза в 30 с), поэтому поломка Mix не должна на пять минут убирать YouTube из поиска, а
успех радио не должен снимать отдых, который заработал поиск (`Unavailable` в `observe` и вовсе считается успехом). `observe`
не зовётся, `now` не нужен:

```rust
    pub fn radio(
        &self,
        provider: &ProviderId,
        seed: &ExternalId,
        page: Option<&ExternalId>,
    ) -> Result<RadioPage, CoreError>
```

Тело — `self.find(provider)?.radio(seed, page)`. Неизвестный провайдер — `Unavailable` (как у `resolve`: `find`). Дополнить
комментарий к `Registry`: `radio` — тоже действие человека, спрашивается и в `Down`, здоровья не трогает.

### Б. Граница с платформой: `external_api.rs`

В `trait ExternalProvider` (после `stream`):

```rust
    /// Радио от трека `seed` (id трека или его источника); `page` — ключ из прошлого ответа.
    /// Не умеет — `Failed { Unavailable }`: у внешнего трейта UniFFI методов по умолчанию не бывает.
    fn radio(&self, seed: String, page: Option<String>) -> ExternalRadio;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ExternalRadio {
    Found { tracks: Vec<OnlineTrackInfo>, next: Option<String> },
    Failed { failure: ExternalFailure },
}
```

**Метод `ExternalProvider::radio` — без тела.** Проверено компилятором: `#[uniffi::export]` отвечает «uniffi::export'd
trait methods can't have a default implementation». Поэтому в трёх двойниках платформы старых тестов Ю3 (`yu3v_tests.rs`:
`Platform`, `Hopeless`; `yu3g_tests.rs`: `Platform`) планировщик добавил `fn radio(..)` → `Failed { Unavailable }` и импорт
`ExternalRadio` — это в коммите тестов, оба файла теперь защищённые (вставка — единственное, что в них изменено).
Провайдер приложения «не умеет» радио через метод по умолчанию на стороне Kotlin (`OnlineProvider.radio`, раздел Г).

`impl Provider for ExternalAdapter` — метод `radio`:

1. `panic::guard`, как у `resolve`: паника платформы — `Internal` вызова, текста паники в ошибке нет.
2. `Failed { failure }` → `failure.into()`.
3. `Found { tracks, next }`: берутся не больше `RADIO_PAGE_MAX = 100` первых треков (как `search` отрезает лишнее
   сверх лимита); каждый — через `remote_track`; **негодный трек (нет названия, нет вариантов, негодный id)
   выбрасывается, страница остаётся** — в Mix бывают пустышки вместо удалённых видео, и один такой не должен
   убивать радио; если страница не пуста, а годных треков нет, — `Parse("external radio: no usable track")`.
   `next`: `ExternalId::new` — негодный ключ (пустой, длиннее 1024 байт, управляющие знаки) → `Parse`
   (так же строго, как с остальными данными платформы).
4. Пустая страница с ключом — не ошибка и пропускается как есть (`tracks: []`, `next: Some(..)`).

### В. `Core`: два вызова (`online_api.rs`, рядом с `online_album`)

```rust
/// Страница радио: треки подборки или беда (`tracks` пуст, когда `problem` есть).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OnlineRadioReply {
    pub tracks: Vec<OnlineTrackInfo>,
    /// Ключ следующей страницы; `None` — подборка кончилась (или беда).
    pub next: Option<String>,
    pub problem: Option<OnlineProblem>,
}

/// Где трек каталога играется: провайдер и его источник (вариант).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OnlineOrigin {
    pub provider: String,
    pub source: String,
}

// в #[uniffi::export] impl Core:
pub fn online_radio(&self, provider: String, seed: String, page: Option<String>) -> Result<OnlineRadioReply, CoreError>
pub fn online_origins(&self, track: TrackId) -> Result<Vec<OnlineOrigin>, CoreError>
```

- `online_radio` — как `online_album`: `panic::guard`; `self.online().ok_or_else(off)?`; `ProviderId::new`,
  `ExternalId::new` для затравки и ключа (негодные — `Parse`, платформа не зовётся);
  `registry.radio(&provider, &seed, page.as_ref())`; `Ok(page)` → `OnlineRadioReply { tracks: page.tracks.map(track_info), next: page.next.map(|key| key.as_str().to_owned()), problem: None }`;
  `Err(Network | Unavailable | Parse)` → `problem_of(&error)` с пустыми треками; прочие ошибки (`Internal`, `Storage`)
  — ошибка вызова. Выключенные источники — `Unavailable("online sources are off")` ошибкой, как у альбома.
- `online_origins` — читает каталог: `online_sources(track)` → `OnlineOrigin { provider, source: external_id }` в
  порядке `online_sources`; нет такого трека или нет сетевых источников (файл с диска) — пустой список, не ошибка.
  Онлайн-источники для него включены быть не обязаны.

### Г. Kotlin

`ffi/CoreOnline.kt` — типы рядом с `OnlineAlbumReply` и метод рядом с `album`:

```kotlin
/** Страница радио: треки подборки по порядку и ключ следующей страницы; `null` — подборка кончилась. */
data class OnlineRadioPage(val tracks: List<OnlineTrack>, val next: String? = null)

/** Ответ на просьбу о радио: страница — или беда провайдера (тогда [page] пуста). */
data class OnlineRadioReply(val page: OnlineRadioPage, val problem: OnlineProblem?)

/** Где трек каталога играется: провайдер и его источник (вариант). */
data class OnlineOrigin(val provider: String, val source: String)

// в CoreOnline:
fun radio(provider: String, seed: String, page: String? = null): OnlineRadioReply =
    core.quietCall { rust -> rust.onlineRadio(provider, seed, page).toApp() }

fun origins(track: TrackId): List<OnlineOrigin> =
    core.call { rust -> rust.onlineOrigins(track.value).map { it.toApp() } }
```

Преобразования — `internal fun RustOnlineRadioReply.toApp()` и `internal fun RustOnlineOrigin.toApp()` (файл
`CoreOnline.kt`, рядом с `RustOnlineAlbumReply.toApp()`; сгенерированные записи — `io.github.puflik.plinth.ffi.generated.OnlineRadioReply`
и `OnlineOrigin`, импорт с псевдонимами `RustOnlineRadioReply`, `RustOnlineOrigin`).

`ffi/CoreOnlineProvider.kt` — интерфейс провайдера приложения получает метод **с телом по умолчанию**:

```kotlin
    /** Радио от трека [seed] — id трека или его источника; [page] — ключ прошлой страницы. Не умеет — `Unavailable`. */
    fun radio(seed: String, page: String? = null): OnlineRadioPage = throw ProviderException.Unavailable("radio")
```

и `toRust()` — `override fun radio(seed: String, page: String?): RustRadio`: `try { RustRadio.Found(tracks, next) }
catch (e: Exception) { RustRadio.Failed(e.toFailure()) }` — по образцу `search`/`resolve`/`stream`
(`@Suppress("TooGenericExceptionCaught", "SwallowedException")` с тем же комментарием). `YoutubeProvider` **не
трогать**: он унаследует `radio` по умолчанию и до yu4b радио не умеет.

`online/OnlineRepository.kt` — тип и два метода рядом с `OnlineAlbum`/`album`:

```kotlin
/** Страница радио: треки и ключ следующей страницы — или беда, которую объясняет экран. */
sealed interface OnlineRadio {
    data class Page(val tracks: List<OnlineTrack>, val next: String?) : OnlineRadio
    data class Failed(val problem: OnlineProblem) : OnlineRadio
}

// в OnlineRepository:
/**
 * Радио от [seed] провайдера [provider] (id трека или его источника); [page] — ключ прошлой страницы,
 * `null` — первая.
 */
suspend fun radio(provider: String, seed: String, page: String? = null): OnlineRadio

/** Где трек каталога играется; файл с диска и неизвестный трек — пусто. */
suspend fun origins(track: TrackId): List<OnlineOrigin>
```

- `CoreOnlineRepository.radio` — как `album`: `withContext(io)`, `reply.problem?.let { OnlineRadio.Failed(it) } ?: OnlineRadio.Page(reply.page.tracks, reply.page.next)`;
  `CoreFailure` → `OnlineRadio.Failed(failure.problem())` с `AppLog.w(TAG, "radio failed", failure)`.
  `origins` — `attempt(io, TAG, "origins") { core.online.origins(track) } ?: emptyList()`.
- `GatedOnlineRepository`: `radio` ждёт первого `setEnabled` (`awaitSwitch()`), как `album` — холодный старт
  с восстановленным радио иначе получил бы «источники выключены»; `origins` ждать не нужно — каталог.
- `FakeOnlineRepository` (sharedTest): `scriptRadio(provider, seed, page, answer)` — сценарий; `radioCalls` — вызовы
  по порядку (`RadioCall(provider, seed, page)`); без сценария — `OnlineRadio.Failed(PROVIDER_DOWN)`; выключенные
  источники и провайдер из `disabled` — `Failed(PROVIDER_DOWN)` даже при сценарии; нет сети — `Failed(NO_NETWORK)`;
  отдыхающий провайдер — `Failed(PROVIDER_DOWN)` (как `album`: те же `enabled`, `disabled`, `networkUp`, `providerDown`).
  `origins` фейк выводит из `add`: провайдер из вызова и `external` каждого варианта по порядку; неизвестный
  трек — пусто.

### Отвергнуто

- **Радио только в Kotlin** — в обход реестра: без здоровья, без переключателей, второй разбор бед.
- **Поле `radio` в `Capabilities` / `ExternalCapabilities`** — правка всех литералов в тестах Ю3 ради одного
  провайдера; способность пока выражается тем, что остальные отвечают `Unavailable`. Приложение знает, что
  радио у YouTube, из `OnlineProviderIds` (yu4c).
- **`radio(track: TrackId, ..)` с обходом источников в ядре** — ключ страницы принадлежит провайдеру, пришлось бы
  заворачивать его вместе с именем провайдера. Два прямых вызова (`origins`, `radio`) проще и ложатся на
  то, что уже есть (`album`, `known`).
- **Ядро заводит треки радио в каталог само** — запись решает приложение, как у альбома («каталог при действии»).
- **Ключ страницы — сериализованная `Page` NewPipe** — больше килобайта и растёт с каждой страницей; ключом будет
  короткая строка, а страница — в памяти источника (yu4b).

## Инварианты

- `online_radio`, `online_origins` и всё, что их зовёт в этой задаче, **ничего не пишут** в каталог и журнал.
- Беда провайдера (`Network`, `Unavailable`, `Parse`) — `problem` ответа, а не исключение; выключенные источники,
  `Internal` и негодные аргументы — ошибка вызова.
- В тексте ошибки нет затравки, ключа и названий; затравка и ключ доходят до платформы **дословно** (в том числе
  не ASCII). Паника платформы — `Internal` вызова, как у `search`/`resolve`/`stream`; её текст идёт в ошибку и лог через
  `panic::guard`, как и раньше (этой задачей не меняется).
- Страница: не больше 100 треков, порядок платформы сохранён; негодный трек выброшен без потери страницы; страница
  только из негодных — `Parse`; негодный ключ — `Parse`.
- Радио в здоровье не идёт — ни успех, ни сбой (`Registry::radio` не зовёт `observe`): поломка Mix не убирает провайдера
  из поиска, успех радио не снимает отдых и сбой его не продлевает. Провайдера спрашивают и в `Down` (действие
  человека над треком). Провайдер без `radio` отвечает `Unavailable`.
- Существующее поведение не меняется: поиск, альбом, поток, здоровье. Старые тесты Rust и Kotlin проходят
  (в трёх двойниках платформы старых тестов Rust планировщик уже добавил `fn radio`, см. «Решение», Б).

## Файлы

- **Менять:**
  - Rust: `core/providers/src/model/radio.rs` (новый), `core/providers/src/model/mod.rs`,
    `core/providers/src/provider.rs`, `core/providers/src/registry.rs`, `core/ffi/src/api/external_api.rs`,
    `core/ffi/src/api/online_api.rs`;
  - Kotlin: `app/src/main/kotlin/io/github/puflik/plinth/ffi/CoreOnline.kt`, `.../ffi/CoreOnlineProvider.kt`,
    `.../online/OnlineRepository.kt`, `.../online/CoreOnlineRepository.kt`, `.../online/GatedOnlineRepository.kt`,
    `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/FakeOnlineRepository.kt`;
- **Не трогать:** `online/YoutubeProvider.kt`, `online/youtube/*`, `online/OnlineProviders.kt`,
  `online/OnlineProviderIds.kt` (yu4b, yu4c); `queue/*`, `audio/*`, `ui/*` (yu4c); `Cargo.lock`,
  `gradle/libs.versions.toml`; `CHANGELOG.md`, `tasks*.md`, `docs/decisions.md`, `docs/adr/*`, `docs/plan/*`, `CLAUDE.md`
  (при слиянии их правит Opus: ADR 0008 — поправка «`related` → `radio`», план 6.1, 9.2).
- **Защищённые тесты** (из коммита тестов; не менять, не удалять, не глушить):
  - `core/providers/tests/yu4a_radio.rs`
  - `core/ffi/src/api/yu4a_tests.rs` (и строка `mod yu4a_tests;` в `core/ffi/src/api/mod.rs` — она в коммите тестов)
  - `core/ffi/src/api/yu3v_tests.rs`, `core/ffi/src/api/yu3g_tests.rs` — только вставка `fn radio` и импорт `ExternalRadio`
  - `app/src/test/kotlin/io/github/puflik/plinth/ffi/ExternalProviderRadioTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/ffi/CoreOnlineRadioMappingTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/online/FakeOnlineRepositoryRadioTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/online/GatedOnlineRepositoryRadioTest.kt`
  - `app/src/androidTest/kotlin/io/github/puflik/plinth/ffi/OnlineRadioCoreTest.kt`

## Тесты (написаны в коммите тестов)

До реализации ничего из этого не собирается (новый API: `RadioPage`, `Provider::radio`, `Registry::radio`,
`ExternalRadio`, `online_radio`, `online_origins`, `OnlineRadio`, `OnlineRadioPage`, …) — сигнатуры выше.

**`core/providers/tests/yu4a_radio.rs`** — реестр и трейт:
- провайдер без `radio` отвечает `Unavailable`, и реестр, спрошенный пять раз, оставляет ему здоровье `Ok`;
- реестр отдаёт затравку и ключ провайдеру дословно, первая страница → `next = Some`, по ключу — вторая, `next = None`;
- неизвестный провайдер — `Unavailable`, никого не спрашивают;
- пять сбоев радио (`Network`) подряд здоровье не меняют (`Ok`), провайдера по-прежнему спрашивают;
- отдыхающий (три сбоя `resolve`) провайдер на радио отвечает, но ни сбой, ни успех радио не меняют ни здоровье, ни срок
  отдыха.

**`core/ffi/src/api/yu4a_tests.rs`** — `Core` с платформенным провайдером в памяти:
- первая страница: треки как пришли (id, название, варианты), ключ, нет беды; по ключу — вторая и последняя;
- затравка-вариант `dQw4w9WgXcQ/251` и ключ с кириллицей и пробелами доходят платформе дословно;
- негодный трек выброшен, страница жива; страница только из негодных — `Broken`; пустая страница с ключом и без;
- больше 100 треков — первые 100; негодный ключ платформы — `Broken`;
- таблица бед платформы → `NoNetwork`/`ProviderDown`/`RateLimited`/`Broken` (как у альбома); `Internal` — ошибка;
- паника платформы — `Internal` вызова, следующий вызов работает;
- источники выключены — `Unavailable` ошибкой; чужой провайдер — `ProviderDown`; негодные аргументы — `Parse`, платформа не зовётся;
- платформа без `radio` — `ProviderDown`, поиск по ней жив, здоровье цело;
- пять сбоев радио подряд не отдыхают провайдера: поиск по нему жив, шестой вызов получает страницу;
- радио ничего не заводит в каталоге (`known_online_tracks` — пусто);
- `online_origins`: у сетевого трека — его источники по порядку; у неизвестного — пусто; ядро без `connect_online` отвечает.

**Kotlin (`app/src/test`):**
- `ExternalProviderRadioTest` — `OnlineProvider.toRust()`: страница уходит ядру как есть, затравка и ключ доходят
  провайдеру; `ProviderException` трёх видов → `Failed` тех же видов с текстом; чужое исключение — только имя
  класса; провайдер без своего `radio` → `Failed(Unavailable("radio"))`.
- `CoreOnlineRadioMappingTest` — ответ ядра и `OnlineOrigin` → типы приложения; беда → пустая страница.
- `FakeOnlineRepositoryRadioTest` — сценарии фейка (раздел Г): ответ по трём ключам, без сценария, выключено,
  `disabled`, нет сети, отдых, `origins` из `add`.
- `GatedOnlineRepositoryRadioTest` — `radio` до первого `setEnabled` ждёт и потом идёт; `origins` не ждёт.

**`app/src/androidTest/.../ffi/OnlineRadioCoreTest.kt`** (эмулятор): настоящая граница. Ядро + `CoreOnlineRepository`
+ провайдер приложения с радио: страницы доходят до репозитория с ключом, беда провайдера становится
`OnlineRadio.Failed`, провайдер без радио — `PROVIDER_DOWN`, `origins` после `add` отдаёт источники.

## Проверки

```text
cd core && cargo test -p plinth-providers && cargo test -p plinth-ffi && cargo clippy --workspace --all-targets -- -D warnings && cargo fmt --all -- --check
.\gradlew.bat ktlintCheck detekt testGithubDebugUnitTest lintGithubDebug   (JAVA_HOME — docs/work/README.md)
$env:ANDROID_SERIAL = "emulator-5554"; .\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.package=io.github.puflik.plinth.ffi"
```

Инструментальные — только на эмуляторе `-no-window` (память проекта: фильтр по пакету, а не списком классов).
Известное: на Windows падают 4 теста `DataStore*SettingsTest` — не из-за задачи; в CI на Linux проходят.

## Готово, когда

- все защищённые тесты зелёные, остальные тесты Rust и Kotlin — как были;
- `cargo clippy`/`fmt`, `ktlintCheck`, `detekt`, `lintGithubDebug` чисты;
- `OnlineRadioCoreTest` проходит на эмуляторе;
- в приложении по-прежнему ничего не видно: меню, очередь, YouTube — те же (`YoutubeProvider` не менялся);
- числа для сверки: Rust — 6 тестов в `yu4a_radio`, 19 в `yu4a_tests` (всего в `plinth-ffi` 180); Kotlin — 6 + 4 + 6 + 4
  на JVM (`ExternalProviderRadioTest`, `CoreOnlineRadioMappingTest`, `FakeOnlineRepositoryRadioTest`,
  `GatedOnlineRepositoryRadioTest`) и 4 инструментальных (`OnlineRadioCoreTest`);
- скорость: не критерий — радио не на горячем пути.

## Остановиться и написать, если

- защищённый тест кажется неверным;
- сгенерированные Kotlin-имена не те, что в тестах (`ExternalRadio.Found(tracks, next)`, `OnlineRadioReply`, `OnlineOrigin`);
- нужно менять `Capabilities` или файл вне списка «Менять»;
- проверки упали дважды подряд.

## На что смотреть при чтении плана (Opus)

**Ответы Opus (2026-10-27, предварительные, до исполнителя).** П. 3 — радио в здоровье не идёт: внесено в план и в тесты.
П. 2 — формат очереди 2 с видом `radio`, без версии 3: принято (это yu4c). П. 4 — затравка есть `ExternalId` провайдера в
любой его форме (id видео или вариант), разбирает провайдер: принято. П. 1 — `add_online_tracks` журнал не пишет
(проверено: только `db.add_online_track`); рост каталога и показ исполнителей и альбомов из одних сетевых треков —
вопрос yu4c, чистка каталога — отдельная задача после 0.3. П. 6 — поправку к ADR 0008 пишет Opus при слиянии. Ждёт ответа
п. 5 и общий итог.

1. **Каталог растёт радио.** `add` на каждую страницу заводит до 25 строк `Track`/`Version`/`Source`, а ещё
   исполнителей и альбомы по имени (`insert_online`: `artist_by_name`, `album_by_title`). Заметки в `online_repo.rs`
   говорят, что вкладки библиотеки сетевых треков не показывают, но показывают ли они исполнителей и альбомы, заведённые
   только сетевыми треками? Играть альбом YouTube уже так делает, радио умножает в 25 раз и без границы. Чистки каталога
   нет. Предел 300 в контексте — на одну очередь, не на каталог. Годится ли «как есть» или нужна граница/чистка
   (отдельная задача)? Журнал `add` не пишет — проверь, что это так для `add_online_tracks`.
2. **`queue.bin` без подъёма версии.** Новый вид контекста `radio` в формате 2; понижение версии приложения
   теряет очередь молча. Альтернатива — версия 3 с чтением обеих: тогда понижение тоже теряет очередь, а
   обновление безопасно лишь при корректном чтении 2. Какой вариант принимаешь?
3. **Здоровье — решено:** радио в здоровье не идёт (ответ выше). Раньше план вёл его через `observe`, как `resolve`.
4. **Затравка — «источник или id трека»** (двойной смысл `seed`): каталог хранит только варианты, поэтому ядро не
   знает id видео. Принимать оба должен провайдер (YouTube: `VIDEO_ID` или `VARIANT`). Нет ли чище?
5. **`ExternalProvider::radio` без тела (решение плана изменилось по ходу проверки).** UniFFI тел по умолчанию не принимает (компилятор), поэтому три двойника платформы
   в старых тестах Ю3 получили вставку `fn radio` → «не умею»; они теперь защищённые. Альтернатива — отдельный маленький
   внешний трейт только для радио (ещё один объект на провайдера) — отвергнута как лишняя. Согласен?
6. **Название `radio` вместо `related`** — поправка к ADR 0008 и плану 6.1 (`related(id) → [SearchResult]` →
   `radio(seed, page) → RadioPage`); текст поправки напишет Opus при слиянии.
7. **Инвариант про текст паники убран из плана** (по ходу проверки): `panic::guard` кладёт текст паники в `Internal` у всех
   вызовов платформы (`search`, `resolve`, `stream`), и радио не исключение. Это существующее поведение, этой задачей оно не
   меняется; в Kotlin исключения до границы не доходят (`toRust()` ловит `Exception`), паника возможна только от `Error`.
   Если нужно ужесточить — отдельная задача.
8. **Тесты на эмуляторе и фейк.** `FakeOnlineRepository` получает `scriptRadio`, `radioCalls`, `RadioCall`, `origins` из
   `add`: на нём будут стоять тесты очереди (yu4c), поэтому поведение закреплено `FakeOnlineRepositoryRadioTest`.

## Временная реализация и проверка тестов

**Rust (проверено 2026-10-27).** Прототип лежит патчем в общей папке заметок сессий
(`yu4a-reference-implementation-rust.patch`, в ветке его нет): `RadioPage`, `Provider::radio`, `Registry::radio`, `ExternalRadio`,
`ExternalProvider::radio` (без тела), `ExternalAdapter::radio`, `OnlineRadioReply`, `OnlineOrigin`, `online_radio`, `online_origins`.
Против него: `cargo test -p plinth-providers --test yu4a_radio` — 6 из 6; `cargo test -p plinth-ffi` — 180 из 180 (19 из них
в `yu4a_tests`, остальные — старые вместе с тремя двойниками); `cargo clippy --workspace --all-targets -- -D warnings` и
`cargo fmt --all -- --check` чисты (шум CRLF по чужим файлам не в счёт). Без прототипа тесты не собираются: новый API.
Что прототип выяснил: (1) тело по умолчанию у метода `ExternalProvider` UniFFI не принимает; (2) текст паники платформы
попадает в `Internal` через `panic::guard` и сейчас, не только у радио — тест этого не требует; (3) `versions_of` неизвестного
трека — пустой список, поэтому `online_origins` для него `Ok([])`; (4) `TrackId` — `Copy`.
Правка по ответу Opus о здоровье (2026-10-27): прототип обновлён — `Registry::radio(provider, seed, page)` без `now` и без
`observe`, патч в заметках перезаписан; тесты на здоровье переписаны. Мутация «радио снова идёт в здоровье» валит ровно три
теста (два в `yu4a_radio`, один в `yu4a_tests`); без мутации — 6 из 6 и 19 из 19, clippy и fmt чисты.

**Kotlin (проверено 2026-10-27).** Прототип — патчем в общей папке заметок (`yu4a-reference-implementation-kotlin.patch`, в ветке
его нет): `OnlineRadioPage`, `OnlineRadioReply`, `OnlineOrigin`, `CoreOnline.radio`/`origins`, `OnlineProvider.radio` по умолчанию и
`toRust()`, `OnlineRadio`, `OnlineRepository.radio`/`origins`, `CoreOnlineRepository`, `GatedOnlineRepository.radio`,
`FakeOnlineRepository.scriptRadio`/`radioCalls`/`origins`. Против него: `testGithubDebugUnitTest` на четырёх новых классах — 20 из 20
(6 + 4 + 6 + 4); `compileGithubDebugAndroidTestKotlin` собирается; `OnlineRadioCoreTest` на эмуляторе `Medium_Phone_API_36.0`
(`-no-window`, `ANDROID_SERIAL=emulator-5554`, фильтр `…class=io.github.puflik.plinth.ffi.OnlineRadioCoreTest`; сборка 4 мин 43 с,
эмулятор потом выключен) — 4 из 4; `ktlintCheck` и `detekt` на моих тестах чисты (длинные строки поправлены; в прототипе нашёлся
порядок импортов в `FakeOnlineRepository.kt` — исполнителю при реализации). Без прототипа тесты не собираются: новый API.

## Дальше: yu4b и yu4c (наброски; подробно распишут их планировщики)

### yu4b — YouTube Mix через NewPipeExtractor

Файлы: `online/youtube/YoutubeSource.kt`, `NewPipeYoutubeSource.kt`, `online/YoutubeProvider.kt`, `FakeYoutubeSource.kt`,
`tools/youtube-record/*`, `app/src/test/resources/youtube/mix/*`, `routes.tsv`, правила R8 при нужде.

- `YoutubeSource.radio(seed: String, page: String?): YoutubeRadioPage` (треки как `YoutubeTrack` с номинальными
  вариантами, как у треков альбома, и `next: String?`). Затравка — id видео или вариант `<видео>/<itag>`; прочее —
  `Unavailable("id")` без сети.
- `NewPipeYoutubeSource.radio`: первая страница — `getPlaylistExtractor("https://www.youtube.com/watch?v=<видео>&list=RD<видео>")`,
  `fetchPage()`, `initialPage.items`; следующая — `getPage(Page)`. `Page` NewPipe (адрес, `ids`, cookies, тело) в ключ не
  сериализуется: ключ — короткая случайная строка, `Page` — в ограниченной памяти источника (восемь ключей, 30 минут, как
  `StreamStash`); неизвестный или протухший ключ — `Unavailable("page")`: приложение тогда открывает радио заново
  от затравки (это же происходит после запуска). Первый трек подборки (сама затравка) в ответ не идёт. Прямые
  эфиры и видео без аудио пропускаются. Срок вызова — отдельный в `YoutubeBudgets` (первая страница 0,8–0,9 с).
- Записанные ответы Mix пишет **планировщик yu4b** (`Recorder.java` получает сценарий `mix <видео> [страниц]`,
  `trim.py` — урезание, `routes.tsv` — маршруты): сетевые ответы, а не рукописные.
- Проверка на эмуляторе с настоящей сетью — пробой, не часть CI. Release-сборка с R8 — радио не вырезано.

### yu4c — очередь, подгрузка, пункт «Радио»

Затрагивает данные (очередь `queue.bin`, каталог): план читает Opus до исполнителя.

- `QueueContext.Radio(seed: String /* TrackId.value */, title: String)`; вид `radio` в `FileQueueStore` (версия 2);
  подпись «Радио: …».
- `PlaybackQueue.extended(context, base, items)` — дописывает в конец контекста, только если контекст тот же и
  `contextItems.size == base` (гонка со сменой контекста); порядок обхода дополняется концом (shuffle радио —
  только в загруженной части). `PlaybackController.extend(..)` обновляет очередь **без** `prepare`: текущий трек не
  прерывается.
- **Ловушка:** `PlaybackQueue.playing` сравнивает `contextItems` целиком; после `extended` оно перестанет быть
  равным, и `QueueKeeper.saveQueues` сбросит сохранённую секунду текущего трека на 0. Нужен ключ места, не зависящий
  от длины контекста, и тест.
- `RadioStarter` (затравка → источники `origins` → иначе поиск по словам среди секции YouTube → первая страница →
  `add` → `play`) и `RadioFeeder` (следит за `PlaybackController.queue`, порог 5, один запрос за раз,
  повтор при провале — не чаще раза в 30 с, 3 страницы подряд из одних повторов — стоп, предел 300, дедуп, ключ в
  памяти, при незнакомом ключе — открыть заново от затравки). `OnlineProviderIds.RADIO = setOf(YOUTUBE)`.
- Пункт «Радио» в `TrackAction`/`TrackRow` (все списки), в строках `OnlineAlbumScreen`, в `PlayerMenu`; строки ru/en;
  сообщения о выключенном YouTube, сети, пустом ответе, остановке радио; очередь не меняется до первой страницы.
- Холодный старт: восстановленная очередь радио на паузе; подгрузка начинается с первого `play` (через `Gated`).
- Проверить: не показывают ли вкладки «Исполнители» и «Альбомы» исполнителей и альбомы, заведённые только сетевыми
  треками радио (`insert_online` создаёт их по имени); чистка каталога — отдельная задача после 0.3. Радио в здоровье не
  идёт (yu4a), поэтому повторы подгрузки ограничивает только сам `RadioFeeder`.

## Чтение плана (Opus, 2026-10-27)

План принят. К предварительным ответам в разделе «На что смотреть при чтении плана»:

5. **`ExternalProvider::radio` без тела — принято.** Вставки `fn radio` в трёх двойниках платформы старых тестов Ю3 —
   единственная правка старых тестов, она в коммите тестов; отдельный трейт для радио не нужен. В Kotlin у
   `OnlineProvider.radio` тело по умолчанию есть, поэтому `YoutubeProvider` не меняется.
7. **Текст паники — принято как есть:** поведение `panic::guard` общее для всех вызовов платформы, этой задачей не
   меняется.
8. **Фейк и его тест — принято:** на них встанут тесты очереди yu4c.

Исполнителю: решение и тесты не менять; при расхождении с планом — стоп по разделу «Остановиться и написать, если».

## Отчёт исполнителя

**Rust — сделано** (коммит `ea73e69` в `work/yu4a`; шесть файлов из «Менять»:
`core/providers/src/model/radio.rs` (новый), `model/mod.rs`, `provider.rs`, `registry.rs`,
`core/ffi/src/api/external_api.rs`, `online_api.rs`). Основа — Rust-патч планировщика; каждая
строка сверена с «Решением» и «Инвариантами». Расхождений с планом два, оба поправил я:

- в патче не было правки шапки трейта `Provider` — внесена, как велит «Решение», А: «`radio`
  пришёл в Ю4а; `download` и `browse` придут с Bandcamp (v0.5)»;
- `next` в `online_radio` — `key.as_str().to_owned()`, как в «Решении», В (в патче `to_string()`:
  значение то же, `Display` у `ExternalId` пишет строку как есть).

Состав: `RadioPage`; `Provider::radio` с телом «не умею» (`Unavailable`); `Registry::radio` — как
`resolve` по доступности (спрашивает и отдыхающего), но **без `observe`**: ни успех, ни сбой радио в
здоровье не идёт (ответ Opus), комментарий к `Registry` дополнен; `ExternalProvider::radio` **без
тела** (UniFFI тел не принимает; три двойника старых тестов Ю3 уже получили `fn radio` в коммите
тестов), `ExternalRadio`, `RADIO_PAGE_MAX = 100`; `ExternalAdapter::radio` — `panic::guard`, первые
100 треков, негодный трек выброшен, страница из одних негодных — `Parse`, негодный ключ — `Parse`,
пустая страница с ключом пропускается; `online_radio` (беда провайдера — `problem`, выключенные
источники, `Internal`, негодные аргументы — ошибка вызова) и `online_origins` (источники трека по
порядку каталога; неизвестный трек и файл с диска — пусто). Каталог и журнал не пишутся.

**Rust — проверки** (Windows, из `core/`):

- Красное до правки: `cargo test -p plinth-providers --test yu4a_radio --no-run` и
  `cargo test -p plinth-ffi --no-run` не собираются — нет `RadioPage`, `Provider::radio`,
  `Registry::radio`, `ExternalRadio`, `online_radio`, `online_origins`, `OnlineRadioReply`,
  `OnlineOrigin` (E0599, E0432, E0407, E0425).
- После: `cargo test --workspace` — 1000 зелёных, 0 упавших, 1 `ignored` не мой (доктест
  `providers/src/testing/contract.rs`). Числа из «Готово, когда» сошлись: `yu4a_radio` — 6 из 6,
  `yu4a_tests` — 19 из 19, весь `plinth-ffi` — 180 из 180. `cargo clippy --workspace --all-targets
  -- -D warnings` — чисто. `cargo fmt --all -- --check` — настоящих `Diff in` нет (код 1 — только
  «Incorrect newline style» по CRLF-файлам рабочей копии); `cargo fmt` без `--check` не запускался.

**Kotlin — сделано** (коммит с этим отчётом; шесть файлов из «Менять»: `ffi/CoreOnline.kt`,
`ffi/CoreOnlineProvider.kt`, `online/OnlineRepository.kt`, `CoreOnlineRepository.kt`,
`GatedOnlineRepository.kt`, `sharedTest/.../FakeOnlineRepository.kt`). Основа — Kotlin-патч
планировщика, сверен с «Решением», Г. Поверх него мои правки: порядок импортов в фейке
(`OnlineOrigin` после `OnlineKind` — на это указал планировщик; ktlint его бы не пропустил);
KDoc у `GatedOnlineRepository` (перечень ждущих вызовов дополнен `radio`, каталог — `origins`) и
у `FakeOnlineRepository` (радио по сценарию, `origins` из `add`) — иначе они описывали бы старое
поведение. `YoutubeProvider` и всё из «Не трогать» не тронуты.

**Kotlin — проверки (JVM)** (`.\gradlew.bat ktlintCheck detekt testGithubDebugUnitTest
lintGithubDebug --continue`, `JAVA_HOME` jdk-21.0.8):

- `ktlintCheck`, `detekt`, `lintGithubDebug` — чисто.
- `testGithubDebugUnitTest` — 894 теста, 1 пропущен, **7 упали — все в `DataStore*SettingsTest`**
  (`Folder` — 1, `Online` — 3, `Sort` — 1, `Onboarding` — 2): `IOException: Unable to rename
  …preferences_pb.tmp` в `FileStorage.kt:114`. Это известный сбой Windows из README («Известное»);
  там названо 4 теста, в этом прогоне 7 — переименование временного файла, к радио отношения нет
  (вне `DataStore*` не упал ни один тест). Решает CI на Linux.
- Четыре новых класса — 20 из 20: `ExternalProviderRadioTest` 6, `CoreOnlineRadioMappingTest` 4,
  `FakeOnlineRepositoryRadioTest` 6, `GatedOnlineRepositoryRadioTest` 4.

**Эмулятор** (`Medium_Phone_API_36.0`, `-no-window -no-audio -no-snapshot-save`, `ANDROID_SERIAL=emulator-5554`
в той же команде, что Gradle; фильтр по пакету, не списком классов):
`connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.package=io.github.puflik.plinth.ffi"`
— `BUILD SUCCESSFUL` за 2 мин 51 с. Число проверено по `TEST-*.xml`, а не по «зелёной» сборке:
выполнилось **35 тестов в 8 классах, 0 упавших, 0 пропущенных**; `OnlineRadioCoreTest` — **4 из 4**
(страницы доходят до репозитория с ключом и затравкой дословно; беда провайдера — `OnlineRadio.Failed`;
провайдер без радио, чужой и выключенные источники — `PROVIDER_DOWN`; `origins` после `add` отдаёт
источники, радио в каталог ничего не заводит). Остальные классы пакета (`PlinthCoreTest`,
`ExternalProviderCoreTest`, `CoreScanTest`, …) не пострадали. После прогона эмулятор погашен
(`adb emu kill`; процессов нет, `adb devices` пуст). На устройства автора ничего не ставилось:
Gradle был привязан к `emulator-5554`, а до запуска и после прогона `adb devices` других устройств
не показывал.

**Готово, когда — сверка.** Защищённые тесты зелёные: Rust 6 + 19, Kotlin JVM 6 + 4 + 6 + 4, на
эмуляторе 4. Остальные тесты Rust — как были (1000 зелёных); Kotlin — как были, кроме известных
`DataStore*SettingsTest` на Windows. `cargo clippy`/`fmt`, `ktlintCheck`, `detekt`, `lintGithubDebug`
чисты. В приложении ничего не видно: `YoutubeProvider` и всё из «Не трогать» не тронуты —
`git diff cc1b495 HEAD --name-only` содержит ровно шесть Rust-файлов, шесть Kotlin-файлов из «Менять»
и этот файл; защищённые тесты и `Cargo.lock`, `libs.versions.toml`, `CHANGELOG.md` не менялись.

**На что глянуть при ревью:**

- Единственный новый `@Suppress` — `("TooGenericExceptionCaught", "SwallowedException")` на
  `toRust().radio`, ровно как требует «Решение», Г (тот же комментарий, что у `search`/`resolve`/`stream`).
  Новых `unwrap`, `expect`, `panic!`, `#[allow]`, `#[ignore]` в диффе нет.
- `DataStore*SettingsTest`: упало 7, в README названо 4 — причина та же (`IOException` переименования
  временного файла, `FileStorage.kt:114`), радио к ним отношения не имеет; арбитр — CI на Linux.
- Для yu4c: «провайдер не умеет радио» и «провайдер не ответил» приложению неразличимы — оба
  `PROVIDER_DOWN` (так закреплено `yu4a_tests`: платформа без `radio` — `ProviderDown`). Что радио
  есть у YouTube, приложение знает не из ответа, а из `OnlineProviderIds` — так и задумано в
  «Отвергнуто» (поле `Capabilities` отвергнуто); до yu4b `YoutubeProvider` радио не умеет.
- Вне задачи, не тронуто: ADR 0008 (`related` → `radio`), план 6.1/9.2, `CHANGELOG.md`, `tasks.md` —
  правит Opus при слиянии.
