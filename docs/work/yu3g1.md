# yu3g1 — переключатель по провайдерам: YouTube и Internet Archive (Ю3г, часть 1)

> Файл задачи для исполнителя (Sonnet). Пишет планировщик; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

- **Ветка:** `work/yu3g1` · **Ворктри:** `C:\claude\Plinth-yu3g1`
- **Основа:** `main` на `479462c` (Ю3в в `main`). **Коммиты тестов:** _(заполняется после тестов)_
- **Откуда:** `tasks.md`, строка 389 (Ю3); `docs/work/yu1.md`, раздел 6 (строка Ю3г); `docs/work/yu3v.md`,
  «Что не проверено» и комментарий в `online/OnlineProviders.kt` («выбор по сборке и по переключателю — Ю3г»).
  Ю3г разбита на три задачи с непересекающимися файлами — раздел «Дальше» внизу; эта — первая.
- **Данные пользователя трогает по краю.** Каталог и журнал не меняются, но в настройках появляется новый сохранённый
  ключ, а ID провайдеров (`archive.org`, `youtube`) — ключи данных пользователя. Меняется граница ядра (UniFFI).
  **До исполнителя план читает Opus** — раздел «На что смотреть при чтении плана (Opus)».

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
- Модуль тестов: строку `#[cfg(test)] mod yu3g_tests;` в `api/mod.rs` уже поставил коммит тестов (между `test_api` и
  `yu3v_tests` — порядок `rustfmt`). Вызовы `connect_online(` в `r1_11a_tests.rs`, `r1_11d_tests.rs`, `yu3v_tests.rs`
  правятся **механически**: добавить третий аргумент `true`; ни утверждения, ни имена тестов не трогать.

### В. Kotlin

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
  - `core/ffi/src/api/r1_11a_tests.rs`, `core/ffi/src/api/r1_11d_tests.rs`, `core/ffi/src/api/yu3v_tests.rs` — только третий аргумент `true` в вызовах `connect_online(`
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
  - `app/src/test/kotlin/io/github/puflik/plinth/online/OnlineProviderIdsTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/online/ActiveSourcesTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/online/OnlineSwitchProvidersTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/online/DataStoreOnlineSettingsTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/ui/settings/OnlineSourcesProvidersViewModelTest.kt`
  - `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/FakeOnlineSettings.kt`, `FakeOnlineRepository.kt`, `OnlineRepositoryContractTest.kt`
    (в двух фейках уже новый API; в контракте — три новых случая в конце)

## Тесты (написаны в коммитах тестов)

_(заполняется после тестов)_

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

_(заполняется)_

## Дальше: остальные задачи Ю3г

_(заполняется)_

## Отчёт исполнителя

_Заполняет исполнитель: что сделано, что не вышло и почему, коммиты, вывод проверок (коротко)._
