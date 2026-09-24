# Архитектура Plinth

Карта проекта: где что лежит, какие границы нельзя пересекать и как звук
доходит от файла до динамика. Документ короткий намеренно — подробности живут
в других местах:

- **зачем так** — [docs/decisions.md](decisions.md);
- **что делаем** — [plan.md](../plan.md) и [tasks-v0.1.md](../tasks-v0.1.md);
- **как собрать** — [README.ru.md](../README.ru.md).

Обновляется при появлении каждого нового модуля (A3.1).

---

## Один модуль Gradle

Пока весь код — в `:app`. Разделение на модули придёт, когда появится второй
потребитель кода (десктоп в v0.2+); дробить его раньше — платить за сборку без
выгоды. Границы слоёв держатся пакетами и тестами-стражами, а не модулями.

## Пакеты

Корень — `io.github.puflik.plinth`.

| Пакет | Ответственность | Что ему запрещено |
|---|---|---|
| `audio/engine` | Абстракция воспроизведения: интерфейс, модели состояния, событий и ошибок | **Любой импорт Android**, включая `androidx.media3` |
| `audio/media3` | Реализация движка на ExoPlayer, `MediaSessionService`, уведомление, аудиофокус | Знать об экранах |
| `audio` | `PlaybackController` — фасад для UI и хозяин очереди; `QueueKeeper` — её сохранение | — |
| `library` | Локальная фонотека: разрешения, сканер, база, сортировка | Знать об экранах |
| `queue` | Очередь воспроизведения, порядок, повтор, её файл | **Любой импорт Android**; знать об экранах и библиотеке |
| `diagnostics` | Логи, обход вендорских ограничений, отчёты об ошибках | — |
| `settings`, `startup` | Настройки и первый запуск; пока — `SortSettings`, порядок списков библиотеки в DataStore | — |
| `di` | Проводка графа Hilt | Содержать логику |
| `ui` | Compose: тема, навигация, экраны | Обращаться к движку иначе как через `PlaybackController`, к библиотеке — иначе как через `LibraryRepository`, `LibraryScan` и `FolderSettings` |
| `core` | Мелочь, общая для всех: конфигурация, расширения | Зависеть от `ui` |
| `flavor` | То, чем различаются сборки `github` и `fdroid` | Лежать в `main` |

## Главная граница: `AudioEngine`

```
     ui/player ──► PlaybackController ──► AudioEngine (интерфейс)
                                               ▲
                            ┌──────────────────┴──────────────────┐
                      Media3Engine                          FakeAudioEngine
                    (ExoPlayer, Android)                  (тесты, чистый Kotlin)
```

Правило: **ни один тип из `androidx.media3` не пересекает границу
`audio/engine`.** Реализация знает об интерфейсе, интерфейс о реализации — нет.

Ради чего терпим лишний слой:

- десктопный клиент в v0.2+, где Media3 не существует;
- движок на Rust (R3), который встанет на то же место, пройдя те же тесты;
- тестируемость: экраны и очередь проверяются на `FakeAudioEngine`, без
  эмулятора и без звука.

Правило сторожит `EngineBoundaryTest` — он читает исходники `audio/engine` и
падает на первом же импорте Android. Контракт реализации описан
`AudioEngineContractTest`: набор требований в коде, а не в комментарии.

### Из чего состоит абстракция

| Тип | Что описывает |
|---|---|
| `AudioEngine` | Команды: `prepare` · `play` · `pause` · `seekTo` · `setVolume` · `release` |
| `AudioSource` | Откуда брать звук: локальный файл (`content://`, `file://`) или поток с заголовками |
| `PlaybackParams` | Стартовая позиция, автостарт, следующий трек для gapless |
| `PlaybackState` | Что происходит сейчас: `Idle` · `Buffering` · `Playing` · `Paused` · `Ended` · `Error` |
| `PlaybackEvent` | Что случилось: позиция, буферизация, конец трека, ошибка |
| `PlaybackError` | Типизированная причина: нет источника, формат, сеть, неизвестно |

Команды не приостанавливаются (`suspend` нет): движок принимает их и отвечает
асинхронно — через `state` (актуальное значение сразу после подписки) и
`events` (только то, что случилось после подписки).

### Как `Media3Engine` обходится с потоками

ExoPlayer разрешает обращаться к себе только из одного потока. В приложении
это главный поток: плеер — синглтон процесса (`di/AudioModule`), его делят
`Media3Engine` и `PlaybackService`, а `MediaSessionService` требует плеер на
главном looper. Декодирование при этом идёт во внутреннем потоке ExoPlayer.
Команды движка приходят из UI, службы и тестов: команда проверяет аргументы и
состояние в потоке вызывающего — так контракт требует бросать исключения, — и
уходит в поток плеера сообщением. Обратно плеер отвечает через
`state`/`events`, которые безопасно читать откуда угодно. Команды с внешних
пультов (уведомление, гарнитура) приходят в плеер через сессию, мимо движка,
но движок их видит: адаптер слушает сам плеер.

Каждый `prepare` получает номер. Пока новый источник не дошёл до плеера,
сообщения плеера относятся к прошлому и наверх не идут: иначе запоздавшая
ошибка старого трека пометила бы новый как неготовый.

| Файл `audio/media3` | Что делает |
|---|---|
| `ExoPlayerFactory` | Создаёт плеер на заданном `Looper`: атрибуты «музыка», аудиофокус, пауза при выдернутых наушниках |
| `MediaItemMapper` | `AudioSource` → `MediaItem`; заголовки потока пока отвергает |
| `PlayerListenerAdapter` | Обратные вызовы ExoPlayer → состояние, события, типизированные ошибки; позиция раз в 500 мс, пока звук идёт |
| `Media3Engine` | Реализация `AudioEngine`: проверки, публикация; создаётся в потоке плеера |
| `PlaybackService` | `MediaSessionService`: сессия на том же плеере, уведомление и foreground — силами Media3 |
| `NotificationChannels` | Идентификаторы каналов уведомлений |

## Вторая граница: `LibraryRepository`

```
     ui/library ──► LibraryRepository (интерфейс) ◄── library/scan (сканер пишет)
          │                 ▲
          │  ┌──────────────┴──────────────┐
          │  RoomLibraryRepository   FakeLibraryRepository
          │  (над library/db, Android) (тесты, чистый Kotlin)
          │
          ├──► LibraryScan (интерфейс) ◄── WorkManagerLibraryScan → ScanWorker → LibraryScanner
          │                                                            │
          └──► FolderSettings (интерфейс) ◄── DataStoreFolderSettings ◄──┘ (какие папки)
```

Весь эпик C временный: в v0.2 Room и сканер заменит ядро на Rust. Переживёт
его только фасад `LibraryRepository` — и только если экраны ходят в
библиотеку через него. Фасадов три: `LibraryRepository` — данные и поиск,
`LibraryScan` — запуск скана и его прогресс, `FolderSettings` — какие папки
сканировать. Правило сторожит
`LibraryBoundaryTest`: `ui/**` не импортирует `library/db` и `library/scan`,
а фасады, модель (`library/model`) и сортировка (`library/sort`) не
импортируют Android. Требования к
хранилищу — `LibraryRepositoryContractTest`, устроенный как контракт движка.

| Тип | Что описывает |
|---|---|
| `LibraryTrack` | Трек v0.1: `id` из `MediaStore`, `content://`, теги, папка, время изменения |
| `Album`, `Artist` | Собираются из треков, своих записей нет. Альбом — название + владелец (исполнитель альбома, иначе трека) |
| `LibraryFolder` | Дерево папок из `folder` треков: подпапки в естественном порядке, треки — в порядке списка; исчезнувшая папка открывается ближайшей уцелевшей |
| `NaturalOrder`, `ArticleStripper`, `SortKeys` | Ключ сортировки: без артикля, регистра и диакритики, числа по значению. База сортирует по ключу, посчитанному при записи |
| `FolderConfig` | Какие папки сканировать: включённые со всеми подпапками, кроме исключённых; умолчание — `Music` и `Download`; правка — `include`, `exclude`, `remove` |
| `SearchQuery` | Поиск: каждое слово запроса — подстрока названия, исполнителя или альбома, без регистра и надстрочных знаков. Room ищет им же в Kotlin, поверх списка по названию |
| `CodePointOrder` | Сравнение строк по кодовым точкам, как в SQLite и Rust; им сортирует всё, что сортирует ключи в Kotlin |
| `TrackSort`, `AlbumSort` | Варианты порядка списков |

### Как Room держит порядок контракта

Контракт один на фейк и на Room, поэтому SQL повторяет компаратор фейка
один в один, и расхождение ловят те же тесты. Всё держится на трёх приёмах:

- **Ключи считаются при записи.** `RoomLibraryRepository.upsert` кладёт в
  строку рядом с тегом его ключ (`title_key`, `artist_key`, `album_key`,
  `album_owner_key`) — без артикля, регистра и диакритики, числа дополнены
  нулями. SQL сортирует ключи обычным сравнением строк — побайтово в UTF-8,
  то есть по кодовым точкам; фейк сравнивает так же (`CodePointOrder`), а не
  `String.compareTo` по UTF-16. Нет тега — нет и ключа.
- **Пустое — в конце: `ORDER BY x IS NULL, x`.** `NULLS LAST` появился в
  SQLite 3.30, а на Android 8 — 3.18. Исключение — диск: без номера он идёт
  первым, и это обычный порядок SQLite, где `NULL` меньше любого значения.
  Равные треки — по `media_store_id`, равные альбомы — по точному названию и
  владельцу.
- **Группы — по точным значениям.** Альбом — `GROUP BY album, album_owner`
  (владелец — исполнитель альбома, иначе трека, хранится готовым),
  исполнитель — `GROUP BY artist`. Экран альбома ищет треки по
  `album_owner IS :owner`: у альбома без исполнителей владелец `NULL`, а
  `NULL = NULL` в SQL ложно.

Пропавший трек помечается (`missing = 1`) и остаётся в таблице: его видит
`upsert`, но не видит ни один список. `markMissing` режет список `id` на
порции по 999 — больше параметров одним запросом SQLite до 3.32
(Android 8–11) не примет.

| Файл | Что делает |
|---|---|
| `library/db/PlinthDatabase` | База v1, одна таблица `tracks`; схема каждой версии — в `app/schemas/` |
| `library/db/entity/TrackEntity` | Строка трека: теги, владелец альбома, ключи, флаг `missing`; перевод из модели и обратно |
| `library/db/dao/TrackDao` | Запросы списков, альбомы и исполнители через `GROUP BY`, `upsert`, `markMissing` |
| `library/db/Converters` | `Duration` ↔ миллисекунды |
| `library/RoomLibraryRepository` | Фасад поверх `TrackDao`: выбирает запрос, переводит строки в модель |
| `di/LibraryModule` | База — синглтон процесса, фасад поверх неё; `LibraryEntryPoint` для тестов |

### Сканер

```
MediaStore (системный сканер уже прочитал теги)
     │  MediaStoreSource: строки IS_MUSIC → MediaStoreRow
     ▼
LibraryScanner ── FolderConfig: только сканируемые папки
     │          ── ScanDiff: knownVersions() против _ID + DATE_MODIFIED
     │          ── TagReader: MediaStoreRow → LibraryTrack
     ▼
LibraryRepository.upsert (новые и изменённые) · markMissing (пропавшие)
```

Своей библиотеки тегов в v0.1 нет (решение C2): теги читает системный
сканер, приложение берёт их из `MediaStore`. Сканер пишет через фасад,
как любой клиент библиотеки, поэтому на JVM он проверяется на
`FakeLibraryRepository` и фейковом источнике. Android знает только
`MediaStoreSource`; остальное — чистый Kotlin.

| Файл `library/scan` | Что делает |
|---|---|
| `DataStoreFolderSettings` | `FolderSettings` в DataStore: два набора строк; нет ключа — умолчание (выбор, равный умолчанию, пишется так же), пустой набор — «ничего» |
| `MediaStoreRow` | Строка `MediaStore` как есть, без типов Android |
| `MediaStoreSource` | Запрос к `MediaStore`: папка из `RELATIVE_PATH` (Android 10+) или из `DATA` |
| `TagReader` | Соглашения `MediaStore`: `<unknown>` — нет тега, `TRACK` = диск × 1000 + номер, без названия — имя файла |
| `ScanDiff` | Что перечитать и что пометить пропавшим — по `_ID` и времени изменения |
| `LibraryScanner` | Собирает всё вместе и возвращает `ScanResult`: найдено, записано, пропало; пишет порциями по 500 с отчётом о прогрессе и точкой отмены |
| `ScanWorker` | `CoroutineWorker` от Hilt: скан в фоне, прогресс через `setProgress`, итог — выходные данные |
| `WorkManagerLibraryScan` | `LibraryScan` поверх уникальной работы `library-scan` (`KEEP`): состояние работы → `ScanProgress` |

Скан запускает `LibraryViewModel`, когда разрешение становится выданным: при
старте приложения и сразу после выдачи, — и кнопка «Rescan» на экране папок.
Папки `ScanWorker` читает из `FolderSettings` в начале каждого скана. WorkManager настраивает
`PlinthApplication` (`Configuration.Provider` с `HiltWorkerFactory`),
автоматическая инициализация WorkManager в манифесте выключена.

### Разрешение

`MediaPermission` выбирает разрешение по версии Android (`READ_MEDIA_AUDIO`
с 13-го, раньше `READ_EXTERNAL_STORAGE`), `PermissionState.of` — чистая
функция: выдано, не спрашивали, отказано (объяснить и спросить снова),
отказано навсегда (только настройки). Android не говорит прямо про «навсегда»:
это `shouldShowRequestPermissionRationale == false` после ответа на запрос.
Экран спрашивает сам при первом показе и проверяет разрешение при каждом
возврате — его могли выдать или отозвать в настройках.

### Экраны библиотеки

```
LibraryScreen (стартовый) ── вкладки: TracksTab · AlbumsTab · ArtistsTab · FoldersTab
     │  LibraryViewModel: LibraryRepository (списки, порядок) + LibraryScan + PlaybackController
     ├── касание трека ──► PlaybackController.open(source, title) ──► PlayerScreen
     ├── касание альбома ──► AlbumScreen (Album — аргументами навигации) ──► PlayerScreen
     └── меню: «Now playing» ──► PlayerScreen · «Open file» (SAF) ──► PlayerScreen

SearchScreen (вкладка) ── SearchViewModel: запрос с задержкой 300 мс → LibraryRepository.search
FolderSettingsScreen (вкладка «Настройки») ── FolderSettings + LibraryScan: папки, «Rescan»
```

Плеер и альбом открываются поверх вкладок, нижняя навигация на них скрыта.
Строка трека одна на все списки (`components/TrackRow`), карточка альбома —
пока без обложки (`components/AlbumCard`). Название играющего хранит
`PlaybackController.title`: его видят и библиотека, и плеер.

## Очередь

```
экран: касание / долгое нажатие ──► TrackAction ──► PlaybackController.act
                                                        │ play · replace · perform
                                                        ▼
            PlaybackQueue (queue/, неизменяемая) ◄── next/previous: кнопки плеера,
                    │ current                         уведомление, гарнитура
                    ▼                                 (QueueCommandsPlayer в сессии)
            AudioEngine.prepare ── TrackEnded ──► PlaybackController: следующий
                    │
            QueueKeeper ──► FileQueueStore (filesDir/queue): очередь и позиция
```

Очередь = контекст (видимый список: треки, альбом, папка, поиск или один
файл) плюс ручной блок, который переживает смену контекста; shuffle — порядок
обхода, а не перестановка. ExoPlayer держит один трек: соседей знает только
очередь, поэтому сессия стоит на `QueueCommandsPlayer`, который объявляет
«следующий/предыдущий» и отправляет их в `PlaybackController`. При старте
процесса `QueueKeeper` возвращает сохранённую очередь на паузе на той же
секунде.

| Файл | Что делает |
|---|---|
| `queue/PlaybackQueue` | Контекст, порядок обхода, позиция, ручной блок; `play`, `replace`, `perform`, `next`, `previous`, shuffle, repeat |
| `queue/QueueItem`, `QueueContext`, `QueueAction`, `ShuffleOrder` | Элемент (источник и подписи), откуда контекст, действия долгого нажатия и повтор, порядок обхода |
| `queue/QueueStore`, `FileQueueStore` | Сохранённая очередь и позиция; свой двоичный формат с версией |
| `audio/QueueKeeper` | Восстановление при старте и сохранение по ходу |
| `audio/media3/QueueCommandsPlayer` | Плеер сессии: next/prev — в очередь, слушатели видят эти команды |
| `ui/library/TrackAction` | Действие над треком списка и перевод `LibraryTrack` в `QueueItem` |

## Поток данных первой вертикали

```
библиотека (content://media/…) или SAF: ACTION_OPEN_DOCUMENT
        │  content:// URI
        ▼
   AudioSource.LocalFile
        │
        ▼
   PlaybackController ──► AudioEngine.prepare/play/pause/seekTo
        │                        │
        │                 state / events
        ▼                        ▼
   PlaybackService          ui/player: кнопки, полоса перемотки
   (MediaSession: фон, уведомление, гарнитура, экран блокировки)
```

Дальше вертикали: сканер `MediaStore` наполняет библиотеку, очередь решает,
что играть следующим, — но в движок всё равно приходит `AudioSource`.

## Наборы исходников

| Набор | Что там |
|---|---|
| `src/main` | Приложение |
| `src/github`, `src/fdroid` | Различия сборок: `FlavorConfig` |
| `src/test` | JVM-тесты |
| `src/androidTest` | Тесты на эмуляторе: всё, что требует настоящего Media3 или SQLite |
| `src/androidTest/assets/tags` | Секунда тишины с тегами на формат (MP3, FLAC, M4A, без тегов) для сканера; рецепт — `tools/make_tag_fixtures.py` |
| `src/sharedTest` | Общее для двух предыдущих: контрактные тесты, `FakeAudioEngine`, `FakeLibraryRepository` |

`sharedTest` подключён к обоим наборам в `app/build.gradle.kts`. Иначе
контракт пришлось бы писать дважды: фейки проверяются на JVM, настоящие
реализации (`Media3Engine`, Room) — на эмуляторе.

## Зависимости

- **DI — Hilt.** Граф собирается в `di/`; диспетчеры корутин выдаются через
  граф (`@IoDispatcher`, `@DefaultDispatcher`), чтобы в тестах их можно было
  подменить.
- **UI — Compose + Material 3**, одна `Activity`, навигация в `ui/navigation`.
- **Звук — Media3/ExoPlayer**, спрятан за `audio/engine`.
- **Хранение — Room** с компилятором на KSP, спрятан за `LibraryRepository`.
  Схема выгружается в `app/schemas/` (плагин `androidx.room`).
- **Версии** — только в `gradle/libs.versions.toml`.

## Что уже есть

Вертикаль «звук из файла» пройдена целиком: каркас (эпик A), абстракция
движка (B1), `Media3Engine` (B2 без прогона семи форматов и gapless),
фоновая служба с сессией (B3.1), `PlaybackController` и экран плеера
`ui/player/PlayerScreen` — слушать, пауза, перемотка. Идёт вертикаль «библиотека на `MediaStore`» (эпик C, шаги — в
`docs/decisions.md`): готовы модель, сортировка, контракт фасада
`LibraryRepository` с фейком, хранилище на Room, которое проходит тот же
контракт на эмуляторе, сканер `MediaStore`, разрешение и фоновый скан через
WorkManager, экран библиотеки со списками треков, альбомов, исполнителей и
папок, экран альбома и сортировка, поиск и выбор папок для сканирования. Файл
мимо библиотеки открывается через SAF из меню. Вертикаль «очередь» (эпик D)
тоже пройдена: контексты с ручным блоком, shuffle и повтор, next/prev из
уведомления и гарнитуры, очередь переживает перезапуск.
