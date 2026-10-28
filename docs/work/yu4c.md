# yu4c — очередь радио, подгрузка и пункт «Радио» (Ю4, часть 3 из 3)

> Файл задачи для исполнителя (Sonnet). Пишет планировщик; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

> **Данные пользователя: план до исполнителя читает Opus.** Задача пишет в `queue.bin` (новый вид контекста «радио»,
> очередь дописывается на ходу), заводит в каталог треки радио (`add`) и меняет то, по чему `QueueKeeper` и история
> прослушиваний узнают «тот же трек» (`PlaybackQueue.playing`) — ошибка здесь портит сохранённую секунду или счётчик
> прослушиваний. Спорные решения и что проверить — в «На что смотреть при чтении плана (Opus)»; вопросы — там же.

- **Ветка:** `work/yu4c` · **Ворктри:** `C:\claude\Plinth-yu4c`
- **Основа:** `main` на `580c661` · **Коммит тестов:** `f3c9af9` (первый в ветке; тесты в нём не менялись после)
- **Откуда:** `tasks.md`, Ю4 «Радио от трека»; набросок «yu4c» в конце `docs/work/yu4a.md`; «Для Ю4в» в
  `docs/work/yu4b.md`; решения Ю4 в `docs/decisions.md` (2026-10-28).

## Цель

Человек нажимает «Радио» в меню трека (любой список, строки альбома провайдера, меню плеера) — и после ответа YouTube
очередь становится радио: трек, от которого оно, и подборка Mix; когда впереди остаётся пять треков, подборка
подгружается сама, не прерывая звук. Очередь радио переживает перезапуск; ошибки и конец загруженного объясняет строка
внизу. Радио работает только через YouTube и только по действию человека.

## Контекст

Прочитать (диапазонами, не целиком):

- `docs/work/README.md` — «Что исполнитель знает без памяти»; `CLAUDE.md` — «Работа Opus и Sonnet».
- Эта задача ниже целиком; таблица «Ю4 целиком» в `docs/work/yu4a.md` (строки 45–56) — решения автора и Opus, на которых
  стоит план; «Для Ю4в» в `docs/work/yu4b.md` (строки 398–410) — что умеет источник YouTube.
- Очередь: `queue/PlaybackQueue.kt` (весь), `QueueContext.kt`, `QueueItem.kt`, `FileQueueStore.kt` (весь),
  `audio/PlaybackController.kt` (весь), `audio/QueueKeeper.kt` 100–120 (`saveQueues`), `history/ListeningRecorder.kt`,
  `history/ListenTracker.kt` 38–60 (как место трека закрывает прослушивание).
- Онлайн: `online/OnlineRepository.kt` (весь), `online/OnlineProviderIds.kt`, `online/OnlineSettings.kt`,
  `ffi/CoreOnline.kt` 165–260 (`OnlineKind`, `OnlineProblem`, `OnlineTrack`, `OnlineOrigin`, `OnlineSection`).
- Действия над треком: `ui/library/TrackAction.kt`, `TrackActions.kt`, `components/TrackRow.kt`;
  `ui/online/OnlineAlbumViewModel.kt` 105–140 (как действие над треком альбома заводит его в каталог);
  `ui/player/PlayerMenu.kt`, `PlayerViewModel.kt`, `PlayerUiState.kt`, `QueuePanel.kt`, `PlayerScreen.kt` 150–165.
- Сообщения: `core/AppError.kt`, `core/ErrorPresenter.kt`, `ui/common/ErrorNotices.kt`, `ErrorNoticesViewModel.kt`.
- Запуск сервисов: `PlinthApplication.kt` 36–110; `di/OnlineModule.kt`.
- **Основа реализации** — патч планировщика `yu4c-reference-implementation.patch` в общей папке заметок сессий
  (`C:\Users\User\AppData\Roaming\Claude\side-session-notes\local_6a10ff5e-f13f-4d35-84fc-3a8758d8fb83\`, в ветке его нет):
  все файлы из «Менять», ложится на коммит тестов (`git apply`). Каждую строку сверить с «Решением» и «Инвариантами» —
  расхождения исправить, а не применять вслепую.
- Образцы тестов: `audio/QueueKeeperTest.kt` (часы теста, `MemoryQueueStore`), `history/ListeningRecorderTest.kt`,
  `queue/FileQueueStoreTest.kt`, `ui/player/PlayerViewModelTest.kt` (`Dispatchers.setMain`); сеть в памяти —
  `sharedTest/.../online/FakeOnlineRepository.kt` (`scriptRadio`, `radioCalls`).

## Находки планировщика (по ходу работы)

Записаны 2026-10-29 по чтению кода `main` на `580c661`.

1. **Ловушка «место трека» шире, чем в наброске.** `PlaybackQueue.playing` — `contextItems to индекс` — читают **три**
   потребителя, не один: `QueueKeeper.saveQueues` (набросок знал), а ещё `ListeningRecorder` → `ListenTracker.current(place,
   item)`. Смена места закрывает открытое прослушивание **как пропущенное** (`close(skipped = true)`, в журнал уходит
   `NewPlay` с `skippedAt`) и тут же открывает новое. После дописывания страницы радио в `contextItems` (а оно меняет весь
   список) играющий трек получил бы в историю ложный «пропуск» и второе прослушивание того же трека: счётчик
   прослушиваний и «пропущено» портятся — это данные пользователя. Тот же сбой есть уже сегодня при удалении из очереди
   трека контекста (`remove` → `withoutContextAt` меняет `contextItems`); ключ места, который решает радио, чинит и его.
2. **Сообщения.** Единственный канал строк внизу — `ErrorNotices` (`MainActivity.kt:146`) ← `ErrorNoticesViewModel.notices =
   merge(playback.errors, core.events).mapNotNull(presenter::present)`; сообщения о радио пойдут тем же путём (`AppError` →
   `ErrorPresenter` → `ErrorNotice` → строка в `ErrorNotices.kt`), а не новым каналом.
3. **Каталог (проверка из наброска) — вкладки не растут.** `ON_DEVICE` в `core/library/src/db/query/mod.rs` пускает во вкладки
   «Треки», «Альбомы», «Исполнители» только треки с **файлом на устройстве** (`local_uri IS NOT NULL`): «трек провайдера (E3)
   в них не виден». Треки радио (`album = null`) создают в каталоге трек, версию, источники и исполнителя по имени
   (`insert_online`), но ни одна вкладка библиотеки их не покажет; в «Любимом», плейлистах и «Недавнем» (`PLAYABLE`) они видны
   только когда человек их лайкнул, положил в плейлист или прослушал. Рост каталога — строки, не вид; чистка — отдельная
   задача после 0.3 (решение 2026-10-28).
4. **Подпись контекста нигде не показывается.** `PlaybackQueue.context` читает только `FileQueueStore`; плеер «играет из …» не
   пишет. Подпись «Радио: …» из наброска (она же — слова плана 9.5 о том, что провайдер видит запрос) придётся
   показывать новой строкой; она идёт в панель «Очередь» плеера (`QueuePanel`).
5. **`TrackActions` строится в 21 старом тесте из четырёх аргументов** (17 файлов `test`, 4 файла `androidTest`). Пятый
   аргумент без значения по умолчанию сломал бы их все, а менять старые тесты исполнитель не вправе. Решение — в «Решении».
6. **`ApplicationScope` — `Dispatchers.Main.immediate`.** Состояние `RadioFeeder` живёт на одном потоке; общий объём
   гонок — между запросом в сеть и действиями человека (смена контекста, правка очереди), а не между потоками.
7. **Поиск ищет у всех провайдеров.** `OnlineRepository.search(query)` → `Core::online_search` спрашивает **каждый** включённый
   провайдер по порядку (Internet Archive, потом YouTube): запрос «исполнитель название» уйдёт и в Internet Archive. Фильтра
   по провайдеру в API нет; добавлять его — правка Rust и моста, не этой задачи. Вопрос Opus — в «На что смотреть».
8. **`FakeOnlineRepository` уже готов под Ю4в** (`scriptRadio`, `radioCalls`, `origins` из `add`), но его `search` отдаёт только
   секцию Internet Archive с альбомами: для радио от файла с диска тесты берут двойник поверх него (`delegation`).
9. **`Buffering` — не знак «человек нажал play».** `PlaybackController.restore` готовит трек с `autoPlay = false`, и движок
   (настоящий и `FakeAudioEngine`) проходит `Idle → Buffering → Paused`. Подгрузка, разрешённая при `Buffering`, стартует
   на холодном старте до первого `play` — этого не должно быть (набросок yu4a). Разрешены `Playing` и `Ended`. Нашёл
   тест `a restored radio waits for the first play` на первом же прогоне прототипа.

## Решение

### Ю4в целиком (выбор и причина)

Решения автора и Opus по Ю4 (таблица «Ю4 целиком» в `docs/work/yu4a.md`) здесь не пересматриваются; ниже — то, что
решил планировщик сверх них или иначе, чем в наброске.

| Вопрос | Решение | Почему |
|---|---|---|
| **Что лежит в контексте радио** | `QueueContext.Radio(provider, seed, title)`: ID провайдера, затравка **так, как её принимает провайдер** (`radio(provider, seed, page)`: id видео или `<видео>/<itag>`), название трека для подписи. В наброске было `Radio(seed = TrackId, title)` | После перезапуска подгрузка открывает радио заново **от затравки**. Радио от файла с диска (самое частое: автор слушает файлы) стоит не на ID файла, а на видео YouTube, найденном поиском; ID файла у провайдера затравки не даёт (`origins` пуст) — пришлось бы искать заново по тегам первого трека очереди. Строка затравки в файле очереди делает перезапуск таким же простым, как первый запрос: `radio(provider, seed, null)`. Провайдер в контексте — чтобы второй провайдер радио (Bandcamp, v0.5) не менял формат файла |
| **Что такое «то же место»** (`PlaybackQueue.playing`) | `Place(context, index, item)` вместо `contextItems to index`: контекст, место в списке и сам трек на нём. Играет ручной трек — по-прежнему он сам | Подгрузка дописывает `contextItems`; при прежнем ключе (весь список) место играющего трека менялось бы, и **три** потребителя ошибались бы: `QueueKeeper` сбросил бы сохранённую секунду, а `ListenTracker` закрыл бы идущее прослушивание **как пропущенное** и открыл второе — в историю уходили бы ложный «пропуск» и два прослушивания одного трека (находка 1). Новый ключ не зависит от хвоста списка. Побочно он чинит то же самое при удалении трека контекста из очереди (`remove`) |
| **Как дописывается очередь** | `PlaybackQueue.extended(context, base, items)` → очередь или `null`; `PlaybackController.extend(...)` → `Boolean`, движок не трогает. Дописывает **только** если контекст тот же и в нём ровно `base` треков | `base` — защита от гонки: запрос в сети идёт секунды, за это время человек мог сыграть другое или убрать трек из очереди. Новые индексы встают в конец порядка обхода по порядку страницы (shuffle перемешивает только загруженное) |
| **Формат `queue.bin`** | Версия **2** прежняя; новый вид контекста `radio`: `UTF "radio"`, три строки (провайдер, затравка, название — через `writeText`, то есть с обрезкой до предела `writeUTF`). Ключ страницы на диск **не пишется** | Решено при чтении плана yu4a: подъём версии не дал бы прочесть очередь версии 2 при обновлении; неизвестный вид у старой версии — `IOException` → «ничего не сохранено» (так файл уже устроен), очередь пропадёт только при понижении версии приложения |
| **Кто просит страницы** | `RadioFeeder`: следит за `PlaybackController.queue` и `state`; когда **впереди по порядку обхода** (`order.size - 1 - position`) осталось ≤ 5 треков, просит следующую страницу. Один запрос за раз (разбор идёт в одной корутине, изменения очереди склеиваются, пока ждёт сеть) | 5 треков — 15–20 минут запаса при ответе страницы за 1 с (yu4a). Одна корутина вместо флага «идёт запрос» — нет гонки между флагом и очередью |
| **Когда подгрузка разрешена** | Только при состоянии `Playing` или `Ended`. На паузе, в `Idle`, `Error` и **`Buffering`** — нет | Восстановленная при старте очередь радио стоит на паузе: сеть без действия человека не трогается, подгрузка начинается с первого `play` (набросок yu4a). `Buffering` исключён потому, что возвращая очередь на паузе, движок готовит трек и проходит через `Buffering` (находка 9; первая редакция планировщика это пропустила, тест поймал). `Ended` нужен для конца загруженного (см. ниже) |
| **Откуда ключ страницы** | Только из памяти `RadioFeeder`. `RadioStarter` перед `play` зовёт `feeder.expect(context, next)` и отдаёт ключ второй страницы; `next == null` — подборка кончилась. Радио без такой памяти (очередь после запуска) получает сеанс без ключа: первая подгрузка — `page = null` | Запускающий уже держит первую страницу вместе с ключом; подгрузка начала бы с самого начала и просила бы ту же страницу второй раз. Сеанс привязан к значению контекста: другое радио или то же радио после нового `expect` — новый сеанс |
| **Повторы** | Страница сперва сверяется с каталогом **только на чтение** (`known`), потом `freshIndices` выкидывает повторы, и **в каталог идёт только то, что встанет в очередь** (`add`). Повтор — тот же трек каталога или та же пара «название + исполнитель» (без регистра и знаков; без исполнителя сравнивается только ID); сравнение — со всей очередью (сыгранное, текущее, остаток, играющий ручной и ручной блок) и внутри страницы | Каталог не должен пухнуть от двойников, которых человек не услышит. Пара названия — против «клип и lyric video»: одна песня под двумя видео (находка 3 yu4b). «Название (Live)» и «Название» — разные песни, это не склеивается |
| **Страница из одних повторов** | Берётся следующая; **три подряд — радио кончилось** (подгрузки больше нет до нового запуска радио) | Решение yu4a. Mix бесконечен, но может крутить одно и то же |
| **Беда запроса с ключом** | Один раз за подгрузку открыть радио заново: `page = null`, ключ при успехе заменяется ключом новой страницы. Беда без ключа или беда и там — настоящая: ключ остаётся, повтор **не раньше чем через 30 с** | Для приложения протухший ключ неотличим от поломки (находка 6 yu4b). Повторы по таймеру не заведены: проверка идёт при каждом изменении очереди и состояния (смена трека, `play`), это дёшево и без вечного опроса при выключенной сети |
| **Предел** | 300 треков в контексте, включая сыгранные. Страница обрезается так, чтобы уложиться ровно; потом подгрузки нет | Решение yu4a: файл очереди и каталог не растут от многочасового радио |
| **Конец загруженного** | Подгрузка не вышла (или подборка кончилась, или предел), а очередь дошла до конца (`Ended`, впереди пусто): **одно** сообщение «Радио остановилось»; очередь кончается как обычно. Страница пришла, когда конец уже случился, — играем дальше сами (`next()`) | Решение yu4a про конец; продолжение при поздней странице — чтобы медленная сеть не оставляла радио молча стоять с загруженными треками |
| **Запуск (`RadioStarter`)** | Затравка: 1) трек каталога — источник YouTube из `origins`; 2) иначе — поиск «исполнитель название» и первый **трек** из секции YouTube (названия нет — радио нет). Первая страница, и только после неё — `expect` + `play(Radio, [трек] + новое)`: очередь меняется одним шагом, ручной блок остаётся. Новое нажатие отменяет прежнее | Решения yu4a. Поиск идёт ко **всем** включённым провайдерам (находка 7) |
| **Что говорится человеку** | Четыре вести: `NEEDS_YOUTUBE` (главный переключатель или YouTube выключены; сети не трогаем), `NO_NETWORK`, `NOT_FOUND` (ничего не нашлось, провайдер не ответил, пустая страница, одни двойники затравки), `STOPPED` (конец загруженного). Идут тем же каналом, что остальные ошибки: `AppError.Radio` → `ErrorPresenter` → строка внизу; говорятся **каждый раз** | Единственный канал строк внизу (находка 2). Тексты — в «В» |
| **Плеер при нажатии не открывается** | `TrackAction.RADIO` не `startsPlayback`, «добавлено» не говорится | Очередь меняется, только когда пришла первая страница (секунды); открытый заранее плеер показал бы прежнюю очередь, а при беде — зря. Первая страница видна по мини-плееру; беда — строкой |
| **Откуда берётся пункт «Радио»** | `TrackAction.RADIO` → `TrackActions.act` (все списки, строки альбома провайдера — там трек сначала заводится в каталог, как для остальных действий) и `TrackActions.radio(item)` для плеера | Одна дверь для всех экранов |
| **Как `TrackActions` получает запуск радио** | Пятый параметр конструктора `launcher: RadioLauncher = RadioLauncher.None` | 21 старый тест строит `TrackActions` из четырёх аргументов (находка 5); менять их исполнитель не вправе. Hilt передаёт настоящий `RadioStarter` через `@Binds` |
| **Подпись «Радио: …»** | Строка над списком в панели «Очередь»: «Радио от «название» · песни берутся с YouTube» (`PlayerUiState.radio`) | Подпись контекста нигде не показывается (находка 4); слова плана 9.5 («провайдер видит запрос») должны где-то быть |

### А. Очередь (`queue/`)

`QueueContext.kt` — рядом с `File`:

```kotlin
    /**
     * Радио от трека (Ю4в): [provider] подбирает продолжение, [seed] — затравка так, как её понимает провайдер
     * (id видео или его вариант `<видео>/<itag>`), [title] — название трека, от которого радио, для подписи.
     */
    data class Radio(
        val provider: String,
        val seed: String,
        val title: String,
    ) : QueueContext
```

`PlaybackQueue.kt`:

```kotlin
    /** Место того, что играет ... (прежний комментарий остаётся, дополнить: не зависит от длины контекста) */
    val playing: Any? get() = playingManual ?: order.getOrNull(position)?.let { Place(context, it, contextItems[it]) }

    private data class Place(val context: QueueContext?, val index: Int, val item: QueueItem)

    /**
     * Дописать [items] в конец контекста [context] (радио, Ю4в). Порядок обхода дополняется концом: новые индексы —
     * по порядку; играющий трек, ручной блок и позиция не меняются. `null` — очередь за это время ушла: контекст
     * другой или в нём уже не [base] треков.
     */
    fun extended(context: QueueContext, base: Int, items: List<QueueItem>): PlaybackQueue? =
        if (this.context != context || contextItems.size != base) null
        else copy(contextItems = contextItems + items, order = order + (base until base + items.size))
```

(форматировать по стилю файла, не в одну строку: `ktlint`). `items` пуст — не оговорено, тесты этого не закрепляют.

`FileQueueStore.kt` — в `writeContext` и `readContext`:

```kotlin
            is QueueContext.Radio -> {
                writeUTF("radio")
                writeText(context.provider)
                writeText(context.seed)
                writeText(context.title)
            }
            ...
            "radio" -> QueueContext.Radio(readUTF(), readUTF(), readUTF())
```

и комментарий у `VERSION`: версия 2 прежняя, вид «radio» добавлен без подъёма.

`PlaybackController.kt`:

```kotlin
    /**
     * Дописать [items] в конец контекста [context] радио (Ю4в), пока в нём [base] треков: текущий трек не
     * прерывается, движок не трогается. `false` — очередь успела уйти, ничего не изменено.
     */
    fun extend(context: QueueContext, base: Int, items: List<QueueItem>): Boolean
```

— через `mutableQueue.update { … queue.extended(…) ?: queue }` и флаг «применено», выставляемый внутри `update`. В классе уже 19
функций — предел `detekt` (`TooManyFunctions`), поэтому `private fun restart()` встраивается в `previous()`
(`seekTo(Duration.ZERO)`); иначе `extend` не поместится.

`OnlineProviderIds.kt`: `val RADIO: Set<String> = setOf(YOUTUBE)` — кто умеет радио.

### Б. Пакет `radio` (новый, `app/src/main/kotlin/io/github/puflik/plinth/radio/`)

Сигнатуры — дословно (тела — по алгоритмам ниже; стиль файла — как у соседних классов: `@Singleton`, `@Inject constructor`).

```kotlin
/** От чего начинать радио: [item] — трек, как он стоит в очереди (он же сыграет первым), [track] — его ID в каталоге; у файла не из фонотеки и у готового адреса его нет. */
data class RadioSeed(val item: QueueItem, val track: TrackId?)

/** Пункт «Радио»: начать радио от [RadioSeed]. Не ждёт сети — ответ человеку идёт вестями (`RadioNotices`). */
fun interface RadioLauncher {
    fun start(seed: RadioSeed)

    companion object { val None = RadioLauncher { } }
}

@Singleton
class RadioNotices @Inject constructor() {
    /** Сказанное после подписки; подписчика нет — сказать некому. Буфер 8, `tryEmit`. */
    val errors: SharedFlow<AppError>

    fun tell(problem: RadioProblem)   // → errors.tryEmit(AppError.Radio(problem))
}

internal object RadioPolicy {
    const val AHEAD = 5
    const val LIMIT = 300
    const val REPEAT_PAGES = 3
    val RETRY_AFTER = 30.seconds
}

/** Какие треки [page] ещё не стоят в [queue]: индексы в порядке страницы; [known] — ID каталога у треков страницы (`null` — трека в каталоге нет). */
internal fun freshIndices(page: List<OnlineTrack>, known: List<TrackId?>, queue: PlaybackQueue): List<Int>

/** Трек радио как элемент очереди: то же, что `LibraryTrack.toQueueItem()` у сетевого трека без альбома. */
internal fun OnlineTrack.toRadioItem(id: TrackId): QueueItem   // source = AudioSource.Online(id.value), title, artist, album, duration, albumOwner = artist

@Singleton
class RadioFeeder @Inject constructor(
    private val playback: PlaybackController,
    private val online: OnlineRepository,
    private val notices: RadioNotices,
    @ApplicationScope private val scope: CoroutineScope,
    private val clock: Clock,
) {
    /** Запускает слежение (`PlinthApplication.onCreate`, после `onlineSwitch.start()`). */
    fun start()

    /** Радио [radio] только что открыто: [next] — ключ его второй страницы, `null` — подборка кончилась. Зовёт [RadioStarter] до `play`; прежняя память об этом радио забывается. */
    fun expect(radio: QueueContext.Radio, next: String?)
}

@Singleton
class RadioStarter @Inject constructor(
    private val playback: PlaybackController,
    private val online: OnlineRepository,
    private val settings: OnlineSettings,
    private val feeder: RadioFeeder,
    private val notices: RadioNotices,
    @ApplicationScope private val scope: CoroutineScope,
) : RadioLauncher
```

Часы (`Clock`) — тот же `kotlin.time.Clock`, что у `QueueKeeper`; в тестах — виртуальное время.

Правила `detekt` проекта, которые прототип уже учитывает и которые нельзя «чинить» обратно: не больше двух `return` в функции (`ReturnCount`) — поэтому `RadioStarter` собран цепочкой шагов `Step.Done`/`Step.Stopped` (`andThen`), а `RadioFeeder.load` — циклом с исходами `Outcome`; не больше трёх условий в одном `if` (`ComplexCondition`); имя файла равно единственному верхнему объявлению (`MatchingDeclarationName`) — `RadioPolicy` лежит в своём `RadioPolicy.kt`, а `freshIndices` и `toRadioItem` — в `RadioPages.kt`; строка — не длиннее 120 знаков, цепочки из нескольких вызовов — по одному вызову в строке (`ktlint`).

**`freshIndices`** (порядок страницы сохраняется):

1. Что уже в очереди: `contextItems` целиком + `playingManual` + `upNext`. Из них: ID каталога — у элементов с `AudioSource.Online(track)`; песни — пары.
2. Ключ песни = `название` и `исполнитель`, у каждого: `lowercase()`, оставить только буквы и цифры (`Char.isLetterOrDigit`). Пары нет, если пусто название **или** исполнитель — тогда сравнивается только ID.
3. Трек страницы — повтор, если его `known`-ID уже есть среди ID очереди или его пара — среди пар очереди; **после** проверки его ID и пара входят в «уже есть» (повтор внутри страницы: остаётся первый).

**`RadioFeeder` — шаг** (на каждое новое `(queue, state)`; `combine(queue, state).conflate().collect`, один шаг за раз — шаг может ждать сеть):

1. Не `Ended` → «конец объявлен» сбрасывается.
2. Контекст не `QueueContext.Radio` → выйти. Иначе `ahead = order.size - 1 - position`, `ended = state == Ended && upcoming.isEmpty()`.
3. Выйти, если не (`ahead <= 5` или `ended`) или состояние не `Playing`/`Ended`.
4. Сеанс: если сохранённый сеанс — этого же радио (равенство значений контекста), взять его, иначе новый без ключа и не законченный.
5. Подгружать можно, если сеанс не закончен, в контексте < 300 треков и не действует пауза после неудачи (`retryAt`).
6. Подгрузка (ниже). `true` — что-то дописано.
7. Если `ended`, ничего не дописано, **сейчас** (по `playback.state/queue` после шага) всё ещё конец и о нём ещё не говорено — `notices.tell(STOPPED)` и пометить «объявлен».

**Подгрузка**: `key = сеанс.next`; `base = contextItems.size`; один раз можно «открыть заново».

1. `online.radio(provider, seed, key)`.
2. `Failed`: ключ был и ещё не открывали заново → `key = null`, повторить (шаг 1); иначе `retryAt = now + 30 с`, выйти с «не вышло». Ключ сеанса при этом **не меняется**.
3. `Page`: свериться с **текущей** очередью (`playback.queue.value`): контекст другой или в нём не `base` треков → страница не нужна, выйти «не вышло» **без** паузы (ничего не записано в каталог, сеанс цел).
4. `known(provider, tracks)` → `freshIndices` → взять не больше `300 - base`. Нечего — «одни повторы». Иначе `add(provider, только новые)`; `null` или не то число ID → `retryAt = now + 30 с`, выйти.
5. Если сейчас `Ended` и впереди пусто — запомнить; `playback.extend(radio, base, items)`; `false` (очередь успела уйти, пока заводили в каталог) → как неудача: пауза 30 с, запись в сеанс не меняется; `true` и запомнено «конец» → `playback.next()`.
6. Сеанс: `next = page.next`; `finished = (page.next == null)`; повторы подряд: дописано — 0, иначе +1; три подряд → `finished`.
7. Дописано → вернуть `true`. Одни повторы и сеанс не закончен → ключ = `page.next`, шаг 1.

**`RadioStarter.open(seed)`**:

1. Нет названия (`seed.item.title == null`) → `NOT_FOUND`.
2. Главный переключатель выключен или все провайдеры из `OnlineProviderIds.RADIO` в `disabledProviders` → `NEEDS_YOUTUBE`, сеть не трогаем. Провайдер — первый включённый из `RADIO`.
3. Затравка: если есть `seed.track` — первый из `origins(track)` с этим провайдером, его `source`. Иначе (или нет такого) — `search("<исполнитель> <название>")` (без исполнителя — одно название): секция этого провайдера, первый результат вида `TRACK`, его `external`. Нет результата: у секции беда `NO_NETWORK` → `NO_NETWORK`, иначе `NOT_FOUND` (нет секции, пустая секция, другая беда).
4. `radio(provider, затравка, null)`: `Failed(NO_NETWORK)` → `NO_NETWORK`; другая беда → `NOT_FOUND`.
5. Контекст `Radio(provider, затравка, название)`; сверка страницы с очередью из одного затравочного трека (`known` → `freshIndices`), не больше 299; нечего → `NOT_FOUND`; `add` новых (`null` → `NOT_FOUND`).
6. `feeder.expect(контекст, page.next)`, затем `playback.play(контекст, [seed.item] + новые, start = 0)` (не `replace`: ручной блок остаётся, как у остальных списков).
7. `start(seed)`: прежняя корутина запуска отменяется, новая стартует в `scope`.

Затравочный трек (`seed.item`) встаёт первым **как есть**: файл с диска остаётся файлом, трек каталога — сетевым треком.

### В. Сообщения

`core/AppError.kt`:

```kotlin
    /** Радио не началось или остановилось (Ю4в): [problem] — почему; слова подбирает экран. */
    data class Radio(val problem: RadioProblem) : AppError

/** Почему радио не началось или остановилось (Ю4в). */
enum class RadioProblem { NEEDS_YOUTUBE, NO_NETWORK, NOT_FOUND, STOPPED }
```

`core/ErrorPresenter.kt`: `is AppError.Radio -> ErrorNotice.Radio(error.problem)` — **каждый раз**; `ErrorNotice.Radio(val problem: RadioProblem)`; `shown()` по нему ничего не меняет. `library/UnavailableRescan.kt` — `is AppError.Radio` в ветку «нет треков» (иначе `when` не собирается). `ui/common/ErrorNotices.kt` — слова:

| `RadioProblem` | `values/strings.xml` (имя) | EN | RU |
|---|---|---|---|
| `NEEDS_YOUTUBE` | `radio_needs_youtube` | Radio works through YouTube. Turn it on in the settings. | Радио работает через YouTube — включите его в настройках |
| `NO_NETWORK` | `radio_no_network` | No network, so the radio can’t start. | Нет сети — радио не запустить |
| `NOT_FOUND` | `radio_not_found` | No radio found for this track. | Радио для этого трека не нашлось |
| `STOPPED` | `radio_stopped` | The radio has stopped. | Радио остановилось |

`ErrorNoticesViewModel` получает третьим параметром `RadioNotices` (перед `presenter`) и сливает `radio.errors` третьим потоком в `merge`. Слова — отдельной функцией `radioText(problem): Int` рядом с `problemText`: в самой `text(notice)` ветка не помещается в предел `CyclomaticComplexMethod`.

### Г. Экраны

- `TrackAction.RADIO` (в конце перечисления; KDoc — см. выше); `PlaybackController.act` его пропускает (`-> Unit`, как лайк). `startsPlayback`/`queues` для него ложны.
- `TrackActions`: пятый параметр `private val launcher: RadioLauncher = RadioLauncher.None`; `act`: `TrackAction.RADIO -> launcher.start(RadioSeed(track.toQueueItem(), track.id))`; `fun radio(item: QueueItem)` — `scope.launch { launcher.start(RadioSeed(item, userData.trackOf(item.source))) }` (ID трека берётся по источнику: файл фонотеки — по пути, сетевой трек — свой, файл не из фонотеки и адрес — `null`).
- `TrackRow.MENU`: после «Заменить очередь» пункт `TrackAction.RADIO to R.string.track_radio` («Radio» / «Радио»).
- `PlayerViewModel.onRadio()`: `playback.queue.value.current?.let(actions::radio)`; `PlayerMenu` получает последним параметром `onRadio: (() -> Unit)? = null` (`PlayerMenuTest` в `androidTest` зовёт его без него) и показывает пункт «Радио», если `onRadio != null` и у играющего есть название; кнопка ⋮ тогда не гаснет. `PlayerScreenMenu` передаёт `viewModel::onRadio`.
- `PlayerUiState.radio: String? = null` — `title` контекста `Radio`, иначе `null`; `QueuePanel(…, radio: String? = null)` показывает над списком строку `player_queue_radio` (EN: `Radio from “%1$s” · songs come from YouTube`, RU: `Радио от «%1$s» · песни берутся с YouTube`), две строки с многоточием, цвет и фон — как у панели. Пустая очередь (`upcoming.isEmpty()`) по-прежнему даёт заглушку.
- `PlinthApplication`: поле `@Inject lateinit var radioFeeder: RadioFeeder` и `radioFeeder.start()` после `onlineSwitch.start()`.
- `di/RadioModule.kt`: `@Module @InstallIn(SingletonComponent::class) abstract class RadioModule { @Binds abstract fun launcher(starter: RadioStarter): RadioLauncher }`.

### Отвергнуто

- **`Radio(seed = TrackId, title)`** (набросок) — не переживает перезапуск для радио от файла с диска; см. таблицу.
- **Версия формата 3** — очередь версии 2 при обновлении не читалась бы (решение yu4a).
- **Прямая сверка с `queue.value` внутри `RadioFeeder` без `base`** — гонка со сменой очереди.
- **`add` всей страницы в каталог до сверки** — каталог получил бы двойники, которых никто не услышит.
- **Таймер повтора** (раз в 30 с, пока надо) — вечный опрос при выключенной сети; хватает проверки при каждом изменении очереди и состояния.
- **Открывать плеер сразу при нажатии** — см. таблицу.
- **Пятый параметр `TrackActions` без значения** — сломал бы 21 старый тест.
- **Фильтр провайдера в `online_search`** (чтобы запрос «исполнитель название» не уходил в Internet Archive) — правка Rust и моста; вопрос Opus.
- **Склеивать «Название» и «Название (Live)»** — разные песни.
- **Новый канал сообщений** вместо `AppError` — второй поток, второй `Snackbar`-сбор, второе правило «говорить ли».

## Инварианты

Данные пользователя:

- `queue.bin` пишется как прежде: временный файл, `sync`, переименование; версия формата **2**; каждая запись — **целая** очередь (после подгрузки перезаписывается весь файл), не добавка в конец. Файл, в котором есть вид «radio», прежняя сборка прочтёт как «ничего не сохранено» (принято Opus в yu4a: очередь пропадает только при понижении версии приложения).
- Сохранённая секунда текущего трека и история прослушиваний **не замечают** подгрузки: после `extend` играющее место прежнее (`playing` равно), поэтому `QueueKeeper` возвращает секунду, а `ListenTracker` не закрывает прослушивание и не открывает второе. Один трек — одно прослушивание.
- В каталог идёт **только то, что встало в очередь**: повтор (тот же ID или та же песня) не заводится; страница, которая не нужна (человек ушёл из радио, очередь изменилась за время запроса), не пишет в каталог ничего.
- `add_online_tracks` журнал не пишет (проверено в yu4a): лайки, плейлисты и журнал этой задачей не затрагиваются.
- Предел контекста радио — 300 треков, включая сыгранные; страница обрезается ровно под него.

Очередь и звук:

- `extend` не прерывает звук: `prepare` не вызывается, позиция, состояние, ручной блок, shuffle, повтор не меняются; дописывается только конец `contextItems` и `order`.
- Дописать можно только в тот контекст и при том числе треков, что были при запросе; иначе очередь не меняется и `extend` отвечает `false`.
- Очередь восстановленного радио (после запуска) стоит на паузе, и **до первого `play` ни одного запроса к сети нет**; сам `play` — первый повод.

Подгрузка:

- Один запрос радио за раз; проверка — при каждом изменении очереди и состояния, но после неудачи — не раньше чем через 30 с.
- Ключ страницы в памяти, при беде **не теряется**; «открыть заново» (`page = null`) — один раз за подгрузку, и только если был ключ.
- Три страницы подряд из одних повторов, `next == null` у страницы, предел 300 — радио кончилось: новых запросов нет до нового запуска радио.
- Подгрузка идёт только при `Playing` и `Ended`.

Запуск:

- Очередь не меняется, пока не пришла первая страница; при любой беде (нет названия, YouTube выключен, сеть, ничего не нашлось, пустая страница, не вышло завести в каталог) очередь **прежняя**, а человеку сказано одной вестью.
- Выключенный YouTube или главный переключатель — **ни одного** запроса (ни поиска, ни радио).
- Новое нажатие отменяет прежнее: из двух нажатий очередь меняет последнее.
- Ручной блок очереди переживает запуск радио (`play`, не `replace`).

Сообщения:

- Каждая весть о радио доходит до человека каждый раз (не «раз за запуск»); «Радио остановилось» — один раз на конец, а не на каждое изменение очереди, пока конец тянется.

Кодовый стиль: новых `@Suppress`, `@Ignore`, `!!` в основном коде нет.

## Файлы

- **Менять** (основной код, `app/src/main/kotlin/io/github/puflik/plinth/`):
  - `queue/QueueContext.kt`, `queue/PlaybackQueue.kt`, `queue/FileQueueStore.kt`
  - `audio/PlaybackController.kt`
  - `online/OnlineProviderIds.kt`
  - `core/AppError.kt`, `core/ErrorPresenter.kt`
  - `library/UnavailableRescan.kt` (одна ветка `when`, иначе не собирается)
  - `ui/common/ErrorNotices.kt`, `ui/common/ErrorNoticesViewModel.kt`
  - `radio/RadioSeed.kt`, `radio/RadioNotices.kt`, `radio/RadioPages.kt`, `radio/RadioFeeder.kt`, `radio/RadioStarter.kt` — **новые**
  - `di/RadioModule.kt` — **новый**
  - `ui/library/TrackAction.kt`, `ui/library/TrackActions.kt`, `ui/library/components/TrackRow.kt`
  - `ui/player/PlayerViewModel.kt`, `ui/player/PlayerUiState.kt`, `ui/player/PlayerMenu.kt`, `ui/player/QueuePanel.kt`, `ui/player/PlayerScreen.kt`
  - `PlinthApplication.kt`
  - ресурсы: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml`
  - свои новые тесты — в новых файлах `app/src/test/…`/`app/src/androidTest/…`
- **Не трогать:** Rust (`core/**`); `online/youtube/**`, `YoutubeProvider.kt`; `ui/online/OnlineAlbumViewModel.kt` и `OnlineAlbumScreen.kt` (пункт «Радио» в строках альбома провайдера появляется сам: общий `TrackRow` и общий `onTrack`); общие фейки `sharedTest/**` (в том числе `FakeOnlineRepository`, `FakeYoutubeSource`) и **все старые тесты**; `app/proguard-rules.pro`; `CHANGELOG.md`, `tasks*.md`, `docs/decisions.md`, `docs/plan/**`, `CLAUDE.md`, `gradle/libs.versions.toml`.
- **Защищённые тесты** (из коммита тестов; не менять, не удалять, не глушить):
  - `app/src/test/kotlin/io/github/puflik/plinth/queue/Yu4cQueueExtendTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/queue/Yu4cRadioQueueStoreTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/audio/Yu4cControllerExtendTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/radio/Yu4cRadioRig.kt` (стенд)
  - `app/src/test/kotlin/io/github/puflik/plinth/radio/Yu4cRadioPagesTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/radio/Yu4cRadioFeederTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/radio/Yu4cRadioStarterTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/ui/Yu4cRadioUiTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/ui/online/Yu4cRadioAlbumRowTest.kt`
  - `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/player/Yu4cRadioMenuTest.kt` (инструментальный, эмулятор)

## Тесты (написаны в коммите тестов)

68 JVM-тестов в `app/src/test` и 6 инструментальных в `app/src/androidTest` (экраны). Без нового API они не собираются (`compileGithubDebugUnitTestKotlin`: нет `QueueContext.Radio`, `PlaybackQueue.extended`, `PlaybackController.extend`, пакета `radio`, `AppError.Radio`, `TrackAction.RADIO`, `PlayerViewModel.onRadio`, `PlayerUiState.radio`); сигнатуры — в «Решении». Сеть и каталог — `FakeOnlineRepository` (через двойник `RadioOnline` в `Yu4cRadioRig.kt`: поиск по сценарию и ворота, что держат запрос, как медленная сеть); часы — виртуальное время теста. «Впереди» в сценариях — `order.size - 1 - position`.

**`Yu4cQueueExtendTest` (9) — модель очереди.** `extended` дописывает в конец контекста и больше ничего не меняет (порядок `0..6`, позиция, текущий, `upcoming`); перемешанный порядок сохраняет загруженную часть и дописывает новые индексы по порядку; ручной блок и играющий ручной трек целы; чужой контекст (другой провайдер/затравка/название, `Tracks`, пустая очередь) и неверный `base` (меньше, больше) дают `null`; идя вперёд, проходишь и дописанные; **место**: после `extended` `playing` прежнее; смена трека, другой трек на том же месте списка, тот же трек в другом контексте — другое место; повторный `play` того же списка с того же трека и shuffle — то же; убранный `remove` трек впереди место не двигает.

**`Yu4cRadioQueueStoreTest` (4) — `queue.bin`.** Радио-очередь (дописанная, с ручным блоком) читается целой вместе с позицией; первые четыре байта файла — `2`; файл, оборванный внутри строки провайдера, читается как «ничего не сохранено» (не исключение); название длиннее предела `writeUTF` (70 000 «я») обрезается до 32 767 знаков, а очередь сохраняется.

**`Yu4cControllerExtendTest` (4) — контроллер, `QueueKeeper`, история.** `extend` не зовёт `prepare`, не меняет состояние, секунду и текущий трек, возвращает `true`; устаревший вызов (`base`, контекст) — `false` и **тот же** экземпляр очереди; на паузе после `extend` сохранённая очередь выросла, а сохранённая секунда (150 с) цела — **красное при прежнем ключе места**; за 40 с + `extend` + 3 мин в историю идёт **одно** прослушивание на 3 мин 40 с без `skippedAt` — **красное при прежнем ключе** (были бы пропуск на 40 с и второе прослушивание).

**`Yu4cRadioPagesTest` (8) — повторы.** По ID каталога; по паре «название + исполнитель» без регистра, знаков и лишних пробелов (латиница, кириллица; «(Live)» — другая песня); со всем, что в очереди (сыгранное, текущее, остаток, играющий ручной, ручной блок); внутри страницы остаётся первый (в том числе по общему ID); без исполнителя сравнивается только ID; страница из одного нового остаётся целой по порядку; элемент очереди из трека радио (`Online(id)`, поля, `albumOwner = artist`).

**`Yu4cRadioFeederTest` (19) — подгрузка.** Больше пяти впереди — запросов нет; пять — один запрос `page = null` для радио без ключа; страница ложится в каталог и в конец контекста, движок не трогается; ключ каждой страницы идёт в следующий запрос, а закончившаяся подборка больше не просится; новый `expect` того же радио берёт новый ключ, ключ чужому радио не годится; повторы (ID, двойник, внутри страницы) не встают в очередь и не попадают в каталог; страница из повторов — следующая; три таких подряд — конец; один запрос за раз (пока ждёт сеть, очередь меняют, запрос один); ключ упал — один раз открыть заново, дальше с новым ключом; без ключа неудача ждёт 30 с (через 10 с нет, через 31 с есть); ключ и повторное открытие упали — ждём 30 с и пробуем ключ первым; предел 300 (страница обрезается, дальше запросов нет); чужие контексты не трогаются; восстановленное радио ждёт первого `play` — **красное при подгрузке в `Buffering`**; страница после ухода из радио отбрасывается и в каталог не пишет; конец незакормленного радио — одна весть, правки очереди («повтор» по кругу) её не повторяют, новый конец после повторной игры — говорит; страница, пришедшая после конца, продолжает игру.

**`Yu4cRadioStarterTest` (15) — запуск.** Трек YouTube из каталога открывает радио от своего источника (`vidA/251`), контекст `Radio("youtube","vidA/251", название)`, первым играет затравка, ключ первой страницы дошёл до подгрузки (запросы `null`, потом `k2`); файл с диска — затравка из поиска YouTube («Queen Bohemian Rhapsody»), первым играет сам файл; без исполнителя ищется по названию; трек Internet Archive ищется как файл; YouTube выключен (в настройках или главным переключателем) — `NEEDS_YOUTUBE`, ни поиска, ни радио, очередь прежняя; нет сети в поиске и в радио — `NO_NETWORK`; ничего не нашлось (пустая секция, нет секции, беда не сети), провайдер молчит, пустая страница, одни двойники затравки — `NOT_FOUND`; нет названия — `NOT_FOUND` без запросов; очередь прежняя, пока страницы нет; последнее нажатие побеждает; ручной блок остаётся; в каталог — только поставленное в очередь; повторный запуск начинает радио заново.

**`Yu4cRadioMenuTest` (6, инструментальный) — вид.** В меню ⋮ строки трека есть «Радио/Radio», выбор отдаёт `TrackAction.RADIO` и закрывает меню; в меню плеера пункт есть, когда передан `onRadio` и у играющего есть название (выбор вызывает его и закрывает меню), а без названия кнопка ⋮ остаётся выключенной (других пунктов нет); без `onRadio` пункта нет; над очередью радио стоит подпись `player_queue_radio` с названием, у очереди не радио её нет.

**`Yu4cRadioUiTest` (8) и `Yu4cRadioAlbumRowTest` (1) — экраны.** Только YouTube — провайдер радио; `RADIO` не открывает плеер и не «добавлено»; сама очередь `RADIO` не принимает; строка списка запускает радио с собой в роли затравки; плеер — с играющим элементом и ID каталога, если он есть (файл фонотеки — по пути, сетевой — свой, файл не из фонотеки и адрес — `null`); `onRadio` на пустой очереди ничего не делает; `PlayerUiState.radio` — название для радио, иначе `null`; вести о радио говорятся каждый раз, четыре причины; строка альбома провайдера заводит в каталог только свой трек и начинает от него.

## Проверки

Из PowerShell, в ворктри `C:\claude\Plinth-yu4c` (`JAVA_HOME` — `docs/work/README.md`):

```text
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
.\gradlew.bat testGithubDebugUnitTest --tests "*Yu4c*"                                         # защищённые: 68 из 68
.\gradlew.bat ktlintCheck detekt testGithubDebugUnitTest lintGithubDebug --continue            # всё остальное
.\gradlew.bat assembleGithubDebug compileGithubDebugAndroidTestKotlin                          # граф Hilt и инструментальные тесты собираются
.\gradlew.bat assembleGithubRelease                                                            # R8: RadioFeeder и RadioStarter в mapping.txt на месте
```

Инструментальные — только на эмуляторе `Medium_Phone_API_36.0` (`-no-window`, после работы `adb emu kill`), `$env:ANDROID_SERIAL = "emulator-5554"` — в той же команде, что Gradle, по одному классу за прогон (запятая в списке классов теряет всё, кроме первого):

```text
$env:ANDROID_SERIAL = "emulator-5554"; .\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.puflik.plinth.ui.library.TrackRowMenuTest"
$env:ANDROID_SERIAL = "emulator-5554"; .\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.puflik.plinth.ui.player.PlayerMenuTest"
$env:ANDROID_SERIAL = "emulator-5554"; .\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.puflik.plinth.ui.player.Yu4cRadioMenuTest"   # защищённый, 6 из 6
```

Число выполненных тестов — по `TEST-*.xml`, а не по «зелёной» сборке. Устройства автора не трогать.

Известное: на Windows падают 7 тестов `DataStore*SettingsTest` (`IOException` в `FileStorage.kt:114`) — не из-за задачи; в CI на Linux проходят.

## Готово, когда

- 68 защищённых тестов зелёные; прочие unit-тесты — как были (кроме известных 7 `DataStore*` на Windows); `RussianTranslationTest` зелёный (строки есть в обоих языках).
- `ktlintCheck`, `detekt`, `lintGithubDebug` чисты; `assembleGithubDebug`, `assembleGithubRelease`, `compileGithubDebugAndroidTestKotlin` собираются; в `mapping.txt` есть `RadioFeeder` и `RadioStarter`.
- `TrackRowMenuTest`, `PlayerMenuTest` и защищённый `Yu4cRadioMenuTest` (6 из 6) на эмуляторе зелёные.
- Пункт «Радио» есть в меню строки любого списка и строки альбома провайдера, а в меню ⋮ плеера — у любого играющего трека с названием; «Радио» не открывает плеер.
- Защищённые тесты не тронуты: `git diff <коммит тестов> HEAD -- <файлы защищённых тестов>` пуст; изменены только файлы из «Менять», `CHANGELOG.md`, `tasks*.md`, `docs/decisions.md`, `Cargo.lock`, `libs.versions.toml` не менялись; новых `@Suppress`, `@Ignore`, `!!` в основном коде нет.
- Hilt принимает `TrackActions` с пятым параметром `launcher = RadioLauncher.None` (собирается `assembleGithubDebug`).

## Остановиться и написать, если

- защищённый тест кажется неверным;
- для решения нужно менять файл вне списка «Менять» (в том числе старый тест);
- Hilt не принимает параметр конструктора `TrackActions` со значением по умолчанию — не обходить, а вопрос Opus (запасной путь — планировщик обновляет вызовы в старых тестах);
- проверки упали дважды подряд (одна попытка исправить, потом стоп);
- инструментальные `TrackRowMenuTest`/`PlayerMenuTest` краснеют из-за нового пункта;
- поведение на эмуляторе расходится с тестами (например, запрос радио идёт при восстановленной очереди на паузе).

## На что смотреть при чтении плана (Opus)

Решения, где планировщик отошёл от наброска в конце `yu4a.md` или добавил своё, и что в них проверить. К каждому — совет планировщика; ваш ответ допишите в «Ответы Opus» ниже.

1. **Контекст `Radio(provider, seed, title)` вместо `Radio(seed = TrackId, title)`.** Радио от файла с диска стоит на видео YouTube, которое нашёл поиск; у файла `origins` пуст. С `TrackId` файла после перезапуска подгрузке нечем открыть радио заново — пришлось бы искать по тегам первого трека очереди. Строка затравки провайдера в файле — и проще, и не зависит от каталога. Цена: три строки в `queue.bin` вместо двух (формат в версии 2 всё равно новый). *Совет: принять.* Проверить: тесты `Yu4cRadioQueueStoreTest`, таблица «Ю4в целиком».
2. **Ключ места `Place(context, index, item)` вместо `contextItems to index` — единственная правка, что меняет прежнее поведение.** (а) Нужна, чтобы подгрузка не обнуляла сохранённую секунду и не клала в историю ложный «пропуск» и второе прослушивание (находка 1; в наброске был назван только `QueueKeeper`). (б) Побочно чинит то же для удаления трека контекста из очереди (`remove`): сегодня оно закрывает идущее прослушивание как пропущенное. (в) Цена: повторное касание того же трека на том же месте **изменённого** списка (контекст тот же, хвост другой) теперь тоже «то же место» — идущее прослушивание не закрывается и не начинается второе, хотя движок запускает трек заново. Для **тождественного** списка так было и раньше. Ошибиться тут можно только в сторону «одно прослушивание вместо двух». *Совет: принять; прочитать `Yu4cQueueExtendTest` (9) — они задают, что считается тем же местом.* Если вас смущает (б) — запись в `docs/decisions.md` о чинимом сбое.
3. **`TrackActions(…, launcher: RadioLauncher = RadioLauncher.None)` — параметр со значением по умолчанию.** Иначе 21 старый тест перестаёт собираться (17 в `test`, 4 в `androidTest`), а менять их исполнитель не вправе. Запасной путь — я обновляю все вызовы в старых тестах своим коммитом. Прототип с этим параметром собирается вместе с Hilt (`assembleGithubDebug` — см. «Временная реализация»). *Совет: принять.*
4. **Поиск затравки для файла с диска идёт ко всем включённым провайдерам** (находка 7): запрос «исполнитель название» уходит и в Internet Archive, а не одному YouTube, как говорит запись yu4a. Фильтра по провайдеру в API нет; добавить его — Rust и мост, отдельная задача. Радио нажимает человек, провайдеров включил он сам. *Совет: принять и записать в решения; фильтр — в `tasks.md` как мелкое.* **Вопрос Opus:** принимаете ли утечку тегов в Internet Archive? Если нет — задача ждёт фильтра.
5. **Подгрузка не по таймеру.** После неудачи повтор — не раньше чем через 30 с, но сам повтор запускает очередное изменение очереди или состояния (смена трека, `play`); вечного опроса при выключенной сети нет. Запаса пять треков (15–20 минут) хватает на несколько смен трека. *Совет: принять.*
6. **Поздняя страница продолжает игру.** Если страница пришла, когда загруженное уже кончилось (`Ended`), `RadioFeeder` вызывает `next()` — иначе радио молча стояло бы с загруженными треками. Расширение наброска. *Совет: принять; тест `a page that comes while the loaded part has just ended carries the playback on`.*
7. **`Buffering` не повод для подгрузки** (находка 9). Первая редакция плана разрешала его и пропустила холодный старт; тест поймал на прототипе. Подгрузка только при `Playing` и `Ended`. Побочно: пока трек буферится, страницу не просят — на запас в пять треков это не влияет.
8. **Человеку при нажатии ничего не говорится, плеер не открывается.** Ответ — через 1–3 с, на плохой сети до срока вызова радио (10 с, yu4b). Обратной связи в эти секунды нет, кроме мини-плеера после ответа. *Совет: достаточно; если нет — добавить `RadioProblem.STARTING` («Запускаю радио…») за пять строк кода и один тест.* **Вопрос Opus:** нужно ли?
9. **Дедуп сравнивает «название + исполнитель» без регистра и знаков, но «(Official Audio)» и «(Live)» — это уже другая запись.** Mix часто отдаёт клип и lyric video под разными названиями; часть таких двойников пройдёт. Агрессивнее — склеивать скобки — рискует терять живые версии. *Совет: оставить; смотреть по тому, как радио ведёт себя у автора.*
10. **«Радио остановилось» видно, только пока приложение на экране** (`ErrorNotices` собирает вести лишь на `STARTED`, ревью №16). Радио чаще всего кончается с выключенным экраном; тогда человек узнает по тишине. Копить вести в фоне — отдельное решение для всех ошибок. *Совет: принять.*
11. **Предел 300 — со всеми сыгранными.** Радио на 17 часов и больше кончится «Радио остановилось» и без сдвига окна (сыгранное не выбрасывается, чтобы «назад» работало). *Совет: принять; сдвиг окна — когда кто-то услышит предел.*
12. **Три страницы подряд из одних повторов — конец радио, и после перезапуска тоже.** После запуска приложения подгрузка открывает радио заново, и первая страница Mix по той же затравке часто похожа на то, что уже в очереди. Три пустых подряд бывают редко, но если Mix у затравки мелкий, радио кончится раньше времени. *Совет: принять.*
13. **Каждый трек радио при старте тратит 2–3 с на извлечение потока** (`YoutubeSource.resolve`/`stream`, как у треков альбома): предзагрузки следующего нет. Не из этой задачи, но заметно на слух — между песнями будет пауза. *Совет: записать в `tasks.md` отдельной находкой («предварительное извлечение следующего трека радио»).*
14. **Каталог:** на страницу в среднем ≈ 24 новых трека, версии, источника и исполнителя по имени (находка 3); вкладки библиотеки их не покажут. Рост — только то, что встало в очередь (повторы и ненужные страницы в каталог не идут). Чистка — задача после 0.3, как решено.
15. **`UnavailableRescan` вынужденно тронут:** `when` по `AppError` без ветки `Radio` не собирается. Ветка пустая.

Вопросов автору нет: согласие YouTube в ЕС (вопрос yu4b) и пункт «Радио» при выключенном YouTube (он виден всегда, нажатие объясняет) решены раньше.

## Ответы Opus (чтение плана)

_(заполняет Opus до исполнителя; исполнитель по ним не гадает — стоп и вопрос в отчёте)_

## Временная реализация и проверка тестов

**Прототип** (проверено 2026-10-29) лежит патчем в общей папке заметок сессий (`yu4c-reference-implementation.patch`, все файлы из «Менять» кроме тестов; в ветке его нет); рядом — `yu4c-mutate.py` (46 мутаций прототипа по одной строке), `yu4c-mutate.json` (их итог) и `yu4c-planner-notes.md`. Против него:

- **красное до реализации:** на ветке без патча `compileGithubDebugUnitTestKotlin` не собирается — нет `QueueContext.Radio`, `PlaybackQueue.extended`, `PlaybackController.extend`, пакета `radio`, `AppError.Radio`, `RadioProblem`, `TrackAction.RADIO`, `PlayerViewModel.onRadio`, `PlayerUiState.radio` (новый API; сигнатуры — в «Решении»). Утверждениями, а не сборкой, красное доказывают мутации ниже: прежний ключ места (мутация 4) роняет пять тестов, в том числе `the saved queue grows and the saved second stays on pause` и `the history gets one listening across an extension`;
- **зелёное с патчем:** 68 JVM-тестов Ю4в из 68; `ktlintCheck` и `detekt` чисты (тесты тоже); весь `testGithubDebugUnitTest` — 1005 тестов, 1 пропущен, **7 упали — все `DataStore*SettingsTest`** (известный Windows-сбой, `FileStorage.kt:114`), прочие как были; `lintGithubDebug` чист; `assembleGithubDebug` собирается (Hilt принимает пятый параметр `TrackActions` со значением по умолчанию), `compileGithubDebugAndroidTestKotlin` собирается;
- **эмулятор** (`Medium_Phone_API_36.0`, `-no-window`, `ANDROID_SERIAL=emulator-5554`; после прогона `adb emu kill`): `Yu4cRadioMenuTest` 6 из 6, `TrackRowMenuTest` 13 из 13, `PlayerMenuTest` 8 из 8 (числа — по `TEST-*.xml`); эмулятор погашен, устройств автора не было;
- **мутации** (`yu4c-mutate.py`: 46 правок одной строки основного кода, каждая запускает все тесты Ю4в): убиты **45**. Живая одна — 36, «`expect` после `play`»: на тестовых диспетчерах возобновление подгрузки внутри корутины запуска откладывается до её конца, поэтому порядок двух вызовов не виден; он оставлен правилом «Решения» (`expect` — до `play`) и ревью, а не тестом. Две находки мутаций уже исправлены тестами: порог «впереди 6» не был закреплён (тест `a radio with more than five tracks ahead asks for nothing` теперь берёт шесть), а подгрузка при `Buffering` (первая редакция плана её допускала) нашлась обычным прогоном — `a restored radio waits for the first play`;
- **не покрыто прототипом:** живая сеть YouTube (радио через `Core.online_radio("youtube", …)` проверено в yu4b: 24 трека за ≈ 2 с) и вид экранов на телефоне автора; первую настоящую проверку на устройстве делает Opus после слияния. Прототип по-прежнему требует сверки каждой строки с «Решением»: тесты фейками доказывают поведение, а не имена и тексты.

Исполнителю: патч — основа, а не истина; `ktlintCheck`/`detekt` на нём чисты, но если что-то из «Решения» исполнитель считает неверным, стоп и вопрос в отчёте.

## Отчёт исполнителя

_Заполняет исполнитель: что сделано, что не вышло и почему, коммиты, вывод проверок (коротко)._
