# yu3b — источник YouTube на Kotlin: `YoutubeSource`, загрузчик, выбор потока (Ю3б)

> Файл задачи для исполнителя (Sonnet). Пишет планировщик; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

- **Ветка:** `work/yu3b` · **Ворктри:** `C:\claude\Plinth-yu3b`
- **Основа:** `main` на `cb44a9e` — **с Ю2** (Opus слил `work/r1-11d` и перебазировал ветку 2026-10-20): `check_http_status`
  (`core/ffi/src/api/online_api.rs`) в `main` есть.
  **Коммит тестов:** `908840c` (первый в ветке; до перебазирования на `main` с Ю2 был `a8dce70`).
- **Откуда:** `tasks.md`, строка 389 (Ю3); `docs/work/yu1.md`, разделы 3.1 и 6; `docs/work/yu3a.md`, разделы 3.3, 4, 5, 8 (код спайка —
  ветка `spike/yu3a`: образец, не копия); `docs/work/r1-11d.md` на ветке `work/r1-11d`, раздел «Г» (общая таблица статусов Ю2).
- **Данные пользователя не трогает** (сеть и пакет без экрана, в приложение пока не подключён): Opus план до исполнителя не читает.

## Цель

Когда задача сделана:

1. В приложении есть **`YoutubeSource`** — узкий интерфейс `search` / `resolve` / `stream` на NewPipeExtractor `v0.26.5`. Только он знает про
   экстрактор; исключения экстрактора доходят как `Network` / `Parse` / `Unavailable`.
2. **Загрузчик экстрактора на Kotlin**: GET, POST, HEAD, gzip, `User-Agent` клиента. Статус каждого ответа решает **общая таблица ядра**
   `check_http_status` (Ю2) — второй таблицы нет. Срок вызова ограничен: ядро прервать вызов не может.
3. **Выбор потока: Opus 251, иначе AAC 140** (AAC 141 без входа нет) — как порядок вариантов трека, по которому ядро выберет первый, что ответит.
4. Сборка тянет экстрактор через JitPack (`includeGroup`), с десугарингом библиотек; версия экстрактора — в одном месте.
5. Всё проверено JUnit-тестами на **записанных ответах YouTube**, без сети. Пока `YoutubeSource` ни к чему не подключён (подключение — Ю3в).

## Контекст

- `docs/work/yu1.md`: 3.1 (таблица «что менять», строки 2, 3, 5) и 6 (состав Ю3б, порядок Ю3б → Ю3в → Ю3г).
- `docs/work/yu3a.md`: 3.3 (заголовок `Range`), 4 (Gradle, `Downloader`, заголовки, десугаринг), 5 (риски: п. 1 всё держит `VISIONOS`, п. 2 один
  `resolve` тяжёл, п. 10 — 403), 8 (телефон).
- Образцы, читать как чужой код: `git show spike/yu3a:app/src/main/kotlin/io/github/puflik/plinth/spike/SpikeDownloader.kt`, `…/SpikeRunner.kt`
  (`search`, `loadStream`, `userAgentFor`, `mix`), `git diff main spike/yu3a -- settings.gradle.kts gradle/libs.versions.toml app/build.gradle.kts`.
- `docs/work/r1-11d.md` (ветка `work/r1-11d`), раздел «Г»: `check_http_status(status: u16, retry_after: Option<String>)` и грамматика текста `Network`.
- Что Ю3в будет отображать: `core/providers/src/provider.rs`, `core/providers/src/model/*.rs` (`SearchResult`, `RemoteTrack`, `SourceOption`,
  `StreamRequest`, `ExternalId`), `core/providers/src/registry.rs` (`stream` идёт по вариантам и берёт первый, что ответил).
- Стиль и граница: `online/HttpUrlTransport.kt`, `ffi/CoreOnline.kt`, `ffi/CoreFailure.kt`, `FfiBoundaryTest.kt`, `SourceTree.kt`.
- Записи и что экстрактор делает с ними: `app/src/test/resources/youtube/README.md`; как их записать заново — `tools/youtube-record/README.md`.

## Решение

### А. Граница задачи

Граница Ю3б (решение планировщика: заголовок `Range` и правила R8 `yu1.md` относит к Ю3в и Ю3г как воспроизведение и сборку, поэтому здесь они разобраны отдельно):

| Что | Где | Почему |
|---|---|---|
| `YoutubeSource`, реализация на экстракторе, загрузчик, перевод исключений, срок вызова, выбор потока | **Ю3б** | это и есть задача |
| JitPack + `includeGroup`, версия экстрактора и десугаринга в `libs.versions.toml`, `isCoreLibraryDesugaringEnabled` | **Ю3б** | без них нет экстрактора в сборке и тестах |
| `User-Agent` клиента и `Range: bytes=0-` **в `YoutubeStream.headers`** | **Ю3б** | это данные, которые отдаёт источник; проверяются тестами на записях |
| Обёртка `ffi/CoreHttpStatusTable` над `check_http_status` | **Ю3б** | «статусы разбирает общая таблица»; единственное, что требует Ю2 в `main` |
| Правила R8 | **Ю3г** (см. п. Л: в Ю3б — только если release-сборка иначе не собирается) | проверить, что правил хватает, можно только запуском release на устройстве |
| Внешний `trait` ядра, адаптер `Provider`, `connect_online`, DI, ADR 0008 | Ю3в | |
| Media3: принять заголовки в `StreamRequest`, минута игры, перехват 403 и повтор | Ю3в, Ю6 | проверяется только на телефоне |
| Обе flavor, `NonFreeNet`, переключатель, `NOTICE`, заголовки лицензий | Ю3г | |

### Б. Пакет `io.github.puflik.plinth.online.youtube` — файлы и типы дословно

`app/src/main/kotlin/io/github/puflik/plinth/online/youtube/`: `YoutubeSource.kt`, `HttpStatusTable.kt`, `CallBudget.kt`, `YoutubeDownloader.kt`,
`NewPipeYoutubeSource.kt`. Пакет **не зависит от `ffi`** (наоборот: `ffi` реализует его интерфейс) и единственный импортирует `org.schabi.newpipe`;
`YoutubeSource.kt` и `HttpStatusTable.kt` не показывают экстрактор вовсе (`YoutubeBoundaryTest`).

```kotlin
interface YoutubeSource {
    fun search(query: String, limit: Int): List<YoutubeResult>
    fun resolve(id: String): List<YoutubeTrack>
    fun stream(variant: String): YoutubeStream
}
enum class YoutubeKind { TRACK, ALBUM }
data class YoutubeResult(val id: String, val kind: YoutubeKind, val title: String, val artist: String? = null, val duration: Duration? = null)
enum class YoutubeFormat { OPUS, AAC }
data class YoutubeVariant(val id: String, val format: YoutubeFormat, val bitrateKbps: Int)       // id = "<видео>/<itag>"
data class YoutubeTrack(val id: String, val title: String, val artist: String? = null, val album: String? = null,
                        val number: Int? = null, val duration: Duration? = null, val variants: List<YoutubeVariant>)
data class YoutubeStream(val url: String, val headers: List<Pair<String, String>>, val format: YoutubeFormat, val bitrateKbps: Int)
sealed class YoutubeException(val text: String, cause: Throwable? = null) : Exception(text, cause) {
    class Network(text: String, cause: Throwable? = null) : YoutubeException(text, cause)
    class Parse(text: String, cause: Throwable? = null) : YoutubeException(text, cause)
    class Unavailable(text: String, cause: Throwable? = null) : YoutubeException(text, cause)
}

fun interface HttpStatusTable { fun verdict(status: Int, retryAfter: String?): HttpVerdict }
sealed interface HttpVerdict {
    data object Fine : HttpVerdict                                  // 2xx
    data class Unavailable(val text: String) : HttpVerdict          // 404, 410
    data class Network(val text: String) : HttpVerdict             // остальное; "http 503", "http 429, retry after 30 s"
}

internal object CallBudget {                                         // срок вызова на потоке (ThreadLocal)
    fun <T> within(budget: Duration, block: () -> T): T
    fun leftMillis(): Long?                                          // null — срока нет; 0 — вышел
    fun failIfSpent()                                                // SocketTimeoutException, если вышел
}
internal class YoutubeDownloader(statusTable: HttpStatusTable) : org.schabi.newpipe.extractor.downloader.Downloader()
internal class HttpRefused(val verdict: HttpVerdict) : IOException(<текст решения таблицы>)   // verdict — Network или Unavailable
internal data class YoutubeBudgets(val search: Duration = 10.seconds, val extract: Duration = 25.seconds)

class NewPipeYoutubeSource internal constructor(
    downloader: Downloader, statusTable: HttpStatusTable,
    budgets: YoutubeBudgets = YoutubeBudgets(), nanoTime: () -> Long = System::nanoTime,
) : YoutubeSource {
    constructor(statusTable: HttpStatusTable) : this(YoutubeDownloader(statusTable), statusTable)   // так её соберёт Ю3в
}
```

`NewPipe.init(загрузчик, Localization.DEFAULT, ContentCountry.DEFAULT)` ставится **в начале каждого вызова** (внутри обёртки, ловящей беды), а не в конструкторе: состояние экстрактора
глобально, а тесты создают несколько источников подряд — каждый обязан работать со своим загрузчиком.

KDoc — по-русски и по делу, как в `HttpUrlTransport`. Обёртка ядра: `app/src/main/kotlin/io/github/puflik/plinth/ffi/CoreHttpStatusTable.kt`:

```kotlin
object CoreHttpStatusTable : HttpStatusTable      // checkHttpStatus(status.toUShort(), retryAfter) → Fine / verdictOf(CoreException)
internal fun verdictOf(error: CoreException): HttpVerdict
```

`verdictOf`: текст ядра приходит с категорией («network: http 503, retry after 7 s») — **снять всё до первого `": "`, если оно есть** (в тексте таблицы
двоеточия нет, и он уйдёт обратно в `CoreError` как есть); `CoreException.Unavailable` → `Unavailable(text)`, **любой другой** отказ → `Network(text)`
(ответ, который таблица не разрешила, наружу не отдаётся).

### В. Идентификаторы и варианты

- Результат поиска: видео — 11 знаков `[A-Za-z0-9_-]`; альбом — плейлист `OLAK5uy_…` (`OLAK5uy_[A-Za-z0-9_-]{8,64}`). Любой другой `id` в `resolve` —
  `Unavailable("id")` **без сети** (ID уходит в адрес запроса; подставить в него нельзя ничего).
- **Вариант источника — формат:** `<видео>/<itag>`, itag только **251** (Opus, 160 кбит/с) и **140** (AAC, 128). `stream` с другим itag, без itag, с
  мусором — `Unavailable("variant")` без сети. 141, 250, 249, 139 не предлагаются никогда.
- Почему варианты, а не «`stream` выбирает сам»: ядро уже умеет всё нужное — `playback_order` ставит Opus 160 раньше AAC 128, `Registry::stream` идёт к
  следующему варианту, если `stream_url` предыдущего ответил `Unavailable`, а `online_stream(track, metered, undecodable)` умеет «пробовать этот формат
  последним». Выбор «Opus, иначе AAC» и повтор в другом формате после отказа ссылки — это существующие механизмы, не новый код и не новый параметр
  `Provider`.
- `resolve(видео)` отдаёт варианты, **которые у видео есть** (Opus, если есть 251; AAC, если есть 140; порядок: Opus первым). `resolve(альбом)` — оба
  варианта у каждого трека (номинальные: формат известен только после извлечения, а оно 2–3 с на трек).

### Г. `search(query, limit)`

- Пустой после `trim()` запрос — пустой список, **сети нет**. `limit` приводится к 1..100.
- Два поиска YouTube Music: песни (`YoutubeSearchQueryHandlerFactory.MUSIC_SONGS`), затем альбомы (`MUSIC_ALBUMS`): `ServiceList.YouTube.getSearchExtractor(текст,
  listOf(фильтр), "")`, `fetchPage()`, `initialPage.items`. Результат: песни, потом альбомы, всего не больше `limit`; **альбомам — четверть мест**: песен не больше
  `limit - limit/4`, если альбомы нашлись (иначе `limit`), альбомов — остаток. Нехватка песен отдаёт места альбомам.
- Песня: `id` = `v=` из адреса, `title` = название, `artist` = автор (без суффикса « - Topic»; пустой — `null`), `duration` = секунды (≤ 0 — `null`).
  Альбом: `id` = `list=` (`OLAK5uy_…`), `title`, `artist`, `duration` = `null` (длительности и числа треков в поиске нет). Элементы без названия или без id
  пропускаются. Другие виды элементов пропускаются.
- Любая беда любого из двух поисков — вся поиск-ошибка (без частичного ответа: тихая потеря альбомов скрыла бы сломанный формат).

### Д. `resolve(id)`

- **Видео:** одно извлечение — `getStreamExtractor("https://www.youtube.com/watch?v=<id>")`, `fetchPage()`, `name`, `uploaderName`, `length`, `audioStreams`.
  Один трек: `title` = `name` (пустое — `Parse("no title")`), `artist` = `uploaderName` без суффикса « - Topic», `duration` = `length` (≤ 0 — `null`),
  `album`/`number` = `null`, `variants` — см. п. В. Берутся только потоки `isUrl` и `deliveryMethod == PROGRESSIVE_HTTP`. Нет ни одного аудиопотока —
  `Parse("no audio streams")`; нет ни 251, ни 140 — `Parse("no supported audio stream")` (оба — сломанный формат, а не «нет видео»: нет видео — исключение
  экстрактора, п. Ж).
  Потоки извлечения откладываются **заготовкой** (п. Е).
- **Альбом:** `getPlaylistExtractor("https://www.youtube.com/playlist?list=<id>")`, `fetchPage()`, `name`, `initialPage.items` (первая страница, до 100 треков;
  `hasNextPage` не читается). `album` = `name` без начала «Album – » (с длинным тире); у треков `id`, `title`, `artist` (без « - Topic»), `duration`, `number` =
  положение в плейлисте + 1 (по исходному порядку), `album`, оба номинальных варианта. Без единого трека — `Unavailable("empty album")`.

### Е. `stream(variant)`, заготовка и «отказ первой ссылки» (yu3a.md, раздел 5, п. 10)

- `stream` разбирает вариант, берёт потоки видео (из заготовки или **новым извлечением**) и отдаёт поток с нужным itag; нет такого у видео —
  `Unavailable("variant")`.
- `YoutubeStream`: `url` = `AudioStream.content`; `headers` = `User-Agent` клиента, выдавшего ссылку (для `VISIONOS` — `YoutubeParsingHelper.getVisionOsUserAgent(Localization.DEFAULT)`;
  для ссылки другого клиента — без `User-Agent`: чужого не угадываем, останется умолчание плеера) и **`Range: bytes=0-`** — именно в таком порядке; без `Range`
  первый запрос идёт со скоростью 10 КиБ/с (yu3a.md, 3.3). `format`/`bitrateKbps` — OPUS/160 для 251, AAC/128 для 140.
- **Заготовка потоков** (в `NewPipeYoutubeSource`): `resolve(видео)` положил свежие потоки видео; первый же `stream` этого видео берёт их — игра по нажатию стоит
  одного извлечения (2–3 с), не двух. Заготовка **одноразовая и короткая**: удачный `stream` её забирает; живёт не дольше **2 минут** (по `nanoTime`); хранит не
  больше 8 видео; по видео, не по варианту. Если нужного варианта в заготовке нет — `Unavailable` и **заготовка остаётся** (ядро сразу придёт за вторым
  вариантом). Нет заготовки — извлечение; в нём нет нужного itag — потоки кладутся в заготовку, `Unavailable("variant")`.
- **Как это переживает отказ первой ссылки.** Повтор (`stream` ещё раз после удачного) — **всегда новое извлечение и другая ссылка**: ссылку, на которую плеер
  получил 403, источник второй раз не отдаёт (`YoutubeStreamTest`). «Другой формат» — другой вариант: плеер просит `online_stream` с `undecodable + {OPUS}`
  (существующий параметр), ядро пробует AAC первым. **Не в Ю3б (Ю3в/Ю6):** перехват 403 на первом чтении (позиция 0), выбрасывание ссылки из кэша
  `OnlineResolver.loads`, один повтор через `OnlineStreams`; на позиции > 0 формат менять нельзя (другой контейнер), только та же ссылка заново. Проверить без
  телефона нельзя — см. «Принятые ограничения».

### Ж. Беды (исключение → `YoutubeException`)

Все вызовы `YoutubeSource` ловят `Exception` (с `@Suppress("TooGenericExceptionCaught")` и пояснением: экстрактор бросает что угодно). Порядок — по цепочке
`cause` (до 8 звеньев), первое сработавшее правило:

| Что встретилось в цепочке | Результат | Текст |
|---|---|---|
| сам `YoutubeException` | как есть | |
| `HttpRefused` (загрузчик получил отказ по таблице) | `Network` или `Unavailable` по решению таблицы | **текст таблицы без изменений** («http 503, retry after 7 s») |
| `SocketTimeoutException` (в том числе срок вызова) | `Network` | `timed out` — ровно так ядро пишет «не дождались» (`timed_out()` в `status.rs`; в FFI её нет — литерал) |
| `ReCaptchaException`, `SignInConfirmNotBotException` («подтвердите, что вы не бот») | `Network` | то, что скажет таблица на **429 без срока** (`statusTable.verdict(429, null)`); «просит подождать», а не «видео нет» |
| другой `IOException` | `Network` | имя класса (`UnknownHostException`) — как `HttpUrlTransport` называет «нет ответа» |
| `ContentNotAvailableException` (и приватное, возрастное, региональное…), `ContentNotSupportedException` | `Unavailable` | имя класса |
| `InterruptedException` | `Network`, флаг прерывания возвращается | `interrupted` |
| всё остальное (`ParsingException`, `ClassCastException`, …) | `Parse` | имя класса |

**В тексте никогда нет** сообщения исключения, адреса, запроса, названий (ADR 0008; сообщения экстрактора содержат адреса) — только имя класса или решение
таблицы. `cause` остаётся в Kotlin и в журнал не идёт. Kotlin не строит и не разбирает «http N»: единственный литерал грамматики — `timed out`.

### З. Срок вызова (`CallBudget`)

Ядро ждёт `YoutubeSource` на своём потоке и прервать вызов не может. Каждый вызов ставит срок на поток: **поиск 10 с, `resolve`/`stream` с извлечением 25 с**
(`YoutubeBudgets`). Перед каждым запросом экстрактора срок проверяется (`Budgeted`-обёртка загрузчика внутри `NewPipeYoutubeSource`: вышел — `SocketTimeoutException`);
таймауты соединения (15 с) и чтения (30 с) загрузчика не больше остатка (минимум 1 мс); между чтениями тела срок проверяется. Срок живёт только вызов
(`finally` возвращает прежний/снимает). DNS срок не ограничивает (как у `HttpUrlTransport`).

### И. Загрузчик (`YoutubeDownloader`)

`execute(request)`: `CallBudget.failIfSpent()`; `HttpURLConnection` к `request.url()`, метод из `request.httpMethod()`, `instanceFollowRedirects = true`;
`User-Agent` — запасной (Firefox ESR, как в спайке), затем заголовки запроса (первое значение `setRequestProperty`, остальные `addRequestProperty`: заголовок экстрактора
перекрывает запасной); `Accept-Encoding: gzip` **всегда** последним (br и deflate разбирать нечем); тело `dataToSend()` для POST (`Content-Type` задаёт экстрактор).
**Каждый ответ** — через `statusTable.verdict(status, значение заголовка Retry-After как пришло или null)`: `Fine` — читать тело, иначе `throw HttpRefused(verdict)` —
тело отказа экстрактору не показывается (HTML страницы ошибки он принял бы за «сломанный формат»). Тело: gzip при `Content-Encoding: gzip`, UTF-8, не больше 8 МиБ
(больше — `IOException`; `base.js` экстрактора ~2,5 МиБ проходит), HEAD — пустое. `Response(status, message, заголовки без null-ключа, тело, итоговый адрес)`.
Журнала нет (адреса и тела в нём быть не должно).

### К. Таблица статусов

`HttpStatusTable` — интерфейс пакета; настоящая реализация `ffi/CoreHttpStatusTable` (п. Б). Грамматика ответа — Ю2 (`r1-11d.md`, «А» и «Г»): 2xx — `Fine`; 404, 410 —
`Unavailable("http N")`; остальное — `Network("http N")`, у 429 и 5xx со сроком `Network("http N, retry after S s")` (целые секунды; дата и мусор срока не дают).
Её текст едет дальше как есть: Ю3в положит его в `CoreError::Network`, и `NetFailure::of`/`Health::after` ядра прочтут статус и срок (провайдер отдохнёт ровно
столько, сколько просил сервер). Тесты используют двойник таблицы (`FakeStatusTable`): настоящую в JVM-тестах не вызвать (в сборке нет библиотеки для хоста);
настоящий ответ сверяет **инструментальный** `CoreHttpStatusTableTest`.

### Л. Сборка

- `settings.gradle.kts`: репозиторий `maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe") } }` в `dependencyResolutionManagement` (группа покрывает и
  `nanojson`, которого нет в Maven Central).
- `gradle/libs.versions.toml`: `newpipeExtractor = "v0.26.5"` и библиотека `newpipe-extractor` (`com.github.TeamNewPipe:NewPipeExtractor`); `desugarJdkLibs = "2.1.5"` и
  библиотека `desugar-jdk-libs-nio` (`com.android.tools:desugar_jdk_libs_nio`). Версий в других файлах нет.
- `app/build.gradle.kts`: `compileOptions { isCoreLibraryDesugaringEnabled = true }` (экстрактор зовёт API 33 — `URLEncoder.encode(String, Charset)`,
  `Collectors.toUnmodifiableList`; minSdk 26), `implementation(libs.newpipe.extractor)`, `coreLibraryDesugaring(libs.desugar.jdk.libs.nio)`.
- **R8** (`app/proguard-rules.pro`): правила NewPipe (Rhino, `timeago.patterns`, protobuf) и их сужение — Ю3г. В Ю3б правило трогается **только если**
  `.\gradlew.bat minifyGithubReleaseWithR8` (или `assembleGithubRelease`) без него падает на «Missing classes» — тогда добавь `-dontwarn` для названных классов
  (`javax.script.**`, `jdk.dynalink.**`, `org.mozilla.javascript.JavaToJSONConverters`, `org.mozilla.javascript.tools.**` — как в спайке) и запиши это в отчёте. `-keep`
  не добавляй: пока `YoutubeSource` ни к чему не подключён, R8 вырезает пакет целиком, а проверить правила можно только запуском. Результат этой проверки
  — в «Отчёт исполнителя».

### Принятые ограничения

- **403 на чтении потока (телефон) без телефона не проверить.** Источник даёт то, что в его силах (свежая ссылка на каждый вызов, вариант в другом формате); перехват в
  плеере — Ю3в/Ю6. Если 403 не про смену адреса (в прогоне AAC 140 получил 403 четыре раза подряд, Opus играл), повтор той же ссылки не поможет, а другой формат — да.
- **Всё держит клиент `VISIONOS`**; запасных клиентов у экстрактора нет (yu3a.md, п. 1). Закроют его — `Parse("no audio streams")` или 403 при чтении; чинит новый
  выпуск экстрактора. Версия — в одном месте (`libs.versions.toml`).
- Одно извлечение — 7–8 запросов, 2–3 с (yu3a.md, п. 2). Предзагрузка следующего трека — Ю3в/Ю6.
- Альбом: только первая страница (~100 треков); исполнитель альбома, год и число треков экстрактор не отдаёт (`artist` у трека — из элемента плейлиста; `year` нет).
- Прямой эфир, премьера (нет аудиопотоков) дают `Parse("no audio streams")`, а не `Unavailable`.
- Состояние экстрактора глобально (`NewPipe.init`): источник один на процесс; два источника **одновременно** не рассчитаны (подряд — да: каждый вызов ставит свой загрузчик).
- Десугаринг на Android 8–12 проверен только статически (yu3a.md, раздел 4); прогон на `Plinth_API_26` и `Plinth_API_30` — до выпуска.
- Инструментальный `CoreHttpStatusTableTest` планировщик не запускал (эмулятор был занят Ю2) — только собрал и сверил с грамматикой Ю2.
- `Retry-After` датой игнорируется — решение Ю2.

### Отвергнуто

- **Один вариант на трек, «251, иначе 140» внутри `stream`** — повтор после отказа в другом формате пришлось бы тащить новым параметром через `Provider::stream_url`
  (изменение Rust); варианты по формату используют то, что в ядре уже есть.
- **Память о последних результатах поиска для `resolve(видео)`** (чистые метаданные без сети) — состояние ради 2–3 с; после снятия « - Topic» название и автор
  извлечения совпадают с поиском.
- **Бросать `ReCaptchaException` на 429, как `DownloaderImpl` NewPipe** — 429 теперь решает таблица; капчи в Plinth нет.
- **OkHttp в загрузчике** — лишняя зависимость; `HttpURLConnection`, как `HttpUrlTransport` и спайк.
- **Срок через отдельный поток и `Future.get`** — зависший запрос всё равно не прервать; срок ставится на таймауты соединения.
- **Разбирать «http N» из текста в Kotlin** — вторая таблица (решение Ю2).
- **Живой YouTube в CI** — нестабильно; ручной скрипт «живой YouTube» перед выпуском — Ю3г.

## Инварианты

- Экстрактор виден только пакету `online/youtube`; пакет не импортирует `ffi`.
- Любая беда — `YoutubeException`; в тексте нет сообщения исключения, адреса, запроса, названий; Kotlin не строит «http N».
- Каждый `stream` после удачного — новое извлечение и другая ссылка; исключение — одноразовая заготовка `resolve` (до 2 минут).
- Вызов не длится дольше своего срока плюс один блок чтения; срок не переживает вызов.
- Из пустого запроса и плохого `id`/варианта сети нет.
- Только 251 и 140; Opus раньше AAC; в заголовках потока `Range: bytes=0-`.
- Тело отказа экстрактору не отдаётся; статус решает таблица ядра.
- Существующие тесты проходят без правок (кроме известных четырёх `DataStore*SettingsTest` на Windows).

## Файлы

- **Менять (новые):** `app/src/main/kotlin/io/github/puflik/plinth/online/youtube/{YoutubeSource,HttpStatusTable,CallBudget,YoutubeDownloader,NewPipeYoutubeSource}.kt`;
  `app/src/main/kotlin/io/github/puflik/plinth/ffi/CoreHttpStatusTable.kt`.
- **Менять (существующие):** `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`; `app/proguard-rules.pro` — только `-dontwarn` и только по п. Л.
- **Не трогать:** `CHANGELOG.md`, `tasks*.md`, `docs/decisions.md`, `CLAUDE.md`, план, `NOTICE`, заголовки лицензий, `core/**`, `di/**`, `audio/**` (в том числе `OnlineResolver`),
  `online/*.kt` (кроме нового пакета), `AndroidManifest.xml`, ADR, чужие тесты, `tools/youtube-record/`.
- **Защищённые тесты** (из коммита тестов; не менять, не удалять, не глушить):
  - `app/src/test/kotlin/io/github/puflik/plinth/online/youtube/YoutubeSearchTest.kt` (10), `YoutubeResolveTest.kt` (9), `YoutubeStreamTest.kt` (11), `YoutubeErrorsTest.kt` (9),
    `YoutubeDownloaderTest.kt` (10), `YoutubeBoundaryTest.kt` (4); помощники `ReplayDownloader.kt` (+`FakeStatusTable`, `Recorded`), `LoopbackServer.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/ffi/CoreHttpStatusVerdictTest.kt` (3)
  - `app/src/androidTest/kotlin/io/github/puflik/plinth/ffi/CoreHttpStatusTableTest.kt` (4, на эмуляторе)
  - `app/src/test/resources/youtube/**` (записи, `routes.tsv`, `README.md`)

## Тесты (написаны в коммите тестов)

На базовом коде **не собираются** — задача вводит новый API (сигнатуры — п. Б). Сеть — только записи (`ReplayDownloader`: настоящий экстрактор, ответы из
`src/test/resources/youtube/`) и петля (`LoopbackServer`). Таблица статусов — двойник `FakeStatusTable`.

- **`YoutubeSearchTest`:** песни, затем альбомы, поля первого результата и первого альбома; кириллица; четверть мест альбомам (лимит 8 → 6+2, 4 → 3+1, 3 → 3, 1 → 1);
  места песен отдаются альбомам и наоборот; предел 1..100; пустой запрос не спрашивает никого (и собранный «по-боевому» источник тоже); «ничего не нашлось» — пустой список,
  два поиска, запрос обрезан; упавший поиск альбомов роняет весь поиск; повторяемость.
- **`YoutubeResolveTest`:** трек — один, Opus раньше AAC, автор без « - Topic», длительность, одно извлечение; кириллица; только те варианты, что у видео есть;
  141 и «нет аудио» — `Parse`; альбом — 13 треков по номерам, оба варианта, название без «Album – », ни одного извлечения треков; кириллический альбом; пустой альбом —
  `Unavailable`; чужие и испорченные id — `Unavailable` без сети; недоступное/приватное видео — `Unavailable`, «бот» — `Network` по таблице (429 без срока), HTML — `Parse`.
- **`YoutubeStreamTest`:** вариант 251 — ссылка из записи (+ случайный `cpn` экстрактора), заголовки `User-Agent` клиента и `Range`, OPUS/160; 140 — AAC/128; ссылка другого клиента — только
  `Range`; только 251 и 140, мусор — `Unavailable` без сети; нет Opus — `Unavailable`, AAC после этого без второго извлечения; каждый вызов — новое извлечение и другая ссылка
  (A, затем B); другой формат после отказа — тоже новое; заготовка `resolve` уходит первому `stream` и только ему; живёт ≤ 2 мин (119 с жива, 3 мин — нет); заготовка одного видео другому не
  достаётся; вариант, которого в заготовке нет, — `Unavailable` без сети и заготовка остаётся.
- **`YoutubeErrorsTest`:** текст таблицы доходит как есть (в том числе из-под обёртки экстрактора); таймаут — `timed out`; нет соединения — имя класса; капча — таблица 429; остальное — `Parse` с
  именем класса; в текстах нет `SECRET`/адреса; прерывание остаётся прерыванием; вызов дольше срока кончается `timed out` быстрее, чем набрались бы все запросы, срок не липнет.
- **`YoutubeDownloaderTest`** (сокеты петли): GET — заголовки экстрактора, запасной `User-Agent`, `gzip`, UTF-8, заголовки ответа; POST — тело байт в байт, `Content-Type`, `Content-Length`; gzip распаковывается;
  HEAD без тела; редирект и итоговый адрес; каждый ответ спрашивает таблицу, `Retry-After` как пришёл (в том числе датой), отказ — `HttpRefused` с решением таблицы и без тела; тело > 8 МиБ — ошибка;
  молчащий сервер ждётся не дольше срока вызова; вышедший срок — ошибка до соединения и срок не переживает вызов.
- **`YoutubeBoundaryTest`:** экстрактор импортируется только в `online/youtube`; интерфейс и типы его не показывают; пакет не знает `ffi`.
- **`CoreHttpStatusVerdictTest`:** категория ядра снимается («network: …», «unavailable: …»), текст без категории остаётся, прочие отказы — `Network`.
- **`CoreHttpStatusTableTest`** (эмулятор): настоящая таблица ядра: 2xx, 404/410, срок у 429/5xx, дата и дробь срока не дают, 403/301 срок отбрасывают.

Мутации — см. «Временная реализация и мутации».

## Проверки

```text
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
.\gradlew.bat ktlintCheck detekt testGithubDebugUnitTest testFdroidDebugUnitTest lintGithubDebug compileGithubDebugAndroidTestKotlin
.\gradlew.bat minifyGithubReleaseWithR8      (см. п. Л; если проверка тяжёлая — assembleGithubRelease не нужен)
```

Эмулятор (`Medium_Phone_API_36.0`, после слияния Ю2 свободен): `$env:ANDROID_SERIAL = "emulator-5554"; .\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.package=io.github.puflik.plinth.ffi"`
(один пакет, не список классов; число тестов сверять по `app/build/outputs/androidTest-results/connected/**/TEST-*.xml`); эмулятор занят — только сборка, прогон оставить Opus.
Известное: на Windows падают 4 теста `DataStore*SettingsTest` — не из-за задачи; в CI на Linux проходят. Подводные камни, замеченные на временной реализации: строка не длиннее 120
(ktlint/detekt, в том числе комментарии и имена тестов в обратных кавычках); `ThrowsCount` — не больше двух `throw` в функции (причину складывать в переменную и бросать один раз);
`catch (e: Exception)` — только с `@Suppress("TooGenericExceptionCaught")` и пояснением; проглоченное исключение называть `expected`.

## Готово, когда

- все «Проверки» проходят; защищённые тесты не тронуты;
- `grep -rn "org.schabi" app/src/main` — только в `online/youtube/`; `grep -rn '"http ' app/src/main` — ничего (ни одной строки грамматики статуса в Kotlin);
- версия экстрактора и десугаринга встречается в проекте один раз (`libs.versions.toml`);
- release-сборка с зависимостью собирается (результат `minifyGithubReleaseWithR8` — в отчёте);
- в отчёте: что с R8, чем не воспроизвелось, что осталось Ю3в.

## Остановиться и написать, если

- защищённый тест кажется неверным или записи не хватает экстрактору («no recorded answer for …» без смены версии);
- нужно менять файл вне списка «Менять» (в том числе `OnlineResolver`, DI, `core/**`);
- в `main` нет `check_http_status` (`grep check_http_status core/ffi/src/api/online_api.rs`) — Ю2 не слита, тесты на такой основе не собрать;
- UniFFI называет функцию иначе, чем `checkHttpStatus(UShort, String?)`, или текст `CoreException` не похож на «категория: текст»;
- JitPack не отдаёт `v0.26.5` или R8 просит больше, чем `-dontwarn`;
- проверки упали дважды подряд (одна попытка исправить, потом стоп).

## На что смотреть при чтении плана (Opus)

Коротко, для ревью: (1) **варианты по формату** (п. В) — отступление от «`stream` выбирает» в `yu1.md`: обосновано тем, что фоллбэк и повтор в другом формате уже есть в ядре; (2) граница
(п. А): `Range` — данные источника в Ю3б, проводка в плеер — Ю3в; (3) `HttpRefused` + таблица: статус и срок идут в `CoreError` только текстом таблицы (`grep '"http '`); (4) заготовка:
одноразовая, ≤ 2 мин, по видео — иначе повтор после 403 получит ту же ссылку; (5) срок: `Budgeted` + таймауты загрузчика ≤ остатка; (6) перевод бед: капча/«бот» → 429 по таблице, не «видео нет»;
(7) тексты без сообщений исключений; (8) `verdictOf` снимает категорию (проверить на эмуляторе: `CoreHttpStatusTableTest`); (9) Gradle: версии в одном месте, `includeGroup`.

## Временная реализация и мутации

Реализация нужна была только для проверки тестов: **в репозиторий не коммитилась**. Патч — в заметках планировщика
(`C:\Users\User\AppData\Roaming\Claude\side-session-notes\local_6a10ff5e-f13f-4d35-84fc-3a8758d8fb83\yu3b-reference-implementation.patch`, 9 файлов: 5 новых в пакете, `CoreHttpStatusTable.kt`,
три файла Gradle); ложится на ветку со слитой Ю2 (в проверочном ворктри — на патчи Ю2 из `r1-11d-reference-implementation-*.patch`). Исполнитель его не видит и пишет своё по разделу «Решение»;
Opus при ревью может сверить. На патче: `ktlintCheck`, `detekt`, `testGithubDebugUnitTest` (56 тестов `online/youtube` + `ffi`), `compileGithubDebugAndroidTestKotlin` зелёные.
Мутации (`yu3b-mutate.py` рядом) и проверка R8 — ниже.

**Мутации — 72 штуки** (`yu3b-mutate.py`): поиск (порядок, четверть мест, предел, пустой запрос, обрезка, проглоченный поиск альбомов, вид результата, суффикс « - Topic»), раскрытие (регулярки id, порядок и состав вариантов, номера, название альбома, пустой альбом, заготовка, автор, длительность), поток (одноразовость, срок и ёмкость заготовки, по видео, заголовки, формат, «нет такого варианта»), беды (каждая ветка перевода, глубина цепочки, прерывание, срок, `NewPipe.init` в вызове), срок, загрузчик (gzip, заголовки, `Retry-After`, таблица, предел тела, редирект, метод, тело POST), `verdictOf`, граница пакета. **Все пойманы**, кроме одной: `CoreHttpStatusTable` не передаёт `Retry-After` ядру (F4) — JVM-тесты настоящий вызов `checkHttpStatus` не делают, ловит только инструментальный `CoreHttpStatusTableTest` (потому он в списке защищённых). Первые версии трёх мутаций (две не собирались, одна равносильна) переписаны и пойманы.

**R8:** `.\gradlew.bat minifyGithubReleaseWithR8` на временной реализации с зависимостью и **без единого нового правила** проходит (1 мин 12 с): пока пакет ни к чему не подключён, R8 вырезает его целиком. Правила (Rhino, `timeago.patterns`, protobuf) нужны с подключением, то есть в Ю3в/Ю3г.

## Отчёт исполнителя

_Заполняет исполнитель: что сделано, что не вышло и почему, коммиты, вывод проверок (коротко), результат `minifyGithubReleaseWithR8`._
