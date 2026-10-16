# r9a — плеер после перепривязки: лайк и «В плейлист…» ложатся на песню каталога

> Файл задачи для исполнителя (Sonnet). Пишет Opus; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

- **Ветка:** `work/r9a` · **Ворктри:** `C:\claude\Plinth-r9a`
- **Основа:** `main` на `041b738` · **Коммит тестов:** `b8b93e6` (первый в ветке)
- **Откуда:** Р9а (`tasks.md`, «Р9а»); находка ревью R1 №1, «Сломается»
  (`docs/review/r1-review.md:47–53`). Задача с данными пользователя: до исполнителя план
  читает Opus.

## Цель

После перепривязки каталога (скан после переустановки, «Восстановить») плеер показывает
лайк и пишет лайк и «В плейлист…» на трек, который каталог знает сейчас, а не на ID, по
которому он узнал песню раньше. Сердце, поставленное в плеере, видно в «Любимом»; песня,
добавленная из меню плеера, видна в плейлисте.

## Контекст

Прочитать (диапазонами; больше не нужно):

- `docs/review/r1-review.md:47–53` — находка №1: сценарий, причина, как воспроизведено.
- `app/src/main/kotlin/io/github/puflik/plinth/ui/player/PlayerViewModel.kt` — весь (133
  строки); `history/TrackOf.kt` (17), `library/UserDataRepository.kt` (39),
  `library/CoreUserDataRepository.kt` (50), `ui/library/TrackActions.kt` (76) — тоже весь.
- Откуда берётся смена ID (читать не обязательно, но так устроена причина): перепривязка
  `core/sync/src/journal/relink.rs` (`relink`, `rekey_track` в
  `core/library/src/db/repo/passport_repo.rs`) идёт в конце скана
  (`core/ffi/src/api/scan_api.rs:98–100`) и в «Восстановить»
  (`core/sync/src/mirror/mod.rs`, `restore`). Kotlin после обоих двигает
  `PlinthCore.catalogChanged()`: `ffi/CoreScan.kt` (`finally` в `scan`) и `ffi/CoreMirror.kt`
  (`restore`). Ядро в этой задаче не меняется.
- Образцы: `test/.../ui/player/PlayerPlaylistTargetTest.kt`, `PlayerHeartTest.kt`,
  `androidTest/.../ui/player/PlayerHeartRestoreTest.kt`,
  `androidTest/.../backup/ReinstallRestoreTest.kt`; `docs/work/r6.md`, раздел «Замечено, не
  в этой задаче» — тот же недочёт назван там «ленивым поиском трека».

## Решение

### Принцип

**`PlayerViewModel` не держит `TrackId` дольше одного действия и одной перемены каталога.**
tasks.md предлагает «перечитывать ID по `catalogChanged()` или в момент действия» — нужны
оба, каждый для своего:

- **Сердце (показ)** следует за каталогом: ID трека — поток, который отдаёт новый ID, когда
  перепривязка его сменила. Одного «ID в момент действия» мало: сердце читало бы лайк
  мёртвого ID — пустое у песни, лайкнутой в журнале (снять нельзя: касание ставит
  «ещё раз» на тот же живой ID), и закрашенное у песни, которой в «Любимом» нет.
- **Запись (лайк, «В плейлист…»)** берёт ID в момент действия, а не из показа: между
  сигналом «каталог изменился» и перечиткой потока есть миллисекунды, а выбор плейлиста
  держится, пока открыт диалог — секунды.
- **Выбор плейлиста запоминает источник (путь), а не ID.** Пока диалог открыт, очередь
  может уйти дальше (Р6), и перепривязка тоже может кончиться — ID, найденный при открытии,
  к записи устареет.

### 1. `UserDataRepository` — новый метод (дословно)

```kotlin
    /**
     * Трек фонотеки, который играет из файла [uri], — сейчас и после каждой перемены. Первым
     * приходит текущий (файл не из фонотеки — `null`), дальше — когда фонотека изменилась и
     * трек под путём другой: перепривязка (скан после переустановки, «Восстановить») переводит
     * трек на ID журнала (C4). Кто держит трек дольше одного вызова — плеер, — следит за
     * потоком, а не запоминает [trackAt]: прежнего ID в фонотеке уже нет, и лайк на него
     * не увидит ни «Любимое», ни плейлист.
     */
    fun trackAtChanges(uri: String): Flow<TrackId?>
```

Контракт (проверяет общий `UserDataRepositoryContractTest`, тест
`the_track_of_a_file_follows_the_catalog`; `FakeUserDataRepository` его уже выполняет):
первое значение — результат поиска по пути на момент подписки; дальше — по каждому
сигналу «каталог изменился», но только если результат поиска другой.

### 2. `CoreUserDataRepository.trackAtChanges`

- Сигнал — `core.catalogChanges` (`StateFlow<Long>`: отдаёт текущее значение при подписке —
  отсюда первый поиск; его двигают каждая пачка записи скана, конец скана и «Восстановить»).
  Поиск — `core.library.trackAt(uri)`: блокирующий, на `io` (`flowOn(io)`, как у `liked`
  рядом). `distinctUntilChanged()`. Цена — один запрос по уникальному индексу
  `source_local`, и только пока кто-то подписан (плеер на экране).
- **Подвох: отказ ядра не должен стать ответом «файла нет».** `quietly`/`attempt` отдают
  `null` и на отказ, и на «файла нет в фонотеке»; если поток пропустит этот `null`,
  сердце исчезнет, а касание уйдёт в пустоту. Отказ — пропуск значения (поток держит
  прежнее), как `mapNotNull` у `liked`; для этого ответ поиска заворачивается в тип, у
  которого `null` внутри — «файла нет». (Мелочь: у `Flow.map` ссылка на свойство вместо
  лямбды не выводится — писать лямбду.)

### 3. `history/TrackOf.kt` — рядом с `trackOf` (дословно)

```kotlin
fun UserDataRepository.trackChangesOf(source: AudioSource): Flow<TrackId?>
```

`AudioSource.Online` → `flowOf(TrackId(source.track))`; `AudioSource.LocalFile` →
`trackAtChanges(source.uri)`; `AudioSource.Remote` → `flowOf(null)` — как у `trackOf`.

### 4. `TrackActions` — два метода по источнику (дословно)

```kotlin
    fun addToPlaylist(source: AudioSource, playlist: PlaylistId)
    fun addToNewPlaylist(source: AudioSource, name: String)
```

Как варианты по `TrackId`, но ID ищется **внутри** записи, в `scope` приложения:
`scope.launch { userData.trackOf(source)?.let { … } }`. Нашёлся — как у варианта по
`TrackId` (в конец плейлиста; для нового — `playlists.create(clean)` и запись). Не нашёлся
(файл не из фонотеки, `Remote`) — ничего, и нового плейлиста тоже нет. Имя обрезается
(`playlistName`) и пустое имя отбрасывается **до** запуска, как у варианта по `TrackId`.
Поиск — в области приложения, а не модели плеера: плеер можно свернуть сразу после выбора
(Р6, заголовок `PlayerViewModel`), область модели при этом отменяется, и запись не должна
пропасть (охранный тест ниже).

### 5. `PlayerViewModel`

Конструктор **не меняется** (его зовут `PlayerViewModelTest`, `PlayerHeartTest`,
`PlayerPlaylistTargetTest`, `PlayerHeartRestoreTest`).

- `track` (только для сердца): вместо `mapLatest { trackOf }` —
  `flatMapLatest { source -> source?.let(userData::trackChangesOf) ?: flowOf(null) }`
  после `distinctUntilChanged()` по источнику. `liked` и `uiState` как были.
- `onLike()`: `uiState.value.liked ?: return` — как было (сердца нет — ничего); дальше
  источник играющего (`playback.queue.value.current?.source`) и в
  `viewModelScope.launch` — `userData.trackOf(source)?.let { userData.setLiked(it, !liked) }`.
  **Не `track.value`.**
- `pinned` — `AudioSource?` вместо `TrackId?`: `onPlaylistPickerShown()` запоминает
  источник играющего, `onPlaylistPickerClosed()` забывает. `onAddToPlaylist(playlist)` и
  `onAddToNewPlaylist(name)` берут `pinned ?: источник играющего ?: return` и зовут
  `actions.addToPlaylist(source, …)` / `actions.addToNewPlaylist(source, …)`. ID в модели
  для записи не ищется — иначе область плеера (см. п. 4).
- Обновить KDoc заголовка класса и `track`: лайк берёт ID трека в момент действия.
- Побочный выигрыш: сердце, которое не нашлось при первом поиске (ядро не открыто, скан
  ещё не записал файл — риск 2 в `docs/work/r6.md`), теперь появляется само, на следующем
  сигнале «каталог изменился».

### Где ещё `TrackId` живёт дольше скана (проверено чтением)

| Место | Вердикт |
|---|---|
| `PlayerViewModel.track` | чинится (п. 5) |
| `PlayerViewModel.pinned` | чинится (п. 5): источник вместо ID |
| `ListeningRecorder.previous` (`history/ListeningRecorder.kt:43,74`) | не трогаем. ID прошлого трека идёт в `previous_track` следующего прослушивания; ядро это поле только пишет и читает как данные события (`history_repo.rs`, `codec.rs`), по нему трек не ищут. После перепривязки в событии окажется ID, которого в каталоге нет, — ничему не мешает. Текущий трек `record` узнаёт в момент записи. |
| Строки списков: `LibraryTrack.id` в `TrackRow`, `PlaylistPicker(track, …)` | не трогаем. Списки перечитываются по `catalogChanges`, строки ключуются `id.value` (`AlbumScreen.kt:49`, `ArtistScreen.kt:59`, `AutoPlaylistScreen.kt:44`): строка со старым ID и её открытый диалог исчезают, устаревшей записи нет. |
| `OnlineAlbumViewModel.withTrack` | не трогаем: трек заводится в каталог и берётся в момент действия. |
| Очередь (`QueueItem`, `AudioSource.LocalFile`) | путь, не ID — не страдает. |

### Отвергнуто

- **Только «ID в момент действия»**, без потока: запись правильная, а сердце — нет (см.
  «Принцип»).
- **Только поток**, без «момента действия» и без `pinned` по источнику: остаются окно в
  миллисекунды и диалог выбора плейлиста, открытый поверх конца скана.
- **Новый параметр конструктора `PlayerViewModel`** (`catalogChanges`) вместо метода
  репозитория: правка конструктора в четырёх существующих тестах и отдельный путь сигнала,
  хотя репозиторий лайков уже читает ядро по сигналам (`liked`).
- **Правка ядра** (запись «прежний ID → новый» при `rekey_track` или лайк по пути): схема
  или FFI-контракт ради единственного места, где Kotlin держит ID; шире находки. Если Р9г
  (перепривязка) решит иначе, — отдельно.
- **Искать ID для записи в `viewModelScope`**: проще, но запись пропадает, когда плеер
  свернули сразу после выбора (Р6) — охранный тест ловит.

### Что остаётся (сознательно)

Между поиском ID и записью в журнал — два вызова ядра. Перепривязка, которая кончится ровно
между ними (раз за скан с перепривязкой, миллисекунды), по-прежнему положит запись на
мёртвый ID. Закрыть можно только в ядре (запись по пути, алиасы прежних ID) — см.
«Отвергнуто». Раньше окно было на всё время, пока играет песня.

## Инварианты

- После перепривязки сердце показывает лайк трека, который каталог знает сейчас; касание
  ставит и снимает лайк именно у него.
- Лайк и «В плейлист…» из плеера берут ID **в момент действия**; единственное место в
  `PlayerViewModel`, где ID живёт дольше действия, — `track` для сердца, и он следует за
  каталогом.
- Выбор плейлиста по-прежнему закрепляет песню, на которой его открыли (даже если очередь
  ушла дальше), и забывает её, когда выбор закрыт (Р6).
- Запись в плейлист и поиск ID для неё идут в области приложения, а не в `viewModelScope`.
- Файл не из фонотеки и готовый адрес: ни лайка, ни записи в плейлист, ни нового
  плейлиста — как было.
- Отказ ядра при поиске не прячет сердце и не пишет лайк мимо: поток держит прежнее
  значение, действие при отказе поиска ничего не пишет.
- Ядро, журнал, FFI, конструкторы `PlayerViewModel` и `TrackActions` не меняются; граф Hilt
  прежний.
- `FakeUserDataRepository` и `CoreUserDataRepository` выполняют один контракт.

## Файлы

- **Менять:**
  - `app/src/main/kotlin/io/github/puflik/plinth/library/UserDataRepository.kt`
  - `app/src/main/kotlin/io/github/puflik/plinth/library/CoreUserDataRepository.kt`
  - `app/src/main/kotlin/io/github/puflik/plinth/history/TrackOf.kt`
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/library/TrackActions.kt`
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/player/PlayerViewModel.kt`
- **Не трогать:** `core/` целиком; `history/ListeningRecorder.kt`; экран и меню плеера
  (`PlayerScreen.kt`, `PlayerMenu.kt`, `PlayerUiState.kt`); `PlaylistPicker.kt`;
  `OnlineAlbumViewModel.kt`; `PlaybackController` и очередь; любые тесты и тестовые подпорки
  вне нового; `CHANGELOG.md`, `tasks*.md`, `docs/decisions.md`, `CLAUDE.md`, план,
  зависимости.
- **Защищённые тесты** (из коммита тестов; не менять, не удалять, не глушить):
  - `app/src/sharedTest/kotlin/io/github/puflik/plinth/library/FakeUserDataRepository.kt`
    — подпорка: `rekey`, `catalogChanged`, `trackAtChanges`; без `override`-метода в
    интерфейсе она не собирается — это ожидаемо, см. «Тесты»
  - `app/src/sharedTest/kotlin/io/github/puflik/plinth/library/UserDataRepositoryContractTest.kt`
    (+ тест `the_track_of_a_file_follows_the_catalog`)
  - `app/src/test/kotlin/io/github/puflik/plinth/library/GatedUserData.kt` — подпорка
  - `app/src/test/kotlin/io/github/puflik/plinth/library/R9aFakeRekeyTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/ui/player/R9aPlayerRelinkTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/ui/library/R9aTrackActionsBySourceTest.kt`
  - `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/player/R9aPlayerRestoreTest.kt`

## Тесты (написаны в коммите тестов)

**На текущем коде набор тестов не собирается:** задача вводит новый API
(`trackAtChanges`, методы `TrackActions` по источнику), а фейк, который его выполняет,
лежит в том же наборе. Первым шагом исполнитель добавляет сигнатуры из «Решения»,
п. 1–4, и смотрит, как тесты падают утверждениями.

`R9aPlayerRelinkTest` (JVM, 9 тестов, модель плеера на фейках). Перепривязку изображает
`FakeUserDataRepository.rekey`: путь тот же, ID другой, и фонотека объявила перемену —
как `PlinthCore.catalogChanged()` после скана. Журнал помнит лайк под ID `journal-song`.
На старой модели (сигнатуры есть, модель не тронута) 8 из 9 падают утверждениями:

1. `the heart follows the song to the id the relink gave it` — песня играет под прежним
   ID, сердце пустое; после перепривязки закрашено. Старая: остаётся пустым.
2. `a like comes off the song under the id the relink gave it` — после перепривязки
   касание снимает лайк с `journal-song`. Старая: ставит лайк на прежний ID.
3. `a like after the relink lands on the song the catalog has now` — лайк у `journal-song`,
   не у прежнего ID.
4. `a like tapped before the heart caught up lands on the song the catalog has now` —
   `rekey(announce = false)`: путь уже ведёт на новый ID, сигнала ещё нет; ID берётся в
   момент действия, а не из показа.
5. `the song the picker was opened on keeps up with a relink` — выбор открыт,
   перепривязка, добавить: в плейлисте песня каталога. Старая: запись на прежний ID, в
   плейлисте её нет.
6. То же для «Новый плейлист».
7. `the picker keeps its song when the queue moves on and the relink comes after` — выбор
   на первой песне, очередь ушла на вторую, первую перепривязали: в плейлисте первая, с
   ID каталога.
8. `an add to a playlist without a picker takes the song the catalog has now`.
9. `an add to a playlist that the player left behind is still written` — **охранный**,
   проходит и на старой модели: поиск ID придержан (`GatedUserData`), область модели
   отменена, ворота открыты — запись дошла. Падает у решения, которое ищет ID для
   плейлиста в `viewModelScope`.

`R9aTrackActionsBySourceTest` (6) — новые методы `TrackActions`: файл фонотеки в конец
плейлиста (порядок и повтор); ID берётся при записи, а не при выборе (ворота и `rekey`
между ними); сетевой трек — под своим ID; файл не из фонотеки и `Remote` — ни записи, ни
нового плейлиста; новый плейлист с обрезанным именем; пустое имя — ничего.

`R9aFakeRekeyTest` (2) — подпорка: смена ID под путём и сигнал — два шага (поиск видит
смену сразу, поток — после сигнала).

`UserDataRepositoryContractTest.the_track_of_a_file_follows_the_catalog` — общий
контракт, JVM-фейк и `CoreUserDataRepositoryContractTest` на эмуляторе: подписка на файл,
которого в фонотеке нет, даёт `null`; после появления файла — его трек; значения ровно
`[null, трек]`.

`R9aPlayerRestoreTest` (androidTest, **настоящее ядро**, 3 теста): скан изображает
`seedForTest`, копия журнала прошлой установки настоящая (`mirror.copy`), «Восстановить» —
`mirror.restore`.

1. `the_heart_of_a_playing_song_follows_a_restore` — в копии песня с лайком; песня играет,
   сердце пустое; после «Восстановить» закрашено; касание снимает, `likedTracks()` пуст.
   Старая модель: сердце не закрашивается (таймаут 5 с).
2. `a_like_from_the_player_after_a_restore_lands_on_the_song_the_catalog_has_now` —
   сценарий находки: в копии песня лишь в плейлисте; после «Восстановить» касание сердца;
   `likedTracks()` содержит песню. Старая: сердце закрашено, а `likedTracks()` пуст
   (`expected [Song] but was []`) — тот самый симптом.
3. `the_picker_of_the_player_keeps_up_with_a_restore` — выбор плейлиста открыт до
   «Восстановить», запись после; песня в плейлисте. Старая: записи нет (таймаут).

## Проверки

Из корня ворктри (`JAVA_HOME` — `docs/work/README.md`):

```text
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
.\gradlew.bat ktlintCheck detekt testGithubDebugUnitTest testFdroidDebugUnitTest lintGithubDebug assembleGithubDebugAndroidTest
```

Инструментальные — только на эмуляторе (`docs/work/README.md`): эмулятор в фоне
(`C:\Users\User\AppData\Local\Android\Sdk\emulator\emulator.exe -avd Medium_Phone_API_36.0 -no-window -no-audio`),
`adb devices` показывает `emulator-5554`; **`ANDROID_SERIAL` — в той же команде, что и
Gradle**; один пакет или класс за запуск; первая сборка кросс-компилирует ядро под четыре
ABI — минут десять:

```text
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"; $env:ANDROID_SERIAL = "emulator-5554"
.\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.package=io.github.puflik.plinth.ui.player"
.\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.puflik.plinth.library.CoreUserDataRepositoryContractTest"
```

Только новые JVM-тесты и соседей (быстро, для цикла «сигнатуры → падение → реализация»):

```text
.\gradlew.bat testGithubDebugUnitTest --tests "io.github.puflik.plinth.ui.player.R9aPlayerRelinkTest" --tests "io.github.puflik.plinth.ui.library.R9aTrackActionsBySourceTest" --tests "io.github.puflik.plinth.library.R9aFakeRekeyTest" --tests "io.github.puflik.plinth.library.FakeUserDataRepositoryTest"
```

Известное: на Windows падают 4 теста `DataStore*SettingsTest` — не из-за задачи; в CI на
Linux проходят. ktlint требует переносить цепочки из четырёх и больше вызовов
(`chain-method-continuation`) — смотреть на `playback.queue.value.current?.source`.

## Готово, когда

- все проверки выше проходят (кроме четырёх известных), в том числе на эмуляторе: пакет
  `ui.player` и `CoreUserDataRepositoryContractTest`;
- защищённые тесты зелёные и не тронуты (`git diff <коммит тестов> origin/work/r9a --
  <файлы тестов>` пуст); изменены только файлы из списка «Менять»;
- в `PlayerViewModel` нет поля, которое держит `TrackId` дольше показа (`track` для
  сердца), и нет поиска ID для записи в `viewModelScope`;
- в «Отчёте исполнителя» — вывод `R9aPlayerRelinkTest` и `R9aPlayerRestoreTest` и пометка,
  что сигнатуры добавлены первым шагом.

## Остановиться и написать, если

- защищённый тест кажется неверным (в том числе контрактный на ядре: с контрольной
  реализацией планировщика он зелёный на эмуляторе, но реализация исполнителя будет другой);
- для решения нужно менять конструктор `PlayerViewModel` или `TrackActions`, фейк или
  любой файл вне списка «Менять»;
- `PlayerHeartTest`, `PlayerPlaylistTargetTest`, `PlayerViewModelTest` или
  `PlayerHeartRestoreTest` красные после правки: они держат поведение сердца и выбора
  плейлиста, которое не меняется;
- проверки упали дважды подряд (одна попытка исправить, потом стоп).

## Риски, принятые сознательно

1. **Окно между поиском ID и записью** — см. «Что остаётся»: миллисекунды, раз за скан с
   перепривязкой.
2. **Поиск по пути на каждый сигнал** «каталог изменился»: во время скана сигнал идёт с
   каждой пачкой записи. Один запрос по уникальному индексу, пока плеер на экране
   (`WhileSubscribed(5 с)`); списки в это время перечитывают куда больше.
3. **Показ на миллисекунды отстаёт** от каталога: сердце может мигнуть старым состоянием,
   пока поток перечитывается. Запись при этом верна.

## Замечено, не в этой задаче

- Ядро принимает `Like` на ID, которого нет в каталоге, без ошибки (так воспроизведена
  находка в ядре) — это и скрывало её. Предупреждение в лог помогло бы замечать такие
  записи; решать в Р9г (перепривязка).
- Не проверено: `relink` сравнивает паспорта со всеми треками каталога
  (`track_passports()` без отбора по источнику) — сетевой трек со схожими название и
  исполнитель теоретически может занять место потерянного файла. Тогда `AudioSource.Online(id)`
  в очереди устареет так же, как путь здесь. Проверить в Р9г.

## Проверка плана планировщиком (для Opus)

Сделано в ворктри до коммита; реализация (контрольная) в ветку не входит, её дифф —
`r9a-reference-implementation.patch` в папке заметок сессии планировщика (233 строки).

- **JVM.** Сигнатуры из «Решения» (интерфейс, ядро, `TrackActions`, `TrackOf`) без правки
  модели плеера: `R9aPlayerRelinkTest` — 8 из 9 красных, все `AssertionErrorWithFacts`
  (не исключения); охранный зелёный. С контрольной реализацией весь
  `testGithubDebugUnitTest` — 656 тестов, красные только четыре известных `DataStore*`.
- **Мутации.** Поиск ID для плейлиста в `viewModelScope` — краснеет ровно охранный тест.
  Лайк по `track.value` из показа вместо момента действия — краснеет ровно тест 4.
- **Эмулятор** (`Medium_Phone_API_36.0`). `R9aPlayerRestoreTest`: с контрольной реализацией
  3 из 3 зелёные; на неизменной модели 3 из 3 красные (сердце не закрашивается — таймаут;
  `expected [Song] but was []`; записи в плейлисте нет — таймаут). С контрольной реализацией
  ещё: `CoreUserDataRepositoryContractTest` — 6 из 6, в том числе новый контрактный тест на
  настоящем ядре; пакет `ui.player` — 16 из 16 (в нём оба `PlayerHeartRestoreTest`).
- **Остальные проверки** на контрольной реализации: `ktlintCheck`, `detekt`,
  `lintGithubDebug`, `assembleGithubDebugAndroidTest` чисты; `testFdroidDebugUnitTest` —
  656 тестов, красные те же четыре известных.

Что стоит проверить при чтении плана:

1. Нужны ли оба механизма (поток для сердца и ID в момент действия) — по-моему, да, см.
   «Принцип». Если нужно меньше: без потока данные тоже не теряются (запись берёт ID в
   момент действия), но сердце врёт после «Восстановить» и снять лайк из плеера нельзя.
   Без «момента действия» (один поток) данные теряются в окне из «Что остаётся» и при
   выборе плейлиста, открытом поверх конца скана.
2. «Что остаётся»: согласен ли Opus оставить окно в миллисекунды или закрывать его в ядре
   (это уже Р9г).
3. Раздел 2: как `CoreUserDataRepository.trackAtChanges` различает отказ ядра и «файла нет».
4. Контрактный тест `the_track_of_a_file_follows_the_catalog` не сводится к фейку: на
   настоящем ядре он зелёный (см. «Эмулятор» выше), но настоящую перепривязку им не
   изобразить — её изображает `R9aPlayerRestoreTest`.

## Отчёт исполнителя

**Сделано** (коммит реализации `24f4963` в `work/r9a`; изменены только пять файлов из «Менять»):

- Взята контрольная реализация планировщика — `r9a-reference-implementation.patch`
  (233 строки, папка заметок планировщика; её называет раздел «Проверка плана
  планировщиком»). В запуске был указан более ранний черновик из scratchpad (230 строк):
  от финального он отличается только переносами строк (`playing()` выражением, лайк в одну
  строку), поэтому применён финальный; `git apply` лёг чисто.
- Сверка с «Решением», п. 1–5. «Дословные» блоки (KDoc и сигнатура п. 1, сигнатура п. 3,
  обе сигнатуры п. 4) найдены в коде проверочным скриптом. П. 2: сигнал
  `core.catalogChanges`, поиск `core.library.trackAt` на `io`, `distinctUntilChanged()`;
  отказ ядра пропускает значение (`mapNotNull` и `Lookup` с `null` внутри, в `map` лямбда).
  П. 5: `track` — `flatMapLatest { trackChangesOf }` после `distinctUntilChanged()` по
  источнику; `onLike` ищет ID по источнику играющего, не из `track.value`; `pinned` —
  `AudioSource?`; ID для плейлиста ищет `TrackActions` в области приложения. Конструкторы
  `PlayerViewModel` и `TrackActions`, граф Hilt, ядро, FFI не тронуты.
- **Одно расхождение патча с «Решением», исправлено мной:** п. 5 требует обновить KDoc
  заголовка `PlayerViewModel`, в патче его не было. Дописал три строки (ID файла не вечен;
  лайк и «В плейлист» берут ID в момент действия; сердце следует за каталогом) и KDoc к
  `playing()`. Только комментарии. Других расхождений не нашёл.

**Красный шаг.** Сигнатуры первым отдельным шагом я не добавлял и красное до реализации не
прогонял: по поручению сослался на раздел «Проверка плана планировщиком» — на сигнатурах без
правки модели `R9aPlayerRelinkTest` 8 из 9 красные утверждениями (охранный зелёный),
`R9aPlayerRestoreTest` на неизменной модели 3 из 3 красные. Реализация применена целиком
одним шагом.

**Проверки** (Windows; Gradle из PowerShell с `JAVA_HOME` из README; инструментальные —
эмулятор `Medium_Phone_API_36.0`, перед прогоном `adb devices` — только он, `ANDROID_SERIAL`
в той же команде, что и Gradle):

- `ktlintCheck`, `detekt`, `lintGithubDebug`, `assembleGithubDebugAndroidTest` — чисто.
  Lint: 0 ошибок, 22 предупреждения, ни одного в пяти файлах задачи. После правки KDoc
  `runKtlintCheckOverMainSourceSet` и `detekt` исполнены заново, не из кэша.
- JVM: `testGithubDebugUnitTest` и `testFdroidDebugUnitTest` — по 656 тестов, упали только 4
  известных `DataStore*SettingsTest`. Новые — все зелёные: `R9aPlayerRelinkTest` 9 из 9,
  `R9aTrackActionsBySourceTest` 6 из 6, `R9aFakeRekeyTest` 2 из 2, `FakeUserDataRepositoryTest`
  6 из 6 (в нём `the_track_of_a_file_follows_the_catalog`); соседи `PlayerHeartTest` 3,
  `PlayerPlaylistTargetTest` 4, `PlayerViewModelTest` 14 — зелёные.
- Эмулятор: пакет `ui.player` — 16 из 16 (`R9aPlayerRestoreTest` 3, `PlayerHeartRestoreTest`
  2, `PlayerMenuTest` 8, `PlayerFrameTest` 3); `CoreUserDataRepositoryContractTest` — 6 из 6,
  в том числе контрактный тест на настоящем ядре.
- Вывод `R9aPlayerRelinkTest` (9 из 9): `the heart follows the song to the id the relink gave
  it`; `a like comes off the song under the id the relink gave it`; `a like after the relink
  lands on the song the catalog has now`; `a like tapped before the heart caught up lands on
  the song the catalog has now`; `the song the picker was opened on keeps up with a relink`
  и его вариант для нового плейлиста; `the picker keeps its song when the queue moves on and
  the relink comes after`; `an add to a playlist without a picker takes the song the catalog
  has now`; охранный `an add to a playlist that the player left behind is still written`.
  Вывод `R9aPlayerRestoreTest` (3 из 3): `the_heart_of_a_playing_song_follows_a_restore`;
  `a_like_from_the_player_after_a_restore_lands_on_the_song_the_catalog_has_now`;
  `the_picker_of_the_player_keeps_up_with_a_restore`.
- Защищённые тесты не тронуты: `git diff b8b93e6 HEAD` по `app/src/test`, `app/src/androidTest`
  и `app/src/sharedTest` пуст. Новых `@Ignore`, `@Suppress`, `#[allow]` в диффе нет; файлов вне
  «Менять» нет. Попыток исправить упавшие проверки не было: красные — только четыре известных.
- **CI** (`api.github.com`, ветка `work/r9a`, прогон на `24f4963`): оба задания зелёные —
  «Rust core — format, clippy, tests» и «Style, tests, both flavors»
  (ktlint, detekt, Android lint both flavors, Unit tests both flavors, Assemble both flavors,
  Release APK within the size budget, Assemble instrumented tests), прогон 38903577780.
  Прогон на `fc56b89` (коммит тестов) красный — набор тестов без нового API не собирается,
  как сказано в «Тесты»; причину по логу не разбирал.

**На что глянуть при ревью:**

- `onLike` ищет ID внутри `viewModelScope.launch` — так задано п. 5 (лайк и раньше писался из
  области модели). «Готово, когда»: «нет поиска ID для записи в `viewModelScope`» относится к
  плейлисту; там поиск в `TrackActions`, в области приложения, и охранный тест зелёный.
- `onLike` берёт `!liked` из показа, а ID — из момента действия. В миллисекунды после смены
  песни, пока сердце не перечиталось, касание применит состояние прежней песни к новой
  (например, снимет лайк с песни, у которой его нет). Безвредно; тот же класс, что риск 3.
- Настоящий экран плеера на устройстве я не открывал: `PlayerScreen`, `PlayerMenu` и
  `PlaylistPicker` не менялись, поведение модели закрыто JVM-тестами и тестами на настоящем
  ядре.
- Эмулятор запущен мной и остановлен после прогонов.

**Вопросов к решению нет**: условия «Остановиться и написать» не сработали (защищённые тесты
верны, `PlayerHeartTest`, `PlayerPlaylistTargetTest`, `PlayerViewModelTest`,
`PlayerHeartRestoreTest` зелёные, файлов вне списка не менялось).
