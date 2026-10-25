# yu4a — радио как способность провайдера: ядро и мост (Ю4, часть 1 из 3)

> Файл задачи для исполнителя (Sonnet). Пишет планировщик; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

> **Данные пользователя: план до исполнителя читает Opus.** Сама yu4a данных не пишет —
> `online_radio` и `online_origins` только читают. Но решения этого файла («Решение», таблица
> «Ю4 целиком») определяют, что yu4c запишет в очередь (`queue.bin`: новый вид контекста) и в
> каталог (треки радио заводятся через `add`, как треки альбома). Вопросы и сомнения —
> в «На что смотреть при чтении плана (Opus)».

- **Ветка:** `work/yu4a` · **Ворктри:** `C:\claude\Plinth-yu4a`
- **Основа:** `main` на `e95c935` · **Коммит тестов:** `<заполняется>` (второй в ветке; первый — черновик этого файла)
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

В `Registry` (`registry.rs`) — по образцу `resolve` (действие человека над конкретным треком: провайдера
спрашивают и в `Down`, исход идёт в здоровье через `observe`):

```rust
    pub fn radio(
        &self,
        provider: &ProviderId,
        seed: &ExternalId,
        page: Option<&ExternalId>,
        now: Timestamp,
    ) -> Result<RadioPage, CoreError>
```

Неизвестный провайдер — `Unavailable` (как у `resolve`: `find`). Дополнить комментарий к `Registry`: `radio`
— как `resolve`.

### Б. Граница с платформой: `external_api.rs`

В `trait ExternalProvider` (после `stream`):

```rust
    /// Радио от трека `seed` (id трека или его источника); `page` — ключ из прошлого ответа.
    /// Не умеет — `Failed { Unavailable }` (тело по умолчанию).
    fn radio(&self, seed: String, page: Option<String>) -> ExternalRadio {
        let _ = (seed, page);
        ExternalRadio::Failed { failure: ExternalFailure::Unavailable { text: "radio is not supported".to_owned() } }
    }

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ExternalRadio {
    Found { tracks: Vec<OnlineTrackInfo>, next: Option<String> },
    Failed { failure: ExternalFailure },
}
```

**Тело по умолчанию у метода внешнего трейта — проверено временной реализацией** (раздел «Временная реализация»):
результат записан там. Если UniFFI его не принял, метод идёт без тела, а в `Platform` из `yu3v_tests.rs` и
`yu3g_tests.rs` добавляется `fn radio(...)`, возвращающий `Failed { Unavailable }` (это единственная правка старых
тестов; больше — стоп).

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
  `registry.radio(.., Timestamp::now())`; `Ok(page)` → `OnlineRadioReply { tracks: page.tracks.map(track_info), next: page.next.map(|key| key.as_str().to_owned()), problem: None }`;
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
/** Радио от [seed] провайдера [provider] (id трека или его источника); [page] — ключ прошлой страницы, `null` — первая. */
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
- В тексте ошибки нет затравки, ключа, названий и текста паники платформы; затравка и ключ доходят до платформы
  **дословно** (в том числе не ASCII).
- Страница: не больше 100 треков, порядок платформы сохранён; негодный трек выброшен без потери страницы; страница
  только из негодных — `Parse`; негодный ключ — `Parse`.
- Провайдер без `radio` отвечает `Unavailable` и здоровью не вредит; сбой радио считается как сбой `resolve`
  (три подряд — отдых провайдера); радио спрашивается и у отдыхающего провайдера (действие человека).
- Существующее поведение не меняется: поиск, альбом, поток, здоровье. Старые тесты Rust и Kotlin проходят без правок
  (единственное допустимое исключение — вставка `radio` в `Platform` старых тестов, см. «Решение», Б).

## Файлы

- **Менять:**
  - Rust: `core/providers/src/model/radio.rs` (новый), `core/providers/src/model/mod.rs`,
    `core/providers/src/provider.rs`, `core/providers/src/registry.rs`, `core/ffi/src/api/external_api.rs`,
    `core/ffi/src/api/online_api.rs`;
  - Kotlin: `app/src/main/kotlin/io/github/puflik/plinth/ffi/CoreOnline.kt`, `.../ffi/CoreOnlineProvider.kt`,
    `.../online/OnlineRepository.kt`, `.../online/CoreOnlineRepository.kt`, `.../online/GatedOnlineRepository.kt`,
    `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/FakeOnlineRepository.kt`;
  - только если тело по умолчанию у `ExternalProvider::radio` не принято UniFFI: `core/ffi/src/api/yu3v_tests.rs`,
    `core/ffi/src/api/yu3g_tests.rs` (вставка `fn radio`).
- **Не трогать:** `online/YoutubeProvider.kt`, `online/youtube/*`, `online/OnlineProviders.kt`,
  `online/OnlineProviderIds.kt` (yu4b, yu4c); `queue/*`, `audio/*`, `ui/*` (yu4c); `Cargo.lock`,
  `gradle/libs.versions.toml`; `CHANGELOG.md`, `tasks*.md`, `docs/decisions.md`, `docs/adr/*`, `docs/plan/*`, `CLAUDE.md`
  (при слиянии их правит Opus: ADR 0008 — поправка «`related` → `radio`», план 6.1, 9.2).
- **Защищённые тесты** (из коммита тестов; не менять, не удалять, не глушить):
  - `core/providers/tests/yu4a_radio.rs`
  - `core/ffi/src/api/yu4a_tests.rs` (и строка `mod yu4a_tests;` в `core/ffi/src/api/mod.rs` — она в коммите тестов)
  - `app/src/test/kotlin/io/github/puflik/plinth/ffi/ExternalProviderRadioTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/ffi/CoreOnlineRadioMappingTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/online/FakeOnlineRepositoryRadioTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/online/GatedOnlineRepositoryRadioTest.kt`
  - `app/src/androidTest/kotlin/io/github/puflik/plinth/ffi/OnlineRadioCoreTest.kt`

## Тесты (написаны в коммите тестов)

До реализации ничего из этого не собирается (новый API: `RadioPage`, `Provider::radio`, `Registry::radio`,
`ExternalRadio`, `online_radio`, `online_origins`, `OnlineRadio`, `OnlineRadioPage`, …) — сигнатуры выше.

**`core/providers/tests/yu4a_radio.rs`** — реестр и трейт:
- провайдер без `radio` отвечает `Unavailable`; реестр, спрошенный пять раз, оставляет ему здоровье `Ok`;
- реестр отдаёт затравку и ключ провайдеру дословно, первая страница → `next = Some`, по ключу — вторая, `next = None`;
- неизвестный провайдер — `Unavailable`;
- три сбоя радио (`Network`) подряд — провайдер отдыхает, а четвёртый вызов всё равно доходит до него.

**`core/ffi/src/api/yu4a_tests.rs`** — `Core` с платформенным провайдером в памяти:
- первая страница: треки как пришли (id, название, варианты), ключ, нет беды; по ключу — вторая и последняя;
- затравка-вариант `dQw4w9WgXcQ/251` и ключ с кириллицей и пробелами доходят платформе дословно;
- негодный трек выброшен, страница жива; страница только из негодных — `Broken`; пустая страница с ключом и без;
- больше 100 треков — первые 100; негодный ключ платформы — `Broken`;
- таблица бед платформы → `NoNetwork`/`ProviderDown`/`RateLimited`/`Broken` (как у альбома); `Internal` — ошибка;
- паника платформы — `Internal` без её текста, следующий вызов работает;
- источники выключены — `Unavailable` ошибкой; чужой провайдер — `ProviderDown`; негодные аргументы — `Parse`, платформа не зовётся;
- платформа без `radio` — `ProviderDown`, поиск по ней жив, здоровье цело;
- три сбоя подряд — отдых, четвёртый вызов доходит и получает страницу;
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
- скорость: не критерий — радио не на горячем пути.

## Остановиться и написать, если

- защищённый тест кажется неверным;
- сгенерированные Kotlin-имена не те, что в тестах (`ExternalRadio.Found(tracks, next)`, `OnlineRadioReply`, `OnlineOrigin`);
- UniFFI не принимает тело по умолчанию и для этого нужно больше, чем вставка `fn radio` в `Platform` старых тестов;
- нужно менять `Capabilities` или файл вне списка «Менять»;
- проверки упали дважды подряд.

## На что смотреть при чтении плана (Opus)

1. **Каталог растёт радио.** `add` на каждую страницу заводит до 25 строк `Track`/`Version`/`Source`, а ещё
   исполнителей и альбомы по имени (`insert_online`: `artist_by_name`, `album_by_title`). Заметки в `online_repo.rs`
   говорят, что вкладки библиотеки сетевых треков не показывают, но показывают ли они исполнителей и альбомы, заведённые
   только сетевыми треками? Играть альбом YouTube уже так делает, радио умножает в 25 раз и без границы. Чистки каталога
   нет. Предел 300 в контексте — на одну очередь, не на каталог. Годится ли «как есть» или нужна граница/чистка
   (отдельная задача)? Журнал `add` не пишет — проверь, что это так для `add_online_tracks`.
2. **`queue.bin` без подъёма версии.** Новый вид контекста `radio` в формате 2; понижение версии приложения
   теряет очередь молча. Альтернатива — версия 3 с чтением обеих: тогда понижение тоже теряет очередь, а
   обновление безопасно лишь при корректном чтении 2. Какой вариант принимаешь?
3. **Здоровье.** Сбой радио идёт в здоровье YouTube так же, как `resolve`; три подряд положат и поиск по нему на пять
   минут. Mix — другой эндпоинт, чем поиск; не слишком ли строго?
4. **Затравка — «источник или id трека»** (двойной смысл `seed`): каталог хранит только варианты, поэтому ядро не
   знает id видео. Принимать оба должен провайдер (YouTube: `VIDEO_ID` или `VARIANT`). Нет ли чище?
5. **`online_radio` и `ExternalProvider::radio` с телом по умолчанию.** Проверено временной реализацией (ниже);
   если UniFFI тело не принял — правка двух старых тестов.
6. **Название `radio` вместо `related`** — поправка к ADR 0008 и плану 6.1 (`related(id) → [SearchResult]` →
   `radio(seed, page) → RadioPage`); текст поправки напишет Opus при слиянии.

## Временная реализация и проверка тестов

_Заполняет планировщик после проверки: что собиралось, что падало, где лежит патч._

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

## Отчёт исполнителя

_Заполняет исполнитель: что сделано, что не вышло и почему, коммиты, вывод проверок (коротко)._
