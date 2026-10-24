# yu3g1 — переключатель по провайдерам: YouTube и Internet Archive (Ю3г, часть 1)

> Файл задачи для исполнителя (Sonnet). Пишет планировщик; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

- **Ветка:** `work/yu3g1` · **Ворктри:** `C:\claude\Plinth-yu3g1`
- **Основа:** `main` на `479462c` (Ю3в в `main`). **Коммиты тестов:** `bf37dc1` (Rust), `9d58106` (Kotlin), `61afd61` (двойник в
  `GatedOnlineRepositoryTest`) — защищённые тесты сверять с последним из них (`61afd61`)
- **Откуда:** `tasks.md`, строка 389 (Ю3); `docs/work/yu1.md`, раздел 6 (строка Ю3г); `docs/work/yu3v.md`,
  «Что не проверено» и комментарий в `online/OnlineProviders.kt` («выбор по сборке и по переключателю — Ю3г»).
  Ю3г разбита на три задачи с непересекающимися файлами — раздел «Дальше» внизу; эта — первая.
- **Данные пользователя трогает по краю.** Каталог и журнал не меняются, но в настройках появляется новый сохранённый
  ключ, а ID провайдеров (`archive.org`, `youtube`) — ключи данных пользователя. Меняется граница ядра (UniFFI).
  **До исполнителя план читает Opus** — раздел «На что смотреть при чтении плана (Opus)».

> **Где остановился и что дальше** (планировщик, 2026-10-24; остановлен по слову автора). Готово и запушено: этот файл, тесты `bf37dc1`
> (Rust), `9d58106` и `61afd61` (Kotlin); тесты проверены временной реализацией — в ветке её нет, патчи лежат в папке заметок сессии Opus
> (`yu3g1-reference-*`), итоги — в «Временная реализация и мутации». Ветка названа `work/yu3g1`, не `work/yu3g`. **Не сделано:** мутанты
> Kotlin; прогон на Linux (CI) для `DataStoreOnlineSettingsTest`; `CoreOnlineRepositoryContractTest` на эмуляторе; Compose-экран и строки
> (в временной реализации только модель экрана); подробные планы и тесты `yu3g2`, `yu3g3` — есть лишь наброски в «Дальше». **Дальше:** Opus
> читает план («На что смотреть…», девять вопросов) → при согласии исполнитель по этому файлу; `yu3g2` можно планировать и запускать
> параллельно — файлы не пересекаются.

## Цель

В настройках «Онлайн-источники» у каждого провайдера — свой переключатель (Internet Archive, YouTube). Ядро
подключается только к включённым: выключенного не опрашивают, секции в поиске у него нет, его треки остаются
в каталоге и не играют, пока он выключен.

## Контекст

Читать только это:

- `docs/work/README.md`, «Что исполнитель знает без памяти» (Gradle, эмулятор, известные падения).
- Ядро: `core/ffi/src/api/online_api.rs` — `connect_online` (≈ 150–170), `registry_with` и `registry` (≈ 326–355);
  `core/providers/src/registry.rs` — `Registry::new` (пустой список не ошибка) и `stream` (≈ 100–125: **незнакомый
  провайдер пропускается**, итог — `Unavailable`), `find` (≈ 133: «unknown provider»).
- Kotlin: `ffi/CoreOnline.kt` (≈ 30–45, `connect`/`disconnect`), `online/OnlineSettings.kt`, `online/OnlineSwitch.kt`,
  `online/OnlineRepository.kt`, `online/CoreOnlineRepository.kt` (`setEnabled`), `online/GatedOnlineRepository.kt`,
  `online/OnlineProviders.kt`, `ui/settings/OnlineSources.kt`, `ui/settings/SettingsScreen.kt` (≈ 55–100),
  `ui/online/ProviderName.kt`.
- Тесты по образцу: `app/src/test/.../online/OnlineSwitchTest.kt`, `ui/settings/OnlineSourcesViewModelTest.kt`,
  `app/src/test/.../settings/DataStoreSortSettingsTest.kt`; контракт — `app/src/sharedTest/.../online/OnlineRepositoryContractTest.kt`.

## Решение

### А. ID провайдеров — в одном месте

`archive.org` (Internet Archive, Rust) и `youtube` (`YoutubeProvider.id`) — ID ядра и ключи данных пользователя: не менять.
Новый файл `online/OnlineProviderIds.kt`:

```kotlin
package io.github.puflik.plinth.online

/** ID провайдеров ядра в порядке секций поиска и списка в настройках; те же ID — в каталоге пользователя. */
object OnlineProviderIds {
    const val ARCHIVE: String = "archive.org"
    const val YOUTUBE: String = "youtube"

    /** Internet Archive — в ядре; остальные — провайдеры приложения (`onlineProviders()`). */
    val ALL: List<String> = listOf(ARCHIVE, YOUTUBE)
}
```

Новый провайдер (Bandcamp и т. д.) добавляется в этот список и в `onlineProviders()`; `OnlineProviderIdsTest` держит их согласными.

### Б. Ядро: `connect_online(transport, providers, archive)`

```rust
pub fn connect_online(
    &self,
    transport: Arc<dyn NetTransport>,
    providers: Vec<Arc<dyn ExternalProvider>>,
    archive: bool,
) -> Result<(), CoreError>
```

- `archive == true` — как сейчас: Internet Archive первым, затем `providers` в своём порядке.
- `archive == false` — реестр из одних `providers`. Internet Archive **не создаётся**: ни одного запроса к archive.org,
  ни одного объекта.
- Пустой реестр (`archive == false`, `providers` пуст) — **не ошибка**: `online_search` — пустой список секций,
  `online_stream` — `Unavailable`, `online_album` — `Unavailable`. Kotlin такой вызов не делает (раздел В), ядро допускает.
- Остальное как в Ю3в: повторный вызов заменяет транспорт и провайдеров и сбрасывает здоровье; негодный провайдер —
  ошибка, прежнее состояние остаётся (и при `archive == false`).
- `registry_with(transport, pause, archive, external)`; тестовый `registry` зовёт его с `true`.
- Каталог (`add_online_tracks`, `known_online_tracks`) от подключения не зависит и не меняется: треки выключенного
  провайдера остаются, их `online_stream` — `Unavailable` (реестр пропускает незнакомого), включение возвращает игру.
- Тестовый модуль и вызовы уже в коммите тестов `bf37dc1`: строка `#[cfg(test)] mod yu3g_tests;` в `api/mod.rs` (между
  `test_api` и `yu3v_tests` — порядок `rustfmt`) и третий аргумент `true` во всех 13 вызовах `connect_online(` в
  `r1_11a_tests.rs` и `yu3v_tests.rs` (механическая правка планировщика; утверждения те же). Эти файлы защищены.
  Пока `connect_online` не получит третий аргумент, тесты крейта `plinth-ffi` не собираются — это ожидаемо.

### В. Kotlin

Сигнатуры ниже записаны в строку ради чтения; форматирование — как вокруг и как требует `ktlintCheck` (при двух и более параметрах — в
столбик, с висячей запятой). Защищённые тесты прошли `ktlintCheck` и `detekt` вместе с временной реализацией планировщика (замечания были только к её main-коду).

**`ffi/CoreOnline.kt`:** `connect(transport, providers = emptyList(), archive = true)` передаёт `archive` в `connectOnline`.
Умолчание сохраняет вызовы `online.connect(archive)` в `androidTest` (`ExternalProviderCoreTest`).

**`online/OnlineSettings.kt`** — интерфейс и `DataStoreOnlineSettings`:

```kotlin
interface OnlineSettings {
    val enabled: Flow<Boolean>
    suspend fun setEnabled(enabled: Boolean)

    /** ID провайдеров, которые человек выключил. Пусто — включены все; новый провайдер включён, пока его не выключат. */
    val disabledProviders: Flow<Set<String>>

    suspend fun setProviderEnabled(provider: String, enabled: Boolean)
}
```

В DataStore — ключ `stringSetPreferencesKey("online_providers_off")`; ключ `online_sources` (главный, по умолчанию включён) остаётся
как был. `setProviderEnabled` меняет набор внутри одного `store.edit`: добавляет или убирает ровно один ID; пустой набор — ключ
удаляется. **Незнакомые ID в наборе не трогаются** (провайдер, которого в этой версии нет; откат версии). Храним *выключенных*, а не
включённых: иначе новый провайдер после обновления оказался бы выключен у всех.

**`online/ActiveSources.kt`** (новый; чистая функция, поэтому проверяется на JVM, без ядра):

```kotlin
internal class ActiveSources(val archive: Boolean, val providers: List<OnlineProvider>) {
    /** Подключать нечего: выключены все. */
    val isEmpty: Boolean get() = !archive && providers.isEmpty()
}

/**
 * [all] — провайдеры приложения (без Internet Archive: он в ядре), [disabled] — ID выключенных.
 * Порядок [all] сохраняется; незнакомые ID в [disabled] ничего не значат.
 */
internal fun activeSources(all: List<OnlineProvider>, disabled: Set<String>): ActiveSources =
    ActiveSources(archive = OnlineProviderIds.ARCHIVE !in disabled, providers = all.filter { it.id !in disabled })
```

**`online/OnlineRepository.kt`:**

```kotlin
/**
 * Главный переключатель «Онлайн-источники» и выбор провайдеров: [enabled] выключено — поиска, альбомов и потоков нет;
 * иначе подключаются все провайдеры, кроме [disabled] (ID из `OnlineProviderIds`). Включены, но выключены все
 * провайдеры — то же, что выключено.
 */
suspend fun setEnabled(enabled: Boolean, disabled: Set<String> = emptySet())
```

**`CoreOnlineRepository.setEnabled`:** `val active = activeSources(providers, disabled)`; подключать — если `enabled && !active.isEmpty`:
`core.online.connect(transport, active.providers, active.archive)`, иначе `core.online.disconnect()`. Сообщение журнала
(`connect`/`disconnect`) — по тому, что сделано. `GatedOnlineRepository.setEnabled(enabled, disabled)` передаёт внутрь; «ворота
открывает любой исход» остаётся.

**`online/OnlineSwitch.kt`:** вместо `settings.enabled.distinctUntilChanged().collect { setEnabled(it) }` —
`combine(settings.enabled, settings.disabledProviders)`; пока главный выключен, набор провайдеров в расчёт не идёт (`Applied(false,
emptySet())`), чтобы выбор провайдеров при выключенном главном не дёргал ядро; `distinctUntilChanged()`; `collect` зовёт
`online.setEnabled(applied.enabled, applied.disabled)`. Включили главный — применяется текущий набор.

**Экран.** `OnlineSourcesViewModel` получает `val disabledProviders: StateFlow<Set<String>>` (пусто до первого значения) и
`fun onProviderToggle(provider: String, enabled: Boolean)` → `settings.setProviderEnabled`. `onlineSources(...)` в `OnlineSources.kt`
после строки главного переключателя рисует по строке на каждый ID из `OnlineProviderIds.ALL`: заголовок — `providerName(id)`,
пояснение — строка ресурсов по ID (`when`; незнакомому ID — без пояснения), справа `Switch`, строка `toggleable(role = Role.Switch)`,
выключена и недоступна, пока главный выключен. `SettingsScreen` передаёт набор и обработчик. `providerName` получает
`"youtube" to "YouTube"` (имена собственные не переводятся).

**Строки** (оба файла; `RussianTranslationTest` требует перевод, не равный английскому):

| Ключ | en | ru |
|---|---|---|
| `settings_online_summary` (меняется) | Search music online: requests go to the sources below | Поиск музыки в сети: запросы уходят на источники ниже |
| `settings_online_archive_summary` (новый) | Live recordings and public-domain music: requests go to archive.org | Концерты и музыка в общественном достоянии: запросы уходят на archive.org |
| `settings_online_youtube_summary` (новый) | Requests go to youtube.com and Google servers | Запросы уходят на youtube.com и серверы Google |

### Г. Что видит человек

Настройки: главный переключатель и под ним два провайдера. YouTube выключен — в поиске нет его секции, его сохранённые треки не
играют (`Unavailable`, как при потере файла), лайки, плейлисты и история целы. Включили — играют снова, переустановки не нужно.
Выключены оба провайдера — как выключен главный: поиск без сетевой секции. Сообщения «YouTube выключен» нет (Ю6).

### Отвергнуто

- **Выключенный провайдер остаётся в реестре, его пропускает фильтр** (`set_provider_enabled`): не «`connect_online` получает только
  включённых», держит здоровье и объекты выключенного.
- **Хранить включённых**: новый провайдер выключен у всех после обновления.
- **Список встроенных провайдеров вместо `archive: bool`**: встроенный один; список — проверка имён ради ничего.
- **Отдельный экспортный `connect_online_without_archive`**: два входа в одно, а Kotlin мог бы позвать не тот.
- **Ошибка на пустом реестре**: пустой реестр равен выключенному; ошибка — лишняя ветка.
- **Флаг сборки «без YouTube» для `fdroid`**: решение автора 2026-10-19 — YouTube в обеих сборках; запасной вариант — отдельной задачей,
  если F-Droid откажет.

## Инварианты

1. Ничего не выключено (по умолчанию) — как до задачи: `connect(transport, providers, archive = true)`; ключ `online_sources` читается как был.
2. Выключенный провайдер не получает ни одного запроса: Internet Archive не создаётся, YouTube не передаётся ядру.
3. Каталог и журнал не затронуты: `known`/`add` работают при любом наборе; треки выключенного провайдера остаются.
4. Выключены все ≡ выключен главный: `disconnect`.
5. Выбор переживает перезапуск; незнакомый ID в сохранённом наборе не теряется, когда переключают другие.
6. Главный выключен — смена провайдеров не дёргает ядро; включили — применяется текущий набор.
7. ID `archive.org`, `youtube` и формат вариантов (`<видео>/<itag>`) не меняются.
8. Ошибка `connect` видна так же, как раньше (`attempt` пишет в журнал; экран не падает).

## Файлы

- **Менять:**
  - `core/ffi/src/api/online_api.rs`
  - `app/src/main/kotlin/io/github/puflik/plinth/ffi/CoreOnline.kt`
  - `app/src/main/kotlin/io/github/puflik/plinth/online/OnlineSettings.kt`, `OnlineSwitch.kt`, `OnlineRepository.kt`, `CoreOnlineRepository.kt`, `GatedOnlineRepository.kt`
  - `app/src/main/kotlin/io/github/puflik/plinth/online/OnlineProviders.kt` — только комментарий (выбор по переключателю теперь в `activeSources`; по сборке выбора нет)
  - новые: `app/src/main/kotlin/io/github/puflik/plinth/online/OnlineProviderIds.kt`, `.../online/ActiveSources.kt`
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/settings/OnlineSources.kt`, `.../ui/settings/SettingsScreen.kt`, `.../ui/online/ProviderName.kt`
  - `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml` (общие файлы отданы этой задаче явно — только три строки из таблицы)
- **Не трогать:** `online/YoutubeProvider.kt`, `online/youtube/**`, `di/OnlineModule.kt`, `audio/**`, `app/build.gradle.kts`, `app/proguard-rules.pro`,
  `gradle/libs.versions.toml`, `CHANGELOG.md`, `tasks*.md`, `docs/decisions.md`, `CLAUDE.md`, план, ADR; существующие тесты, кроме трёх файлов выше.
- **Защищённые тесты** (из коммитов тестов; не менять, не удалять, не глушить):
  - `core/ffi/src/api/yu3g_tests.rs` (и строка `mod yu3g_tests;` в `core/ffi/src/api/mod.rs`)
  - `core/ffi/src/api/r1_11a_tests.rs`, `core/ffi/src/api/yu3v_tests.rs` — в коммите тестов только третий аргумент `true` в вызовах `connect_online(`
  - `app/src/test/kotlin/io/github/puflik/plinth/online/OnlineProviderIdsTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/online/ActiveSourcesTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/online/OnlineSwitchProvidersTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/online/DataStoreOnlineSettingsTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/ui/settings/OnlineSourcesProvidersViewModelTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/online/GatedOnlineRepositoryTest.kt` — в коммите тестов только подпись `setEnabled(enabled, disabled)` у тестового
    двойника («a switch that fails still lets the search through»): иначе он «overrides nothing»; утверждения те же
  - `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/FakeOnlineSettings.kt`, `FakeOnlineRepository.kt`, `OnlineRepositoryContractTest.kt`
    (в двух фейках уже новый API; в контракте — три новых случая в конце)

## Тесты (написаны в коммитах тестов)

**Rust** — `core/ffi/src/api/yu3g_tests.rs`, 8 тестов; сеть Internet Archive — записанные ответы со счётчиком запросов, платформа —
«youtube» в памяти. На текущем коде крейт не собирается (у `connect_online` два аргумента) — новый API, сигнатура в «Решении Б».

1. `with_the_archive_on_it_comes_first_and_is_asked` — `(…, [youtube], true)` → секции `[archive.org, youtube]`, к Internet Archive ушли запросы (как было).
2. `without_the_archive_the_platform_is_alone_and_the_archive_is_never_asked` — `false` → одна секция `youtube` с одним результатом и **ноль** запросов к Internet Archive.
3. `without_the_archive_its_album_is_a_problem_of_the_screen_not_an_error_of_the_call` — `online_album("archive.org", …)` → без треков, `problem == ProviderDown`, запросов нет.
4. `a_track_of_the_archive_stays_in_the_catalog_while_the_archive_is_off_and_plays_again_when_it_is_on` — трек в каталоге; `false` → `online_stream` = `Unavailable`;
   `known_online_tracks` находит тот же ID, повторный `add_online_tracks` отдаёт те же ID; `true` → тот же адрес.
5. `switching_the_archive_off_and_on_gives_the_same_search_each_time` — `true` → `false` → `true`: поиск после возврата равен первому.
6. `a_track_of_the_platform_stays_in_the_catalog_while_it_is_not_connected_and_plays_again_when_it_is` — провайдер не передан: `Unavailable`, секции `[archive.org]`,
   платформу не спрашивали, каталог цел, возврат — играет (это «YouTube выключен»).
7. `an_empty_registry_is_quiet_nothing_is_found_nothing_plays_and_nothing_is_asked` — `(Vec::new(), false)` → `Ok`; поиск пуст, поток `Unavailable`, альбом `ProviderDown`, никого не спросили.
8. `a_refused_provider_keeps_the_previous_connection_also_without_the_archive` — негодное имя при `false` → ошибка, прежние секции на месте.

**Kotlin (JVM, `app/src/test`)** — на текущем коде не собираются: нет `OnlineProviderIds`, `activeSources`, `OnlineSettings.disabledProviders` и `setProviderEnabled`,
второго параметра `OnlineRepository.setEnabled`, `OnlineSourcesViewModel.disabledProviders` и `onProviderToggle`.

- `OnlineProviderIdsTest` (3): ID дословно; `ALL` = архив + `onlineProviders()`; на экране у каждого имя, не ID.
- `ActiveSourcesTest` (6): ничего не выключено; YouTube выключен; архив выключен; выключены оба → `isEmpty`; порядок провайдеров и чужой ID; без провайдеров приложения.
- `OnlineSwitchProvidersTest` (5): по умолчанию применяется `Applied(true, {})`; каждое изменение набора доходит целиком и по порядку; то же значение дважды не применяется;
  главный выключен — выбор не доходит, включили — применяется текущий набор; фейк следует выбору.
- `OnlineSourcesProvidersViewModelTest` (4): включены все; выключенный запоминается, другой включён; включённый обратно уходит из набора; главный не трогает набор.
- `DataStoreOnlineSettingsTest` (6): умолчание; старый ключ главного `online_sources`; независимость от главного; снятие одного; **незнакомый ID выживает**; ключ
  `online_providers_off` на диске. На Windows может упасть по известной причине (раздел «Проверки») — судит CI.
- Общие, `app/src/sharedTest`: `FakeOnlineSettings` (+набор выключенных), `FakeOnlineRepository` (`applied`, `disabled`; выключенный Internet Archive — ни секции, ни альбома, ни потока),
  `OnlineRepositoryContractTest` (+3 случая в конце). Контракт проходят и фейк (JVM, `FakeOnlineRepositoryTest`), и настоящее ядро (эмулятор, `CoreOnlineRepositoryContractTest`):
  выключенный провайдер не даёт секцию, альбом и поток, но `known` находит его треки; включённый обратно работает; чужой ID среди выключенных ничего не меняет.

Не покрыто автотестом: сам Compose-экран (строки провайдеров) — ручная проверка в «Проверки».

## Проверки

```text
cd core && cargo test --workspace && cargo clippy --workspace --all-targets -- -D warnings && cargo fmt --all -- --check
.\gradlew.bat ktlintCheck detekt lintGithubDebug testGithubDebugUnitTest testFdroidDebugUnitTest   (JAVA_HOME — docs/work/README.md)
```

На эмуляторе (в той же команде `$env:ANDROID_SERIAL = "emulator-5554"`; **фильтр по пакету, а не списком классов**):

```text
.\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.package=io.github.puflik.plinth.online"
.\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.package=io.github.puflik.plinth.ffi"
```

(`OnlineRepositoryContractTest` на настоящем ядре — пакет `online`; граница `connect` — пакет `ffi`.) Счёт — по `TEST-*.xml`: «BUILD SUCCESSFUL» при
потерянном фильтре ничего не доказывает.

Руками на эмуляторе (кратко, в отчёт — что видел): Настройки → «Онлайн-источники»: три строки; YouTube выключить → поиск «piano» — только
Internet Archive; включить, а Internet Archive выключить → только YouTube; оба выключить → без сетевой секции; главный выключить → строки
провайдеров недоступны, а их состояние сохранилось после включения; перезапуск приложения — выбор на месте.

Известное: на Windows падают 4 теста `DataStore*SettingsTest` (`IOException` в `FileStorage.kt:114`) — не из-за задачи; `DataStoreOnlineSettingsTest`
этой задачи может упасть так же — его судит CI на Linux (ветка пушится, CI гоняет `work/**`).

## Готово, когда

- Все «Проверки» проходят; защищённые тесты зелёные (кроме `DataStoreOnlineSettingsTest` на Windows — по CI).
- `connect_online` получает третий аргумент; `grep -rn "connect_online(" core` — везде три аргумента; `connectOnline` зовётся из одного места (`CoreOnline.connect`).
- В Настройках три строки; поведение — по разделу Г; ID не изменились (`git diff main -- app/src/main/kotlin/io/github/puflik/plinth/online/YoutubeProvider.kt` пуст).
- Никакого `#[ignore]`, `@Ignore`, `@Suppress`, `#[allow]`, ослабленных утверждений.

## Остановиться и написать, если

- защищённый тест кажется неверным;
- для решения нужно менять файл вне списка «Менять» (в первую очередь `OnlineModule.kt`, `YoutubeProvider.kt`);
- проверки упали дважды подряд (одна попытка исправить, потом стоп);
- `RussianTranslationTest` или `lint` требуют строку, которой нет в таблице;
- `uniffi` не принимает третий аргумент так, как написано (например, привязки Kotlin ждут другой порядок);
- выясняется, что реестр без Internet Archive ведёт себя иначе, чем описано в «Решении Б» (пустой реестр, `Unavailable`).

## На что смотреть при чтении плана (Opus)

1. **Граница ядра.** Третий аргумент `archive: bool` у `connect_online` — вместо отдельного входа или фильтра внутри реестра. Меняет сигнатуру UniFFI;
   13 вызовов в тестах Р1.11а и Ю3в уже правлены планировщиком (только аргумент). Согласен?
2. **«Выключены оба провайдера» при включённом главном** = `disconnect`: поиск без сетевой секции, подсказки на экране нет (Ю5/Ю6). Нужна ли подсказка сейчас?
3. **Формат на диске.** Новый ключ DataStore `online_providers_off` — набор *выключенных*. Старая версия его не знает и включает всё; незнакомые ID в наборе не теряются.
   Хранить *включённых* отвергнуто: новый провайдер (Bandcamp) был бы выключен у всех.
4. **Список в настройках** — константа `OnlineProviderIds.ALL`, а не вывод из `onlineProviders()`: экран не должен создавать `NewPipeYoutubeSource`. Согласованность держит `OnlineProviderIdsTest`.
5. **Тексты строк** (особенно YouTube: «запросы уходят на youtube.com и серверы Google») — на ваш вкус; `settings_online_summary` меняется (старый текст говорил только об archive.org).
6. **`providerName("youtube")` = «YouTube»** сделано здесь, хотя Ю3в оставил название Ю5: иначе строка настроек подписана ID. Ю5 поправит, если нужен значок.
7. **`OnlineRepository.setEnabled(enabled, disabled = emptySet())`** — параметр по умолчанию в интерфейсе, реализации без умолчаний. Отдельный тип `OnlineSources` отвергнут как лишний.
8. **Неохваченное автотестом:** Compose-экран (проверяется руками), `androidTest` для настроек нет.
9. **Разбиение Ю3г** — раздел «Дальше»: `yu3g1 ∥ yu3g2`, `yu3g3` после `yu3g1`. Вопросы по ним — там же.

## Временная реализация и мутации

Планировщик не пишет код задачи, но проверил, что тесты верны: временная реализация (в ветку не вошла).

- **Rust.** Патч — `yu3g1-reference-implementation-rust.patch` в папке заметок сессии (11 строк в `online_api.rs`). Все 8 тестов `yu3g_tests` зелёные; весь `plinth-ffi` — 161 тест зелёный
  (с 13 исправленными вызовами); `cargo clippy -p plinth-ffi --all-targets -- -D warnings` чисто; `cargo fmt --check` — настоящих `Diff in` нет.
  Мутанты: **M1** — `archive` игнорируется (Internet Archive всегда создаётся) — падают 5 тестов (2, 3, 4, 5, 7); **M2** — при `archive == false` пропадают и провайдеры платформы — падают 2 (2, 5).
- **Kotlin.** Патч — `yu3g1-reference-implementation-full.patch` (Rust + Kotlin без новых файлов) и `yu3g1-reference-new-files/` (`ActiveSources.kt`,
  `OnlineProviderIds.kt`) в папке заметок; Compose-строки провайдеров и ресурсы в нём **не сделаны** — только модель экрана. На нём (Windows): `OnlineProviderIdsTest` 3/3,
  `ActiveSourcesTest` 6/6, `OnlineSwitchProvidersTest` 5/5, `OnlineSourcesProvidersViewModelTest` 4/4, контракт фейка (`FakeOnlineRepositoryTest`) 14/14 с тремя новыми
  случаями; старые `GatedOnlineRepositoryTest` 11, `OnlineSwitchTest` 3, `RussianTranslationTest` 4 — зелёные; `compileGithubDebugAndroidTestKotlin` собирается;
  `ktlintCheck` и `detekt` к тестам замечаний не дали. **`DataStoreOnlineSettingsTest`: 3 из 6 падают `IOException` в `FileStorage.kt:114`** — известная беда
  Windows (вторая запись в тот же файл; так же падают `DataStore*SettingsTest`); эти три **не проверены, нужен CI на Linux**. Первый прогон нашёл, что
  `GatedOnlineRepositoryTest` держит свой двойник `OnlineRepository.setEnabled(Boolean)` — поправлен в `61afd61`. **Мутанты Kotlin не гонялись**; заготовка —
  `mutate_kotlin.py` в папке заметок (K1 — набор доходит при выключенном главном; K2 — архив всегда включён; K3 — включение стирает набор; K4 — переключатель
  провайдера в модели экрана наоборот).

## Дальше: остальные задачи Ю3г

Ю3г = «довести YouTube до выпуска 0.3». Не влезает в день, поэтому три задачи; файлы не пересекаются, кроме `SettingsScreen.kt` и `strings.xml` у `yu3g3` и `yu3g1`
(поэтому `yu3g3` после слияния `yu3g1`). **Релиз 0.3 — только после всех трёх** (`docs/work/yu3v.md`: «релиз до Ю3г не выпускать»).

**yu3g2 — R8 и release на трёх эмуляторах.** Файлы: `app/proguard-rules.pro`, новый `docs/testing/youtube-live.md`, `docs/testing/apk-size.md`, `.github/workflows/ci.yml`, один JVM-страж правил.
- Заменить временные `-dontwarn java.beans.*` рабочими правилами. Проверенный набор — в `git show spike/yu3a:app/proguard-rules.pro`: `-keep` для `timeago.patterns.**`,
  `org.mozilla.javascript.**` (и `.engine.**`), `ClassFileWriter`, `javax.script.**`, `jdk.dynalink.**` + `-dontwarn`, поля `GeneratedMessageLite`. Предупреждение
  `Unexpected reference to missing service class: META-INF/services/javax.script.ScriptEngineFactory` — снять правилом или объяснить (Ю3в: «тоже Rhino»).
  Сужать Rhino (+0,66 МиБ, 69 % — из-за широкого `-keep`, `yu3a.md` §5 п. 7) — только если каждое сужение проверено сценарием на устройстве; иначе оставить широкое и записать.
- Страж: JVM-тест читает `proguard-rules.pro` и держит правила (удалить «лишнее» легко — сломается только в release); дополнительно проверка по `mapping.txt`/`seeds.txt` после
  `minifyGithubReleaseWithR8`: классы Rhino и `timeago.patterns` не переименованы.
- `assembleGithubRelease` и `assembleFdroidRelease` (или `minifyFdroidReleaseWithR8`) собираются; CI: бюджет 30 МБ (`ci.yml`, шаг «Release APK within the size budget») держится; `fdroid` release в CI —
  решить (нужен хотя бы `minifyFdroidReleaseWithR8`).
- **Release играет YouTube на эмуляторах API 36 (`Medium_Phone_API_36.0`), 30 (`Plinth_API_30`), 26 (`Plinth_API_26`)** — поиск, минута игры, перемотка; на 26 и 30 нужен десугаринг (Ю3б).
  Сети у эмулятора хватает (Ю3а ходил в живой YouTube). Приложение ведут руками через `uiautomator dump` + `input tap` (память проекта: «Ручная проверка экрана на эмуляторе без окна»);
  если секция YouTube в поиске не читается (названия — Ю5), хватит ID в логе. Итог по трём API — таблица в отчёте.
- `docs/testing/youtube-live.md` — **ручной сценарий «живой YouTube»** (не в CI): поиск, игра, перемотка, качество, срок ссылки, 403; формат таблицы — как `docs/testing/v0.2-checklist.md`
  (колонки «Эмулятор» и «Телефон»). Его же исполнитель проходит как проверку.
- Оценка 4–6 ч; параллельно с `yu3g1`.

**yu3g3 — выпуск: NOTICE, «О приложении», метка fdroid.** После `yu3g1`. Файлы: новый `NOTICE`, `app/build.gradle.kts` (`buildConfigField` версии экстрактора из `libs.versions.toml`),
`ui/settings/SettingsScreen.kt` и новый блок «О приложении», `strings.xml`, `fastlane/metadata/android/en-US/full_description.txt`, `docs/BUILD.md`, `README*.md`, заголовки у `online/youtube/**`.
- **Экрана «О приложении» в приложении нет** (версия видна только в диагностике), поэтому его заводит эта задача: версия и flavor (`AppInfo`), версия экстрактора — **одним источником**
  `libs.versions.toml` (`newpipeExtractor`; `yu3a.md` §5 п. 1), ссылка на исходники (AGPL), «Лицензии» — текст `NOTICE`. Вопрос Opus: отдельный экран или секция в настройках.
- `NOTICE` в корне и в ресурсах: NewPipeExtractor (GPL-3.0-or-later), Rhino (MPL-2.0 — с адресом исходников), jsoup (MIT), protobuf-javalite (BSD-3-Clause), nanojson (Apache-2.0),
  десугаринг (проверить по отчёту лицензий Gradle, `yu1.md` §8 их не проверял). Плюс AGPL-3.0 Plinth и совместимость с GPL-3.0 — одной строкой.
- Заголовки в исходниках: там, где код выведен из кода NewPipe (кандидат — `YoutubeDownloader`), — ссылка на источник и лицензию; в остальных не плодить. Происхождение исполнитель проверяет
  сам; сомнение — вопрос Opus.
- **Метка NonFreeNet у `fdroid`** — это метаданные F-Droid (`AntiFeatures: NonFreeNet` в `fdroiddata`), в репозитории её нет. В репозитории: честное описание YouTube в `full_description.txt`
  и раздел F-Droid в `docs/BUILD.md` с готовой записью для `fdroiddata`. Вопрос автору/Opus: нужен ли файл `.fdroid.yml` в корне.
- Оценка 3–5 ч.

## Чтение плана (Opus, 2026-10-24)

План принят, исполнитель работает по этому файлу. Ответы на вопросы:

1. `archive: bool` третьим аргументом `connect_online` — да.
2. «Выключены оба» при включённом главном = `disconnect`, без подсказки — да; подсказка — Ю5/Ю6.
3. Ключ `online_providers_off`, набор *выключенных*, незнакомые ID не теряются — да. Это формат данных пользователя: после
   выпуска не менять без миграции.
4. Список в настройках — константа `OnlineProviderIds.ALL` под стражем `OnlineProviderIdsTest` — да.
5. Тексты строк — как в таблице.
6. `providerName("youtube") = "YouTube"` здесь — да.
7. Умолчание `disabled = emptySet()` в интерфейсе — да.
8. Compose-экран — руками на эмуляторе, что видел — в отчёт. Мутанты Kotlin (K1–K4) исполнителю гонять не нужно.
9. Разбиение `yu3g1 ∥ yu3g2`, `yu3g3` после `yu3g1` — да. Для `yu3g3`: «О приложении» — секция в конце настроек (версия, flavor,
   версия экстрактора, исходники), «Лицензии» — текст `NOTICE` отдельным экраном; `.fdroid.yml` не нужен — метка `NonFreeNet`
   живёт в `fdroiddata`.

`DataStoreOnlineSettingsTest` на Windows судит CI (раздел «Проверки»).

## Отчёт исполнителя

_Черновик, пишется по ходу (бюджет недели почти выбран — работа может оборваться по лимиту)._

**Шаг 1 — ядро (сделано).** `core/ffi/src/api/online_api.rs`: `connect_online(transport, providers, archive)`;
`registry_with(transport, pause, archive, external)` — Internet Archive создаётся только при `archive == true`, иначе реестр из одних
`providers` (пустой — не ошибка); тестовый `registry` зовёт `registry_with(…, true, …)`.
`cargo test --workspace` — зелёный (в `plinth-ffi` все 8 `yu3g_tests` проходят), `cargo clippy --workspace --all-targets -- -D warnings` — чисто,
`cargo fmt --all -- --check` — только «Incorrect newline style» (CRLF рабочей копии на Windows), настоящих `Diff in` нет.

**Шаг 2 — Kotlin-модель (сделано).** `ffi/CoreOnline.kt` (`connect(transport, providers, archive = true)`), новые `online/OnlineProviderIds.kt` и
`online/ActiveSources.kt`, `OnlineSettings.kt` (`disabledProviders`, `setProviderEnabled`, ключ `online_providers_off`), `OnlineRepository.kt`
(`setEnabled(enabled, disabled = emptySet())`), `CoreOnlineRepository.kt`, `GatedOnlineRepository.kt`, `OnlineSwitch.kt` (`combine` + `distinctUntilChanged`),
комментарий в `OnlineProviders.kt`; в `OnlineSourcesViewModel` — `disabledProviders` и `onProviderToggle`; `providerName("youtube")` = «YouTube».
`testGithubDebugUnitTest` по пакетам `online` и `ui.settings` (161 тест): `OnlineProviderIdsTest` 3/3, `ActiveSourcesTest` 6/6, `OnlineSwitchProvidersTest` 5/5,
`OnlineSourcesProvidersViewModelTest` 4/4, `FakeOnlineRepositoryTest` 14/14, `GatedOnlineRepositoryTest` 11/11, `OnlineSwitchTest` 3/3;
`DataStoreOnlineSettingsTest` — 3 из 6 падают `IOException` в `FileStorage.kt:114` (известное для Windows; судит CI на Linux).
