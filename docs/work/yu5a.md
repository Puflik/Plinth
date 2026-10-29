# yu5a — песни YouTube в онлайн-секции поиска и значок источника (Ю5, часть 1 из 6)

> Файл задачи для исполнителя (Sonnet). Пишет планировщик; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

> **Данные пользователя здесь не меняются по форме** (журнал, `queue.bin`, история — прежними путями), поэтому план до
> исполнителя читать Opus не обязан; места, где ошибка стоила бы данных, — в «На что смотреть при чтении плана (Opus)».
> Часть yu5c (радио без перезапуска) — с данными пользователя, её план читает Opus.
>
> Это **часть 1 из 6** большой задачи Ю5: разбор «что уже сделано» и разбиение — ниже, наброски остальных частей — в
> конце файла.

- **Ветка:** `work/yu5a` · **Ворктри:** `C:\claude\Plinth-yu5a`
- **Основа:** `main` на `0ecb7ca` · **Коммит тестов:** `<будет>` (первый в ветке с тестами)
- **Откуда:** `tasks.md`, Ю5 «UI»; остатки Ю3г (`docs/work/yu3g2.md`, `docs/decisions.md` 2026-10-25), Ю4в
  (`docs/work/yu4c.md`, ответы Opus 8 и 16), заметки `docs/work/yu3v.md` (строка 259), `yu3g1.md` (301, 306).

## Цель

Песня YouTube в результатах поиска — строка трека, а не «альбом из одного трека»: касание играет её, меню ⋮ даёт
«Играть следующим», «В очередь», «Заменить очередь», «Радио», «В плейлист…» и лайк без захода на лишний экран;
альбомы YouTube Music по-прежнему открывают экран альбома. Значок «облако» у сетевого трека больше не озвучивается
«From Internet Archive»: где провайдер известен, он назван («From YouTube»), где нет, — нейтрально («Online track»).

## Что из строки Ю5 уже сделано в Ю3 и Ю4 (разбор планировщика)

Строка `tasks.md`: «UI: онлайн-секция YouTube в поиске рядом с IA, экран альбома и плейлиста YouTube Music; лайк и
«В плейлист…» для сетевых треков (есть с v0.2). Из Ю4в: отклик при нажатии «Радио»; «Радио» из плеера начинает
играющий трек сначала. Из Ю3г: значок источника у трека YouTube озвучивается «From Internet Archive»».

| Что названо | Состояние на `main` `0ecb7ca` | Чем подтверждено |
|---|---|---|
| **Онлайн-секция YouTube в поиске рядом с IA** | **Есть.** Ядро ставит секцию YouTube после Internet Archive (`connect_online`, Ю3в); экран рисует секцию на каждого провайдера (`SearchScreen.online()`/`section()`, строки 116–145), имя — `providerName` (`youtube` → «YouTube», сделано в Ю3г1); «Ищем…», «Нет сети», «Повторить», подсказка про прошивку (Ю2/Р1.11г); переключатель по провайдерам и `online_providers_off` (Ю3г1). | Тесты `SearchViewModelTest`/`…RetryTest`/`…CancelTest` (на Internet Archive), `OnlineProvidersTest`, `OnlineSwitchProvidersTest`; живьём — Ю3г2, эмуляторы API 36/30/26, `docs/testing/youtube-live.md` Я1 (латиница; кириллица ⏸); 2026-10-30 планировщик на эмуляторе: «piano» → секция Internet Archive (20 строк), под ней YouTube (пять песен, потом альбомы). |
| …её **вид**: песни и альбомы | **Нет.** `OnlineResult.kind` (`TRACK` — песня, `ALBUM` — альбом) и `duration` экран не читает: `ResultRow` (`SearchScreen.kt:147–166`) рисует всё значком альбома, касание открывает экран альбома. Песня YouTube — «альбом» из одного трека: тап → экран → «Play» → ещё один тап. Нет меню, нет длительности, нет лайка. | `yu3v.md:259` («песни — строками-альбомами … название и вид — Ю5»), `yu3g2.md:387` («тап по результату YouTube открывает страницу трека»). **Закрывает yu5a.** |
| **Экран альбома YouTube Music** | **Есть общим экраном** (E3, `OnlineAlbumScreen`/`OnlineAlbumViewModel`): заголовок «исполнитель · N треков», «Играть», строки треков с облаком, меню, «Источник: YouTube». Треки — `online.album(provider, item)` → `Provider::resolve`; у YouTube это первая страница плейлиста `OLAK5uy_…` (`NewPipeYoutubeSource.resolveAlbum`: `number` — положение, `album` — название без «Album - »). Пункт «Радио» в строке есть (Ю4в, `Yu4cRadioAlbumRowTest`). | `OnlineAlbumViewModelTest` (на Internet Archive), `YoutubeResolveTest` (записанные ответы альбома). **Живьём альбом YouTube до yu5a не открывали** (`youtube-live.md` об альбомах молчит, `yu3g2` жал песню); планировщик открыл его 2026-10-30 на эмуляторе (прототип yu5a поверх `main`; экран альбома он не менял, кроме озвучки облака): «Vibe with piano · 5 tracks», «Play», пять строк с «From YouTube» и ⋮, внизу «Source: YouTube»; второй альбом («Soft Piano: …», 10 треков) — так же; радио из строки альбома стартовало за ≈ 2 с. **Работает.** Чего нет: года (`year = null`), числа треков до загрузки, обложек, страниц дальше первой. |
| **Плейлист YouTube Music** | **Нет.** Поиск провайдера — песни (`MUSIC_SONGS`) и альбомы (`MUSIC_ALBUMS`); плейлистов пользователей в результатах нет, а `resolve` читает только первую страницу плейлиста. Откуда у человека плейлист — не решено (поиск? ссылка из YouTube?). | `NewPipeYoutubeSource.search` (строки 66–78), `YoutubeSource.kt:16–27`. Вопрос — в «Вопросы» и набросок yu5e. |
| **Лайк и «В плейлист…» для сетевых треков** | **Есть** у треков экрана альбома провайдера и в плеере (D4/E3, `OnlineAlbumViewModel.onTrack`/`onAddToPlaylist` → `online.add` → `TrackActions`): трек заводится в каталог при действии. У YouTube-треков те же пути (идентификаторы `<видео>/<itag>` каталог знает с Ю3в). **Нет** у песен в самих результатах поиска — они не строки треков. | `OnlineAlbumViewModelTest` (лайк, плейлист), `Yu4cRadioAlbumRowTest`. Закрывает yu5a для песен поиска. |
| Из Ю4в: **отклик при нажатии «Радио»** | **Нет.** Ответ Opus 8 (`yu4c.md:494`): «Запускаю радио…» не добавлять — `Snackbar` идут по очереди и задержали бы следующую весть; «решит Ю5 после проверки на телефоне». От нажатия до первой страницы — тишина 1–3 с (срок вызова 10 с). | `yu4c.md:468, 494`; `RadioStarter.start` ничего человеку не говорит до `begin`/`notices.tell`. Набросок yu5b. |
| Из Ю4в: **«Радио» из плеера перезапускает играющий трек** | **Нет.** `RadioStarter.begin` (строка 123) зовёт `playback.play(Radio, [затравка] + подборка, 0)`: движок готовит затравку заново, ключ места меняется вместе с контекстом — прослушивание закрывается «пропуском». Ответ Opus 16 (`yu4c.md:499`): принято как есть, «вопрос Ю5/Ю6: нужен ключ места, который не меняется, когда вокруг играющего трека меняется контекст». Ю4е (`Place(context, item, occurrence)`) этого **не решает** (`yu4e.md:139, 298`). | Набросок yu5c — **данные пользователя, план читает Opus**. |
| Из Ю3г: **значок источника озвучивается «From Internet Archive»** | **Нет.** `OnlineMark` (`ui/library/components/OnlineMark.kt`) читает `R.string.track_online` — «From Internet Archive» / «Из Internet Archive» — для всех сетевых треков: в `TrackRow`, `QueuePanel`. Ответ автора E3b (`decisions.md`, 2026-09-28): «для TalkBack — „из Internet Archive“», то есть **откуда** трек. | `yu3g2.md:353`, `decisions.md:6798`. **Закрывает yu5a** (A2). |

**Итог.** Из строки Ю5 по-настоящему сделано одно: секция YouTube в поиске существует и рисуется (но песни выглядят
альбомами). Экран альбома и лайк/плейлист — переиспользованные общие экраны, которые работают для YouTube «в
принципе», но не проверены живьём на альбоме YouTube. Остаток — вид песен, значок, два пункта из Ю4в и плейлисты.

### Что нашёл планировщик сверх строки Ю5 (в Ю5 не записано)

1. **Окончательный 403 на потоке** (после одного повторного `resolve`) доходит как `ERROR_CODE_IO_BAD_HTTP_STATUS` →
   `PlaybackError.Network` → очередь **встаёт** (`PlayerListenerAdapter.kt:197–204`, `FailedRun`). `yu3v.md:253`: «Понятное
   сообщение и пропуск — Ю5/Ю6». Правило «на сети останавливается» — решение автора (G3), поэтому это вопрос Opus. Набросок yu5d.
2. **Поиск по провайдерам идёт по очереди** (ADR 0008; `yu3v.md:260`): ответ приходит, когда ответили **все**; зависший
   Internet Archive (до 15 с) держит «Ищем…» и у YouTube, а секцию YouTube может оставить «не отвечает» без попытки.
   Для человека, что ищет в основном YouTube, это самый заметный недостаток секции. `OnlineRepository.searchIn` (Ю4г) уже
   даёт запрос одному провайдеру — секции можно получать параллельно и показывать по мере ответа **без изменений ядра**.
   Набросок yu5f.
3. **Подсказка «выключены оба провайдера при включённом главном»** — экран как до E3, без объяснения (`yu3g1.md:301,
   366, 425`: «Ю5/Ю6»). Мелочь; набросок yu5b.
4. **Кириллица в поиске** на эмуляторе не проверялась (`adb input text` — ASCII); остаётся для Ю6 (телефон).

## Разбиение Ю5

Шесть частей. yu5a и yu5b делят `TrackRow.kt`, `SearchScreen.kt` и `strings.xml` — идут по очереди; yu5c с ними не
пересекается (очередь, контроллер, история, радио) и может идти параллельно; yu5f после yu5a делит с ней `SearchViewModel.kt`.
`strings.xml` — общий файл: каждая часть получает его явно в «Менять» и правит только свои строки.

| Часть | Что | Данные пользователя | Файлы (главное) |
|---|---|---|---|
| **yu5a** (этот файл) | Песни YouTube в секции поиска — строки треков с меню; значок источника называет провайдера | нет (лайк, плейлист, каталог — прежними путями) | `ui/search/*`, `ui/library/components/{TrackRow,OnlineMark}.kt`, `ui/online/OnlineAlbumViewModel.kt`, `TrackActions.kt`, строки |
| yu5b | Отклик при нажатии «Радио» (+ подсказка при выключенных провайдерах, по решению Opus) | нет | `TrackRow.kt` (`rememberTrackActionFeedback`), `PlayerScreen.kt`, строки — **после yu5a** |
| yu5c | «Радио» не перезапускает играющий трек | **да — план читает Opus** | `queue/`, `audio/PlaybackController.kt`, `history/`, `radio/RadioStarter.kt` — параллельно с UI-частями |
| yu5d | Окончательный 403: понятное сообщение и пропуск | косвенно (правило остановки/пропуска очереди) | `audio/media3/PlayerListenerAdapter.kt`, `audio/FailedRun.kt`, `core/ErrorPresenter.kt` — **решение Opus/автора** |
| yu5e | Альбомы и плейлисты YouTube Music: год/число треков, страницы дальше первой, плейлисты в поиске | нет | `online/youtube/*`, `ui/online/*` — **вопросы автору** |
| yu5f | Секции поиска приходят по мере ответа (параллельно по провайдерам) | нет | `ui/search/SearchViewModel.kt` — **после yu5a** |

Наброски yu5b–yu5f — в конце файла.

## Находки планировщика (по ходу работы)

Записаны 2026-10-30 по чтению кода `main` на `0ecb7ca`.

1. **`SearchViewModel` и `SearchScreen` нельзя расширять новыми обязательными параметрами.** Четырёхаргументный
   `SearchViewModel(library, online, onlineSettings, actions)` строят три JVM-теста и androidTest `SearchFieldTest`;
   `SearchScreen(onOpenPlayer, onOpenAlbum, viewModel = …)` в `SearchFieldTest` вызывается без Hilt, поэтому новый
   параметр с `hiltViewModel()` по умолчанию его уронил бы. Всё новое — внутри `SearchViewModel` без изменения конструктора.
2. **Сердца песен нет откуда взять:** у `SearchViewModel` нет `UserDataRepository`. Выход — `TrackActions.liked(id)` (пять
   строк): `TrackActions` уже держит `userData`.
3. **Песню нельзя завести в каталог без `resolve`:** `OnlineResult` из поиска не несёт вариантов (`<видео>/<itag>`), а
   каталог узнаёт трек по вариантам (`find_online_track`). Значит, любое действие над песней поиска начинается с
   `online.album(provider, video)` — для видео это один трек с реальными вариантами. Это **не лишняя задержка**: извлечение
   при `resolve` кладёт потоки в заготовку (`NewPipeYoutubeSource.resolveVideo` → `stash.put`), и первый `stream()` берёт их
   оттуда (`streamOf` → `stash.claim`). «Тап → resolve → play» стоит ту же одну выборку (2–3 с), что и сегодняшнее «тап →
   экран → Play». Заготовка живёт 2 минуты и держит 8 видео (`LIFETIME_NANOS`, `CAPACITY` в `NewPipeYoutubeSource.kt`); для
   лайка и плейлиста она не нужна и пропадает — второй раз тот же трек сыграет уже с обычным извлечением на `stream()`, без
   второго `resolve` (песня помнится на экране).
4. **Открывать плеер надо после ответа, не при касании.** `rememberTrackActionFeedback` вызывают сразу после
   `viewModel.onTrack(...)`; для песни, которой ещё нет в каталоге, плеер открылся бы пустым на 2–3 с. Поэтому ViewModel
   сообщает о конце действия событием, а экран на него открывает плеер или говорит «добавлено».
5. **Беду `resolve` объясняет `Toast`, не `Snackbar`:** канал `ErrorNotices` (`AppError` → `ErrorPresenter` → строка)
   потребовал бы новых видов вестей в пяти файлах (как Ю4в для радио); строки о беде секции уже есть
   (`online_no_network`, `online_provider_down`, …) и подходят. Выбор записан в «На что смотреть».
6. **Облако без провайдера:** `LibraryTrack` не знает, чей он (`online = uri == null`), `AudioSource.Online` несёт только ID
   трека. Провайдер известен только на экранах, что строят `TrackLabel` сами: экран альбома и песни поиска. В списках
   «Любимое»/плейлисты/«Недавнее» и в очереди облако остаётся без имени провайдера — нейтральная озвучка. Провайдер в
   `LibraryTrack` — правка ядра (набросок в «Вопросы»).
7. **`kind` пока смотрит только `RadioStarter`** (`firstOrNull { it.kind == TRACK }`); остальной код ему доверяет одинаково,
   поэтому переключение строки по `kind` ничего у Internet Archive не меняет (его результаты — `ALBUM`).
8. **Холодная сборка ворктри долгая:** первый `testGithubDebugUnitTest` — 2 мин 38 с (хост-сборка ядра ≈ 1 мин), первый
   `connectedGithubDebugAndroidTest` — 5 мин (`cargo-ndk` под Android); повторные — 30–45 с. Это не зависание.
9. **Полоса хода не мешает Compose-тесту:** бесконечная анимация индикатора не блокирует `waitUntil`/`performClick` в
   `createComposeRule` (проверено прототипом, тест `a_song_being_fetched_shows_progress_until_the_provider_answers`).
10. **Мутация, которая не нашла строку:** `QueueContext.Search(query.value.trim())` встречается в `SearchViewModel` дважды
    (библиотека и песня) — мутировать по полной строке вызова `actions.act(...)`; запись для тех, кто повторит.
11. **Живьём (эмулятор API 36, 2026-10-30, прототип): секция YouTube стоит под 20 строками Internet Archive.** Запрос
    «piano»: «In your library — ничего», затем Internet Archive — все 20 результатов альбомами, и только под ними YouTube
    (≈ шесть экранов прокрутки). Для человека, что ищет музыку, главная секция — YouTube. Порядок — решение Ю3в («после
    Internet Archive»), не этой задачи; вопрос 6, набросок yu5f.
12. **Живьём: время.** Секции приходят вместе — «Searching…» держится, пока не ответят оба провайдера (запрос «guitar»:
    на 2,5 с ещё «Searching…», на 4,7 с обе секции). Касание песни → полоса «Getting the song…» → плеер открыт и звук
    идёт за ≈ 3–5 с (шаг опроса экрана — 2 с, точнее не замерено). «Radio» из строки альбома: очередь радио встала не
    позже 2,2 с (первый же снимок экрана после нажатия) — окно тишины у yu5b порядка 1–2 с на хорошей сети.
13. **Живьём: что видно.** Песня YouTube — строка с названием, исполнителем, облаком «From YouTube», длительностью и ⋮; в
    меню — *Play next, Add to queue, Replace queue, Radio, Add to playlist…, Like*; после *Like* меню показывает *Unlike*;
    *Add to playlist…* → *New playlist* → имя → песня в «Road» и в «Liked», облако там — «Online track» (списки не знают
    провайдера, как и задумано). Альбомы YouTube в выдаче «piano» — в основном сборники загрузчиков («Deep Sleep
    Relaxation», «Music For Studying»), а не альбомы исполнителей; экран такого альбома: «Deep Sleep Relaxation · 10 tracks»,
    у строк «исполнитель · название сборника».
14. **Lint и ktlint нашли недочёты первой редакции прототипа:** `LocalContext.current.getString(…)` внутри `LaunchedEffect` —
    `LocalContextGetResourceValueCall` (**ошибка** lint, не предупреждение): слова беды берутся через
    `LocalResources.current.getString(…)`, как в `PlaylistPicker`; `ktlint` требует переносить цепочку из четырёх точек с
    лямбдой (`Expected newline before '.'`) и держит строку в 120 знаков. Тесты в ветке уже написаны так.

## Контекст

Прочитать (диапазонами, не целиком, где сказано):

- `docs/work/README.md` — «Что исполнитель знает без памяти»; `CLAUDE.md` — «Работа Opus и Sonnet».
- Этот файл целиком — разбор «что уже сделано» выше объясняет, почему задача именно такая.
- Экран поиска: `ui/search/SearchScreen.kt` (весь), `ui/search/SearchViewModel.kt` (весь).
- Экран альбома — образец, как действие над сетевым треком заводит его в каталог: `ui/online/OnlineAlbumViewModel.kt` (весь),
  `ui/online/OnlineAlbumScreen.kt` 56–77 (как `TrackRow` получает `PlaylistPicker`).
- Строка трека и значок: `ui/library/components/TrackRow.kt` (весь), `OnlineMark.kt`; `ui/online/ProviderName.kt`.
- Действия над треком: `ui/library/TrackActions.kt` (весь), `TrackAction.kt` (весь).
- Онлайн: `online/OnlineRepository.kt` (весь), `ffi/CoreOnline.kt` 184–274 (`OnlineKind`, `OnlineProblem`, `OnlineResult`,
  `OnlineTrack`, `OnlineVariant`).
- Что отдаёт YouTube: `online/youtube/YoutubeSource.kt` 14–63 (поиск — песни, потом альбомы; `resolve` видео — один трек,
  альбома — первая страница плейлиста), `online/YoutubeProvider.kt` 40–45.
- Образцы тестов: `ui/search/SearchViewModelTest.kt`, `ui/online/OnlineAlbumViewModelTest.kt`,
  `ui/online/Yu4cRadioAlbumRowTest.kt`; `androidTest/.../ui/search/SearchFieldTest.kt` (экран без Hilt),
  `androidTest/.../ui/library/TrackRowMenuTest.kt`.
- **Основа реализации** — патч планировщика `yu5a-reference-implementation.patch` в общей папке заметок сессий
  (`C:\Users\User\AppData\Roaming\Claude\side-session-notes\local_6a10ff5e-f13f-4d35-84fc-3a8758d8fb83\`, в ветке его нет):
  все файлы из «Менять» (только основной код — тесты уже в ветке), ложится на коммит тестов (`git apply`). Это
  прототип, которым проверены тесты; **каждую строку сверить с «Решением» и «Инвариантами»** — расхождения исправить, а не
  применять вслепую. `ktlintCheck` и `detekt` на нём чисты не гарантированы — прогнать.

## Решение

### Что делает песня в секции (А1)

Результат поиска с `kind == OnlineKind.TRACK` рисуется **строкой трека** (`TrackRow` по `TrackLabel`, как строки экрана
альбома), результат с `kind == ALBUM` — прежней `ResultRow` (значок альбома, касание открывает экран альбома).
Строка песни: название, исполнитель (без альбома), длительность результата, облако «From YouTube», кнопка ⋮ с обычным
меню трека (*Играть следующим*, *В очередь*, *Заменить очередь*, *Радио*, *В плейлист…*, лайк/снять лайк). Касание —
*Играть*.

Любое действие над песней начинается с того, что она оказывается в каталоге. В результате поиска у песни **нет
вариантов** (`<видео>/<itag>`), а каталог узнаёт трек по ним, поэтому: `online.album(provider, result.external)` — для
видео это один трек с настоящими вариантами — затем `online.add(provider, listOf(трек))`. Это одна выборка (2–3 с), та же,
что стоит «тап → экран → Play» сегодня: `resolve` кладёт потоки в заготовку источника, и первый `stream()` берёт их оттуда
(находка 3). Дальше действие идёт тем же путём, что у трека любого списка: `TrackActions.act(action, контекст,
listOf(трек), трек)`.

- **Контекст очереди** — `QueueContext.Search(<текст запроса, trim>)` с одной песней. Играть список песен секции с
  нажатой нельзя: у соседних нет вариантов, а `resolve` каждой — 2–3 с и около восьми запросов YouTube (отвергнуто).
  Что после песни — решает человек (радио в меню).
- **Память экрана**: результат `resolve`+`add` помнится на жизнь `SearchViewModel` по ключу песни — второе действие над той
  же песней (лайк, потом «играть») провайдера не спрашивает. Неудача в память не идёт.
- **Ход**: пока песню получают, строка помечена занятой; второе касание той же песни ничего не делает (иначе два `add` и
  две игры). Другие песни касаются независимо.
- **Конец действия** сообщает экрану `SongEvent`: `Done(action)` — экран делает то же, что `rememberTrackActionFeedback`
  после касания трека списка (играет — открывает плеер, `PLAY_NEXT`/`ADD_TO_QUEUE` — «Добавлено в очередь», остальное —
  ничего); `Failed(provider, problem)` — экран говорит `Toast` словами беды секции (`online_no_network`,
  `online_provider_down`, `online_rate_limited`, `online_broken`, с именем провайдера). Плеер открывается **после**
  ответа, не при касании (находка 4). «В плейлист…» результата не сообщает: диалог выбора и без того говорит «Добавлено в
  …», а беду скажет `Failed`.
- **Сердце**: как только песня заведена в каталог, строка показывает лайк из журнала (`TrackActions.liked(id)`);
  до этого — «не поставлен», даже если раньше лайкнули: `known` ищет по вариантам, которых у результата нет.

### Типы и сигнатуры (дословно)

`ui/search/OnlineSongs.kt` (новый):

```kotlin
/** Что экран знает о песне секции… */
data class SongState(
    val liked: Boolean = false,
    val busy: Boolean = false,
)

sealed interface SongEvent {
    data class Done(val action: TrackAction) : SongEvent
    data class Failed(val provider: String, val problem: OnlineProblem) : SongEvent
}

/** Ключ песни в `SearchUiState.songs` — тот же, что у строки секции в списке. */
fun songKey(provider: String, result: OnlineResult): String = "$provider/${result.external}"

internal class OnlineSongs(online: OnlineRepository, actions: TrackActions)   // внутренности — прототип в патче
```

`SearchViewModel` (конструктор **не меняется**):

```kotlin
data class SearchUiState(
    val results: List<LibraryTrack> = emptyList(),
    val searched: Boolean = false,
    val online: OnlineResults = OnlineResults.None,
    val songs: Map<String, SongState> = emptyMap(),   // новое, последним; только песни, с которыми работали
)

val songEvents: SharedFlow<SongEvent>      // без повтора: тот, кто не подписан, событие теряет
fun onSong(provider: String, result: OnlineResult, action: TrackAction)
fun onSongToPlaylist(provider: String, result: OnlineResult, playlist: PlaylistId)
fun onSongToNewPlaylist(provider: String, result: OnlineResult, name: String)
```

`onSong` и оба `onSongTo…`: результат не `TRACK` или песню уже получают — ничего. Иначе: песня занята → каталог
(память или `resolve`+`add`) → действие → (`onSong`) `Done(action)`; в конце всегда снять «занята». Беда `resolve` —
`Failed(provider, беда провайдера)`; ответ без треков или `add` вернул `null`/не один ID — `Failed(provider, BROKEN)`;
при беде в каталоге, очереди, журнале и памяти ничего не остаётся.

`ui/library/TrackActions.kt`: `fun liked(track: TrackId): Flow<Boolean> = userData.liked(track)` (пять строк KDoc +
функция; других правок нет).

`ui/library/components/TrackRow.kt`: `TrackLabel(…, val online: Boolean, val provider: String? = null)` — **новый
параметр последним и со значением по умолчанию** (старые тесты строят `TrackLabel` шестью аргументами);
`Trailing`: `if (label.online) OnlineMark(source = label.provider?.let(::providerName))`.

`ui/library/components/OnlineMark.kt`: `fun OnlineMark(modifier: Modifier = Modifier, source: String? = null)`;
`contentDescription` — `track_online_from` с именем, если `source != null`, иначе `track_online`.

`ui/online/OnlineLibraryTrack.kt` (новый): `internal fun OnlineTrack.toLibraryTrack(id: TrackId): LibraryTrack` — тело
перенесено из `private` функции `OnlineAlbumViewModel` как есть; `OnlineAlbumViewModel` пользуется общей и передаёт
`provider = album.provider` в `TrackLabel`.

`ui/search/SearchScreen.kt`: сигнатура `SearchScreen(onOpenPlayer, onOpenAlbum, modifier, viewModel)` **не меняется**
(`SearchFieldTest` зовёт её без Hilt). Внутри: `LaunchedEffect(viewModel)` собирает `songEvents` (`Done` → тот же
`feedback`, что у треков списка, через `rememberUpdatedState`; `Failed` → `Toast`); `section()` выбирает строку по
`result.kind`; песня — `SongItem` (строка + под ней `LinearProgressIndicator` с описанием `online_song_getting`, пока
`busy`). Слова беды секции (`problemText`) и `Toast` берут один `when` по `OnlineProblem` — не копируйте его дважды; строку для
`Toast` берите через `LocalResources.current.getString(…)`: `LocalContext.current.getString(…)` — ошибка lint
(`LocalContextGetResourceValueCall`, находка 14).

### Значок источника (А2)

`track_online` больше **не называет провайдера**: «Online track» / «Сетевой трек». Где экран знает провайдера, значок
называет его: `track_online_from` — «From %1$s» / «Из %1$s», имя — `providerName` («YouTube», «Internet Archive», незнакомый
— его ID). Знают: строки песен поиска (`TrackLabel.provider = provider`) и строки экрана альбома (`album.provider`).
**Не знают и остаются нейтральными**: списки фонотеки («Любимое», плейлисты, «Недавнее» — `LibraryTrack` не несёт
провайдера) и очередь (`QueuePanel` не меняется — `AudioSource.Online` несёт только ID трека). Ответ автора E3b (2026-09-28)
просил «из Internet Archive» — **откуда**; ради нейтральных списков откуда-ответ там теряется. Вернуть его — провайдер в
`LibraryTrack` (правка ядра и моста, набросок в «Вопросы»); здесь не делается.

`values/strings.xml` и `values-ru/strings.xml` (общий файл, этой задаче отдан явно; три строки, без других правок):

| имя | EN | RU |
|---|---|---|
| `track_online` (изменить) | `Online track` | `Сетевой трек` |
| `track_online_from` (новая) | `From %1$s` | `Из %1$s` |
| `online_song_getting` (новая) | `Getting the song…` | `Получаем песню…` |

### Отвергнуто

- **Играть все песни секции с нажатой** — см. выше: у соседних нет вариантов, `resolve` каждой дорог и режется
  лимитами YouTube.
- **Заводить в каталог все песни результатов при показе** — каталог растёт от одного поиска (решение E1: трек попадает
  в каталог при действии, не при показе).
- **Отдельный `ViewModel` для песен (`hiltViewModel()` в `SearchScreen`)** — `SearchFieldTest` зовёт `SearchScreen` без
  Hilt и упал бы (находка 1). Всё внутри `SearchViewModel` без смены конструктора.
- **Беда песни через `AppError` → `ErrorPresenter` → `ErrorNotices`** — канал строк внизу (Ю4в) ради одной беды потребовал
  бы новых видов в пяти файлах; `Toast` с готовыми словами секции хватает. Выбор — в «На что смотреть».
- **Беда прямо в строке песни** (красная подпись) — состояние «беда» надо сбрасывать по смене запроса и повтору,
  а `Toast` не держит состояния. Если Opus захочет строку — это правка `SongState`.
- **Провайдер в `LibraryTrack`/`AudioSource.Online`** ради «откуда» в списках и очереди — ядро и мост, не UI-задача.
- **Продолжение радио после песни автоматически** — новая функция, не из Ю5.

## Инварианты

1. В каталог идёт **только та песня, с которой работали**; показ результатов ничего не пишет.
2. Лайк и «В плейлист» пишутся прежними путями (`TrackActions`); новых операций журнала и форматов нет.
3. Беда `resolve`, пустой ответ и сбой `add` не оставляют ничего: ни трека в каталоге/очереди, ни записи в памяти экрана,
   ни застрявшей «занятой» строки; следующее касание пробует заново.
4. Одна песня — один `resolve` и один `add` за жизнь `SearchViewModel`, сколько бы действий ни было.
5. Касание песни, которую уже получают, ничего не делает.
6. Плеер открывается только после того, как очередь заполнена.
7. Результаты вида `ALBUM` и вся секция Internet Archive работают как до задачи: касание — экран альбома.
8. `SearchViewModel(library, online, onlineSettings, actions)` и `SearchScreen(onOpenPlayer, onOpenAlbum, …, viewModel)`
   собираются и работают как раньше; `TrackLabel(…шесть аргументов…)` тоже.
9. Облако не называет провайдера, которого экран не знает; незнакомый ID называется своим ID, не «Internet Archive».
10. Строки есть в обоих языках (`RussianTranslationTest`).

## Файлы

- **Менять:**
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/search/SearchScreen.kt`
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/search/SearchViewModel.kt`
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/search/OnlineSongs.kt` (новый)
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/online/OnlineLibraryTrack.kt` (новый)
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/online/OnlineAlbumViewModel.kt`
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/library/TrackActions.kt`
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/library/components/TrackRow.kt`
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/library/components/OnlineMark.kt`
  - `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml` (три строки, таблица выше)
- **Не трогать:** `ui/player/*` (в том числе `QueuePanel.kt`), `radio/*`, `core/*` (`AppError`, `ErrorPresenter`),
  `ui/common/ErrorNotices*`, `online/*`, `ffi/*`, Rust (`core/`), общие двойники `sharedTest/.../FakeOnlineRepository.kt`,
  `TestConcert.kt`, все старые тесты, `CHANGELOG.md`, `tasks*.md`, `docs/decisions.md`, `CLAUDE.md`, зависимости.
- **Защищённые тесты** (из коммита тестов; не менять, не удалять, не глушить):
  - `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5aSearchSongsTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/ui/online/Yu5aAlbumLabelTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/ui/Yu5aSourceMarkTextTest.kt`
  - `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/TestYoutubeOnline.kt` (двойник и данные тестов; новый, старых не касается)
  - `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5aSearchSongsScreenTest.kt`
  - `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/library/components/Yu5aOnlineMarkTest.kt`

## Тесты (написаны в коммите тестов)

20 JVM-тестов и 11 инструментальных. Без нового API JVM-тесты не собираются (`compileGithubDebugUnitTestKotlin`: нет
`SearchViewModel.onSong`/`songEvents`, `SearchUiState.songs`, `SongState`, `SongEvent`, `songKey`, `TrackLabel.provider`;
инструментальные — ещё `R.string.track_online_from`, `R.string.online_song_getting`, `TrackLabel(provider = …)`);
сигнатуры — выше. Сеть — двойник `TestYoutubeOnline` (поверх `FakeOnlineRepository`: поиск отдаёт песню и альбом
YouTube, `album` на видео песни — один трек с вариантами `<видео>/251` и `<видео>/140`, ворота `gate`, `networkUp`,
`emptyAnswer`, журнал вызовов `albumCalls`). Красное по утверждениям (не по сборке) показывают мутации ниже.

**`Yu5aSearchSongsTest` (15, JVM) — песни в `SearchViewModel`.** Песня, с которой не работали, не в `songs`; касание
песни заводит в каталог только её и играет одну с контекстом `Search(запрос)`, событие `Done(PLAY)`; `REPLACE_QUEUE`
играет и называется в событии; провайдера спрашивают о песне один раз при `PLAY`, `LIKE`, `ADD_TO_QUEUE` подряд; лайк
заводит только эту песню, ничего не играет и не трогает очередь, сердце видно сразу и снимается; сердце следует за
журналом (лайк снят снаружи — строка видит); «В плейлист» и «Новый плейлист» кладут каталожный ID песни и ничего не
играют; песня в очередь при играющей встаёт в ручной блок, события по порядку; «Радио» отдаёт затравкой саму песню с
каталожным ID, очередь не трогает; пока песню получают (ворота) она `busy`, второе касание ничего не добавляет, после
ответа `busy` снят и событие одно; без сети — `Failed(NO_NETWORK)`, в каталоге нет, не играет, не `busy`, а когда сеть
вернулась, то же касание играет (беда не запомнена); пустой ответ — `Failed(BROKEN)`, в каталоге нет; альбом — не песня:
ни вызовов, ни событий, ни очереди; ключ различает песни и провайдеров.

**`Yu5aAlbumLabelTest` (2, JVM).** Строки экрана альбома несут `provider` альбома; строка трека фонотеки (`TrackLabel.of`)
провайдера не знает.

**`Yu5aSourceMarkTextTest` (3, JVM) — слова.** `track_online` в обоих языках не называет «archive» и без подстановок;
`track_online_from` в обоих языках с одним `%1$s`; `online_song_getting` есть в обоих. Красный на старом тексте, пока
не правлены ресурсы.

**`Yu5aOnlineMarkTest` (5, инструментальный) — озвучка.** Облако без провайдера говорит `track_online` и не называет
YouTube; с `youtube` — «From YouTube»; с `archive.org` — «From Internet Archive»; с незнакомым `bandcamp` — «From
bandcamp»; у локального трека (`online = false`) облака нет вообще.

**`Yu5aSearchSongsScreenTest` (6, инструментальный) — вид секции.** Экран на `TestYoutubeOnline`: песня — строка с одной
кнопкой «⋮» (у альбома её нет), с длительностью (3:29) и облаком «From YouTube»; касание песни играет её, открывает плеер и
не открывает альбом; касание альбома отдаёт `OnlineAlbumHeader` и ничего не играет; меню песни содержит «Радио» и
«В плейлист…», «Like» ставит лайк, после чего меню предлагает «Unlike» (сердце живое), плеер не открыт и не играет; пока
песню получают (ворота), под строкой полоса с описанием «Getting the song…», после ответа она уходит, плеер открывается.

Исполнитель может добавить свои тесты в **новых** файлах.

## Проверки

Из PowerShell, в ворктри `C:\claude\Plinth-yu5a` (`JAVA_HOME` — `docs/work/README.md`):

```text
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
.\gradlew.bat testGithubDebugUnitTest --tests "*Yu5a*"                                          # защищённые JVM: 20 из 20
.\gradlew.bat ktlintCheck detekt testGithubDebugUnitTest lintGithubDebug --continue             # всё остальное
.\gradlew.bat assembleGithubDebug compileGithubDebugAndroidTestKotlin                           # Hilt и инструментальные тесты собираются
```

Инструментальные — только на эмуляторе `Medium_Phone_API_36.0` (`-no-window`, после работы `adb emu kill`),
`$env:ANDROID_SERIAL = "emulator-5554"` — в той же команде, что Gradle, **по одному классу за прогон** (запятая в списке
теряет всё, кроме первого):

```text
$env:ANDROID_SERIAL = "emulator-5554"; .\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.puflik.plinth.ui.search.Yu5aSearchSongsScreenTest"      # защищённый, 6 из 6
$env:ANDROID_SERIAL = "emulator-5554"; .\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.puflik.plinth.ui.library.components.Yu5aOnlineMarkTest"  # защищённый, 5 из 5
$env:ANDROID_SERIAL = "emulator-5554"; .\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.puflik.plinth.ui.search.SearchFieldTest"               # страж: экран без Hilt
$env:ANDROID_SERIAL = "emulator-5554"; .\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.puflik.plinth.ui.library.TrackRowMenuTest"             # страж: меню строки
```

Число выполненных тестов — по `TEST-*.xml` (`app/build/outputs/androidTest-results/connected/debug/flavors/github/` и
`app/build/test-results/testGithubDebugUnitTest/`), а не по «зелёной» сборке; различать «выполнено» и «из кэша» (`--rerun`
сразу после имени задачи). Устройства автора не трогать.

Как поднять эмулятор без окна (так делал планировщик): `Start-Process "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
-ArgumentList "-avd","Medium_Phone_API_36.0","-no-window","-no-audio","-no-boot-anim","-no-snapshot-save","-gpu","swiftshader_indirect"
-WindowStyle Hidden`, затем ждать `adb -s emulator-5554 shell getprop sys.boot_completed` = `1` (≈ 1–2 мин). После работы —
`adb -s emulator-5554 emu kill` и убедиться, что процессов `qemu`/`emulator` не осталось. `adb` — по полному пути
(`$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`), всегда с `-s emulator-5554`. **Вывод Gradle не обрезать
`| Select-Object -First N`** — это обрывает сборку (код 255): писать в файл (`cmd /c "…gradlew.bat … > лог 2>&1"`) и читать
потом. Холодная сборка ворктри долгая (находка 8).

Известное: на Windows падают 7 тестов `DataStore*SettingsTest` (`IOException` в `FileStorage.kt:114`) — не из-за задачи;
в CI на Linux проходят.

## Готово, когда

- 20 защищённых JVM-тестов и 11 инструментальных зелёные; прочие тесты — как были (кроме известных 7 `DataStore*` на
  Windows); `RussianTranslationTest` зелёный.
- `ktlintCheck`, `detekt`, `lintGithubDebug` чисты; `assembleGithubDebug` и `compileGithubDebugAndroidTestKotlin`
  собираются.
- `SearchFieldTest` и `TrackRowMenuTest` на эмуляторе зелёные (экран без Hilt; меню строки).
- Песня YouTube в секции поиска — строка трека; альбом — строка альбома с прежним касанием.
- **Живая проверка на эмуляторе** (`docs/testing/youtube-live.md`: `adb -s emulator-5554`, `uiautomator dump`; телефон автора
  не трогать; `$env:ANDROID_SERIAL = "emulator-5554"; .\gradlew.bat installGithubDebug`, пакет — `io.github.puflik.plinth.debug`;
  после прогона — `adb -s emulator-5554 uninstall io.github.puflik.plinth.debug`): поиск «piano» → в секции YouTube песни со
  значком облака («From YouTube») и ⋮, альбомы со значком альбома (секция YouTube — под 20 строками Internet Archive,
  прокрутить); касание песни — полоса «Getting the song…», потом плеер и звук (≈ 3–5 с); ⋮ → *Radio* — очередь радио;
  ⋮ → *Like* — меню потом предлагает *Unlike*, песня в «Liked» с облаком «Online track»; ⋮ → *Add to playlist…* → *New
  playlist* → имя → песня в плейлисте; **касание альбома — экран альбома YouTube Music с треками и «Play»**.
  Ориентиры планировщика на экране 1080×2400 (проверять по `uiautomator dump`, а не по числам): мастер — «Skip all»
  (133, 2253), диалог доступа — «Don’t allow» (540, 1436), вкладка «Search» (539, 2274), поле запроса (379, 178);
  `adb shell input text piano` — только ASCII. Что увидел — в «Отчёт исполнителя» (в том числе если альбом пуст, без года
  или без числа треков — материал для yu5e).
- Защищённые тесты не тронуты: `git diff <коммит тестов> HEAD -- <файлы защищённых тестов>` пуст; изменены только файлы из
  «Менять»; новых `@Suppress`, `@Ignore`, `!!` в основном коде нет.
- Hilt собирается (`@Inject`-конструктор `SearchViewModel` не менялся).

## Остановиться и написать, если

- защищённый тест кажется неверным;
- для решения нужно менять файл вне списка «Менять» (в том числе старый тест или `FakeOnlineRepository`);
- проверки упали дважды подряд (одна попытка исправить, потом стоп);
- `SearchFieldTest` или `TrackRowMenuTest` краснеют из-за новых строк секции;
- на эмуляторе песня YouTube после `resolve` не играет или играет с паузой дольше 5 с (должна идти из заготовки потока);
- экран альбома YouTube Music пуст, не открывается или падает — это не чинится здесь, а записывается для yu5e.

## На что смотреть при чтении плана (Opus)

Данные пользователя здесь не меняются по форме (журнал, `queue.bin`, история — прежними путями), поэтому до исполнителя
план читать не обязательно; но в этих местах ошибка стоила бы данных, и они — на ревью:

1. **Каталог пополняется из поиска** при лайке, плейлисте и очереди — как у экрана альбома (решение E1), но теперь по
   одной песне из строки. Проверить: сбой `add` ничего не оставляет (инвариант 3, тест «пустой ответ»).
2. **Песня играет одна** (`Search(запрос)` с одним треком): после неё очередь кончается. Альтернативы отвергнуты (выше).
   Согласны, что «радио из меню» — достаточное продолжение?
3. **Беда `resolve` — `Toast`**, не канал `ErrorNotices`. Цена: нет строки внизу с кнопкой, сообщение не копится.
4. **Нейтральное «Online track» в списках и очереди** вместо прежнего (и неверного для YouTube) «From Internet Archive».
   Ответ автора E3b просил называть источник; называется там, где экран его знает. Согласны ли оставить «откуда» только
   на экране альбома и в строках поиска до отдельной задачи «провайдер в `LibraryTrack`»?
5. **События теряются при повороте экрана во время `resolve`**: действие выполнится (песня заиграет), но плеер не
   откроется и `Toast` не покажется. `SharedFlow` без повтора выбран, чтобы старое событие не открыло плеер через
   минуты на другом экране. Принять?
6. **Гонка двух песен**: два касания разных песен подряд — заиграет та, чей `resolve` кончился позже, а не нажатая
   последней. Лечится отменой прежнего `PLAY`; не сделано (редкий случай).
7. **Сердце неизвестно до первого действия** — лайк, поставленный раньше, виден после первого касания песни, не при показе.
   Лечится вариантами в результате поиска (правка ядра).
8. **`TrackActions.liked`** — единственная правка `TrackActions`; её можно заменить передачей `UserDataRepository` в
   `SearchViewModel`, но тогда ломаются конструкторы в четырёх старых тестах.

## Вопросы

Решают Opus и автор; исполнитель их не решает. Записаны здесь, не в чат (условие задачи).

1. **(автору)** «Плейлист YouTube Music» из строки Ю5: нужен ли он в поиске, или достаточно альбомов (`OLAK5uy_…`)? Как
   плейлист попадёт к человеку — строкой поиска (`MUSIC_PLAYLISTS` у экстрактора, результат вида `ALBUM`, первая страница)
   или ссылкой из YouTube («Поделиться» → Plinth, `intent-filter` на `music.youtube.com/playlist?list=…`)? Второе —
   новая возможность приложения. Влияет на yu5e.
2. **(Opus)** Окончательный 403: остановка очереди (правило G3 «сеть — стоп») или пропуск с понятным сообщением? Пока 403
   не встретился ни разу (`youtube-live.md` Я6) — отложить до телефона (Ю6)? Влияет на yu5d.
3. **(Opus)** Параллельные секции (yu5f): зависший Internet Archive задерживает YouTube до 15 с. Поднять yu5f выше yu5b?
4. **(автору)** Подсказка в поиске, когда главный переключатель включён, а оба провайдера выключены (сейчас экран как
   без сети, без объяснения). Нужна?
5. **(Opus)** Провайдер в `LibraryTrack` ради озвучки «откуда» в списках и очереди — отдельная небольшая задача (ядро,
   мост, `LibraryTrack`, SQL выбора) или оставить нейтральное «Online track»?

6. **(Opus, автору)** Порядок и длина секций. В живом поиске YouTube стоит под 20 строками Internet Archive (находка 11).
   Варианты: YouTube первой (порядок задаёт `OnlineProviderIds.ALL` и `connect_online`, Ю3в); Internet Archive не больше пяти
   строк со «Показать все»; обе секции рядом (вкладки «YouTube» / «Internet Archive»). Влияет на yu5f.

## Наброски остальных частей Ю5

Порядок: **yu5a → yu5b** (общие `TrackRow.kt`, `SearchScreen.kt`, `strings.xml`); **yu5c** — параллельно с ними (файлы
не пересекаются: очередь, контроллер, история, радио); yu5d — после телефона; yu5e — после ответа на вопрос 1; yu5f — после
yu5a (`SearchViewModel`).

### yu5b — отклик на «Радио» (и подсказка)

*Цель.* Нажатие «Радио» сразу отзывается: сейчас до первой страницы 1–3 с (срок вызова 10 с) тишина (`yu4c.md:468`).
*Предложение.* `Toast` «Запускаю радио…» / «Starting radio…» — в `rememberTrackActionFeedback` (`TrackRow.kt`) ветка
`TrackAction.RADIO` для списков и строк альбома; у песни поиска отклик нужен **при касании**, не после `resolve` (`Done`
приходит позже) — отдельное событие или `Toast` сразу в экране; в `PlayerScreen` — у пункта «Радио» меню плеера.
`Toast`, а не `Snackbar`: ответ Opus 8 — `Snackbar` идут по очереди и задержали бы «Нет сети». Дольше 3,5 с `Toast`
гаснет; полоса хода у мини-плеера (`RadioStarter.starting: StateFlow<Boolean>`) решает «дольше», но дороже. Замер планировщика
yu5a (эмулятор API 36, хорошая сеть): очередь радио встала не позже 2,2 с после нажатия в строке альбома; худший случай —
до срока вызова радио, 10 с (yu4b); планировщик yu5b замеряет на плохой сети (полоса пропускания эмулятора режется
`adb emu network speed`).
*Подсказка при выключенных провайдерах* (`yu3g1.md:301`): `OnlineResults` получает вид «выключено» при включённом главном и
пустом наборе включённых — строка «Онлайн-источники выключены в настройках»; по решению Opus (вопрос 4).
*Файлы.* `ui/library/components/TrackRow.kt`, `ui/player/PlayerScreen.kt`, `ui/search/SearchScreen.kt`/`SearchViewModel.kt`,
`strings.xml`. Данных пользователя нет. *Тест.* `TrackAction.RADIO` даёт отклик; пункт меню плеера его вызывает;
строки в обоих языках.

### yu5c — «Радио» продолжает играющий трек (данные пользователя: план читает Opus)

*Цель.* «Радио» от играющего трека — из меню плеера или строки списка с тем же треком — не перезапускает его: трек
доигрывает, дальше идёт радио; прослушивание одно, без ложного «пропуска»; сохранённая секунда цела.
*Факты.* `RadioStarter.begin` (строка 123) → `playback.play(Radio, [затравка] + подборка, 0)` →
`PlaybackController.start` → `engine.prepare` — трек заново. Ключ места — `Place(context, item, occurrence)` (Ю4е); контекст в
ключе, поэтому смена контекста — другое место: `ListeningRecorder` закрывает прослушивание «пропуском» и открывает новое,
`QueueKeeper` сбрасывает секунду (`yu4c.md:499`, `yu4e.md:139, 298`).
*Предложение.* Операция `PlaybackController.continueInto(context, items)`: если `items.first()` равен играющему (и
движок играет) — заменить контекст и хвост без `engine.prepare`; `RadioStarter.begin` выбирает её, когда затравка — играющий
трек. Остаётся ключ места: (а) убрать контекст из `Place` — просто, но повторное касание того же трека в другом списке
станет «тем же прослушиванием» (так уже для того же контекста, Ю4е граница 2); (б) поле «прежний контекст» в
`PlaybackQueue` — Ю4е отверг (ломает равенство очередей после `load()`); (в) решение в `ListenTracker` по позиции движка
(позиция не обнулилась — то же прослушивание) — хрупко. Рекомендация: (а) с записью в решения.
*Файлы.* `audio/PlaybackController.kt`, `queue/PlaybackQueue.kt`, `history/ListenTracker.kt`, `radio/RadioStarter.kt`,
тесты. *Риски.* история прослушиваний, `QueueKeeper` (секунда), `RadioFeeder.expect`, shuffle/повтор, ручной блок.
*Тесты.* «Радио» от играющего: `engine.prepare` не вызван снова, в истории одно прослушивание без `skippedAt`,
сохранённая секунда цела, очередь — радио с играющим первым; «Радио» от неиграющего — как раньше (перезапуск).

### yu5d — окончательный 403: сообщение и пропуск

*Что.* После одного повторного `resolve` второй 403 на потоке — `ERROR_CODE_IO_BAD_HTTP_STATUS` → `PlaybackError.Network` →
очередь встаёт, человек видит «остановилась на …» без причины (`PlayerListenerAdapter.kt:197–204`, `yu3v.md:253`).
*Варианты.* (1) оставить до телефона — 403 не встречался (`youtube-live.md` Я6); (2) 403 после повтора → `SourceUnavailable`
(пропуск), но с предохранителем «не больше N подряд», иначе радио на 300 треков при блокировке адреса пролистается
за секунды; (3) отдельный `PlaybackError.Forbidden` со словами «YouTube не отдаёт этот трек» и пропуском.
*Файлы.* `audio/media3/PlayerListenerAdapter.kt`, `audio/engine/PlaybackError.kt`, `audio/FailedRun.kt`,
`core/ErrorPresenter.kt`, `ui/common/ErrorNotices.kt`, `strings.xml`. Решает Opus/автор (правило G3).

### yu5e — альбомы и плейлисты YouTube Music

*Что.* (1) Заголовок альбома: года нет (`year = null`), числа треков до загрузки нет — поиск не знает; (2) `resolveAlbum`
читает только `initialPage` — у плейлиста больше 100 треков остальное теряется; для альбомов не бывает; (3) плейлисты в
поиске (`MUSIC_PLAYLISTS`; тип `ALBUM`, «собрание») и страницы дальше первой; (4) вход по ссылке «Поделиться» из
YouTube (`intent-filter`). Зависит от ответа автора (вопрос 1) и от живой проверки альбома в yu5a.
*Файлы.* `online/youtube/NewPipeYoutubeSource.kt`, `YoutubeSource.kt`, `online/YoutubeProvider.kt`, записанные ответы
`app/src/test/resources/youtube/`, `ui/online/*`; для ссылок — `AndroidManifest.xml`, `MainActivity.kt`.

### yu5f — секции поиска приходят по мере ответа

*Что.* `OnlineRepository.search` ждёт всех провайдеров, а они опрашиваются по очереди (ADR 0008): зависший Internet
Archive (до 15 с) держит «Ищем…» и у YouTube, а секцию YouTube может оставить «не отвечает» без попытки
(`yu3v.md:260`). Живая картина (yu5a, эмулятор): «guitar» — обе секции пришли вместе на 2,5–4,7 с, «Searching…» общее; секция YouTube — под
20 строками Internet Archive (вопрос 6). *Предложение (без правок ядра).* `SearchViewModel.answerTo` спрашивает подключённых провайдеров
параллельно через `searchIn` (Ю4г); `OnlineResults.Found.sections` растёт по мере ответов, порядок секций
фиксирован (Internet Archive, YouTube), у ещё не ответившей — «Ищем…»; «Повторить» — на секцию; память ответа (Р1 №14)
и отмена (Р1.11а) — как сейчас, но на провайдера. *Проверить.* здоровье/отдых провайдера, что учитывает `Registry::search`,
учитывается и `search_in`. *Файлы.* `ui/search/SearchViewModel.kt`, `SearchScreen.kt`, `online/OnlineProviderIds.kt` (список
провайдеров), тесты поиска. Данных пользователя нет; перед yu5f сверить с `yu3v.md:260`.

## Временная реализация и проверка тестов

**Прототип** (проверено 2026-10-30) лежит патчем в общей папке заметок сессий (`yu5a-reference-implementation.patch`: основной
код из «Менять» без тестов — восемь файлов правятся, два новых; в ветке его нет; `git apply --check` на коммит тестов
проходит); рядом — `yu5a-mutate.py` (17 мутаций одной строки), `yu5a-mutate.json` и `yu5a-mutate-m8.json` (их итог),
`yu5a-planner-notes.md` и два снимка живого прогона (`yu5a-live-youtube-section.png`, `yu5a-live-album.png`). Против него:

- **красное до реализации:** на ветке без патча `compileGithubDebugUnitTestKotlin` и `compileGithubDebugAndroidTestKotlin` не
  собираются — только из-за нового API (нет `SearchViewModel.onSong`/`onSongToPlaylist`/`onSongToNewPlaylist`/`songEvents`,
  `SearchUiState.songs`, `SongState`, `SongEvent`, `songKey`, `TrackLabel.provider`, `R.string.track_online_from`,
  `R.string.online_song_getting`; прочих ошибок сборки нет — проверено разбором вывода компилятора). Утверждениями, а не
  сборкой, красное доказывают мутации ниже;
- **зелёное с патчем:** 20 JVM-тестов Ю5а из 20 (по `TEST-*.xml`); соседние `SearchViewModelTest`, `…RetryTest`,
  `…CancelTest`, `OnlineAlbumViewModelTest`, `Yu4cRadioAlbumRowTest`, `Yu4cRadioUiTest` — 33 из 33; `ktlintCheck`, `detekt`,
  `lintGithubDebug` чисты (тесты тоже; замечания первой редакции — в находке 14); `installGithubDebug` встал на эмулятор;
- **эмулятор** (`Medium_Phone_API_36.0`, `-no-window`, `ANDROID_SERIAL=emulator-5554`, после прогона `adb emu kill`; устройств
  автора не было): `Yu5aSearchSongsScreenTest` 6 из 6, `Yu5aOnlineMarkTest` 5 из 5; стражи — `SearchFieldTest` 1 из 1 и
  пакет `ui.library` 25 из 25 (`TrackRowMenuTest` 13, `LibraryContentTest` 3, `TrackListTest` 1, `PlaylistRowMenuTest` 3,
  `Yu5aOnlineMarkTest` 5); числа — по `TEST-*.xml`;
- **мутации** (`yu5a-mutate.py`: 17 правок одной строки основного кода, каждая запускает 20 JVM-тестов): убиты **все 17**
  (M17 — удаление строки `online_song_getting` — по сборке: ресурс нужен коду). Чем: M1 (лишнее касание не игнорируется) —
  тест про ход и второе касание; M2 (песня не помнится) — «провайдера спрашивают один раз»; M3 (беда не сообщается) — «без
  сети» и «пустой ответ»; M4 — «пустой ответ»; M5 и M14 (сердце не из журнала) — «лайк … сердце»; M6 (ход не виден) — тест про
  ход; M7 (альбом принимается за песню) — «альбом — не песня»; M8 (контекст — не поиск) — «касание песни»; M9 (конец действия
  не сообщается) — шесть тестов; M10 (ход не снимается) — пять; M11, M12 (плейлист) — по тесту на каждый; M13 (строка альбома
  без провайдера) — `Yu5aAlbumLabelTest`; M15 и M16 (слова значка) — `Yu5aSourceMarkTextTest`. **Мутации экранов (Compose)
  не гонялись:** выбор строки по `kind` и озвучку облака в строках охраняют инструментальные тесты, а не JVM;
- **живой YouTube** (прототип, тот же эмулятор, `installGithubDebug`; после прогона `uninstall` и `emu kill`): находки 11–13 —
  песня играет за ≈ 3–5 с, меню, лайк, «В плейлист…», радио из строки альбома, альбом YouTube Music открывается и играет;
- **не покрыто:** телефон автора и кириллица в поиске (Ю6); release/R8 (правила R8 не менялись, новых рефлексивных мест нет).

Исполнителю: патч — основа, а не истина; `ktlintCheck`/`detekt`/`lintGithubDebug` на нём чисты, но если что-то из «Решения»
исполнитель считает неверным, стоп и вопрос в отчёте.

## Отчёт исполнителя

_Заполняет исполнитель: что сделано, что не вышло и почему, коммиты, вывод проверок (коротко); результат живой проверки
на эмуляторе — отдельным абзацем._
