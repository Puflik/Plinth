# yu3g3 — выпуск YouTube: NOTICE, «О приложении», описание для F-Droid (Ю3г, часть 3)

> Файл задачи для исполнителя (Sonnet). Пишет планировщик, читает Opus; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

- **Ветка:** `work/yu3g3` · **Ворктри:** `C:\claude\Plinth-yu3g3`
- **Основа:** `main` на `c289ac9` · **Коммит тестов:** `<хеш>` (второй в ветке: первым ушёл черновик этого файла)
- **Откуда:** `docs/work/yu3g1.md`, «Дальше: остальные задачи Ю3г», пункт yu3g3, и «Чтение плана (Opus, 2026-10-24)», п. 9

## Цель

Выпуск 0.3 с YouTube честен перед теми, кто его ставит и кто на него смотрит: в настройках есть секция «О приложении» (версия и
flavor, версия экстрактора YouTube, ссылка на исходники, «Лицензии») и экран «Лицензии» с текстом `NOTICE`; в корне
репозитория и в APK лежит один и тот же `NOTICE` со сторонними компонентами; описание для F-Droid и `docs/BUILD.md` прямо
говорят про YouTube и метку `NonFreeNet`; код, выведенный из кода NewPipe, помечен.

## Контекст

Читать только это.

- `docs/work/yu3g1.md`, строки 328–378: набросок yu3g3 и решения Opus (п. 9 — секция, а не экран; `.fdroid.yml` не нужен).
- `docs/work/yu3a.md`, §5 п. 1 и п. 9; `docs/work/yu1.md`, раздел 8 (лицензии; список там неполон — полный в таблице ниже).
- Образцы кода: `ui/settings/SettingsScreen.kt` (целиком), `ui/settings/Diagnostics.kt` (`diagnosticsSection` — образец секции),
  `ui/settings/DiagnosticsViewModel.kt` (ViewModel с `AppInfo`), `ui/common/BackTopBar.kt` и `ui/library/album/AlbumScreen.kt`
  (экран поверх вкладок с `BackTopBar`), `ui/navigation/Destination.kt` и `PlinthNavHost.kt`, `MainActivity.kt` (нижняя
  панель скрыта на экранах не из `Destination.tabs`), `core/config/ProjectLinks.kt`, `diagnostics/AppInfo.kt`.
- Тесты-образцы: `app/src/test/kotlin/io/github/puflik/plinth/SourceTree.kt`, `R8RulesGuardTest.kt`, `RussianTranslationTest.kt`
  (новые строки обязаны иметь русский перевод, не равный английскому, с теми же `%1$s`).
- `fastlane/metadata/android/en-US/*`, `docs/BUILD.md` (оглавление `grep -n "^#"`), `README.md`/`README.ru.md` (раздел «Licence»/«Лицензия»).
- Внешнее: F-Droid, Build Metadata Reference — `https://f-droid.org/docs/Build_Metadata_Reference/` (поля `AntiFeatures`, `Builds`,
  `UpdateCheckMode`, `gradle`, `subdir`, `ndk`, `prebuild`).

Проверено планировщиком (не пересматривать, но сверить с POM в п. А):

- Зависимости экстрактора `v0.26.5` по его POM (`~/.gradle/caches/modules-2/files-2.1/com.github.TeamNewPipe/NewPipeExtractor/v0.26.5/…/*.pom`):
  nanojson (форк NewPipe), jsoup 1.22.2, jsr305 3.0.2, protobuf-javalite 4.35.1, rhino 1.8.1, rhino-engine 1.8.1, все `runtime`.
- Лицензии по POM: NewPipeExtractor — GPL-3.0-or-later; jsoup — MIT; Rhino — MPL-2.0; protobuf — BSD-3-Clause;
  jsr305 — Apache-2.0; **`desugar_jdk_libs_nio` 2.1.5 — GPL-2.0 с Classpath Exception** (не Apache, как можно было ожидать);
  у nanojson в POM лицензии нет — по yu3a Apache-2.0 (форк `grack/nanojson`), проверить по LICENSE репозитория.
- В Rust-ядре (`cargo metadata`, цель `aarch64-linux-android`, 121 крейт вне нашего): MIT или Apache-2.0 почти все; **восемь крейтов
  `uniffi*` — MPL-2.0** (их рантайм линкуется в `libplinth_ffi.so`); ещё Zlib, Unlicense, 0BSD, `(MIT OR Apache-2.0) AND Unicode-3.0`.

## Решение

### А. `NOTICE` — один текст в двух местах

Два файла, **идентичные** (страж сравнивает после приведения `\r\n` к `\n`): `NOTICE` в корне и `app/src/main/assets/NOTICE`
(экран «Лицензии» читает asset). Gradle-копирование из корня в assets отвергнуто (см. «Отвергнуто»).

Формат: английский, UTF-8, `\n`, без BOM. Абзацы разделены пустой строкой. **Строки внутри абзаца по ширине не переносятся**: экран
показывает файл как есть, и жёсткий перенос на телефоне рвёт строки. Первая строка абзаца — заголовок
`<Имя> — <лицензия>` (длинное тире, лицензия — SPDX-идентификатор); дальше адрес и одно-два предложения «что это и зачем». Версий
зависимостей в тексте нет (они уплывают при каждом обновлении экстрактора; правило обновления — в `docs/BUILD.md`, п. Ж).

Абзацы, по порядку (имя в начале первой строки, лицензия в ней же, адрес в абзаце; страж проверяет именно это):

| Заголовок (первая строка) | Адрес в абзаце | Что ещё обязательно в абзаце |
|---|---|---|
| `Plinth — AGPL-3.0` | `https://github.com/Puflik/Plinth` | текст лицензии — файл `LICENSE`; Plinth включает NewPipeExtractor (`GPL-3.0-or-later`); раздел 13 GPL-3.0 (`section 13`) разрешает соединять такой код с AGPL-3.0, целое распространяется под AGPL-3.0 — **одним предложением** |
| `NewPipeExtractor — GPL-3.0-or-later` | `https://github.com/TeamNewPipe/NewPipeExtractor` | поиск и потоки YouTube; в нём же модуль timeago-parser; текст GPL-3.0 — `https://www.gnu.org/licenses/gpl-3.0.txt` |
| `nanojson — Apache-2.0` | `https://github.com/TeamNewPipe/nanojson` | форк `https://github.com/grack/nanojson` |
| `jsoup — MIT` | `https://jsoup.org` | строка `Copyright` из LICENSE jsoup |
| `Rhino (rhino, rhino-engine) — MPL-2.0` | `https://github.com/mozilla/rhino` | **где исходный код** (слово `source`): библиотека не изменена, версию определяет выпуск NewPipeExtractor; текст MPL-2.0 — `https://www.mozilla.org/MPL/2.0/` |
| `protobuf-javalite — BSD-3-Clause` | `https://github.com/protocolbuffers/protobuf` | строка `Copyright` из LICENSE протобуфа |
| `jsr305 — Apache-2.0` | любой `http…` из POM | аннотации; может не попасть в APK после R8 — пишем всё равно |
| `desugar_jdk_libs_nio — GPL-2.0-only WITH Classpath-exception-2.0` | `https://github.com/google/desugar_jdk_libs` | зачем: `java.nio`/`java.util` API 33 на Android 8–12 (Ю3б) |
| `UniFFI — MPL-2.0` | `https://github.com/mozilla/uniffi-rs` | **где исходный код** (`source`): крейты `uniffi*` в `libplinth_ffi.so`, версия — в `core/Cargo.lock` |
| `AndroidX, Jetpack Compose, Media3, Dagger and Hilt, Kotlin and kotlinx.coroutines — Apache-2.0` | — | `https://www.apache.org/licenses/LICENSE-2.0` |
| `JNA — LGPL-2.1-or-later OR Apache-2.0` | `https://github.com/java-native-access/jna` | «used under Apache-2.0» |
| `Rust crates of the core — MIT, Apache-2.0, MPL-2.0, Zlib, Unlicense, 0BSD, Unicode-3.0` | — | список и версии — `core/Cargo.lock`, лицензия каждого крейта — в его метаданных (`core/Cargo.lock`) |

После абзацев компонентов — два абзаца с текстами лицензий целиком, потому что MIT и BSD-3-Clause требуют воспроизвести
уведомление и в двоичной раздаче: `MIT License` (стандартный текст: «Permission is hereby granted, free of charge, to any person
obtaining a copy…») и `BSD 3-Clause License` («Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met…»), каждый — с `Copyright`-строкой своего проекта
(jsoup и protobuf; строки взять из LICENSE проекта, не выдумывать). Длинные тексты (GPL-3.0, GPL-2.0 с Classpath Exception, MPL-2.0,
Apache-2.0) — по адресу.

Каждую строку таблицы исполнитель сверяет: лицензия компонента — по блоку `<licenses>` его POM в кэше Gradle
(`~/.gradle/caches/modules-2/files-2.1/<группа>/<артефакт>/<версия>/*/*.pom`), для nanojson и JNA — по LICENSE/README репозитория. Затем
сверяет **список** с настоящим: `.\gradlew.bat :app:dependencies --configuration githubReleaseRuntimeClasspath` (и `fdroidRelease…`) —
всякая библиотека, кроме семейств AndroidX/Compose/Media3/Dagger/Hilt/Kotlin/kotlinx и перечисленных в таблице, — стоп и вопрос.

### Б. «О приложении» — секция в конце настроек

Решение Opus 2026-10-24, п. 9. Новый файл `ui/settings/About.kt`:

```kotlin
/** Что показывает «О приложении»: значения готовы к показу, Android-типов нет. */
data class AboutDetails(
    val version: String,          // AppInfo.version, например "0.3.0" или "0.3.0-fdroid"
    val flavor: String,           // AppInfo.flavor: "github" | "fdroid"
    val extractorVersion: String, // тег экстрактора из libs.versions.toml: "v0.26.5"
    val sourceUrl: String,        // ProjectLinks.SOURCE
) {
    val versionLine: String get() = "$version ($flavor)"                    // "0.3.0 (github)", как в AppInfo.line
    val extractorLine: String get() = "NewPipeExtractor $extractorVersion"  // имя и версия не переводятся
}

fun aboutDetails(info: AppInfo, extractorVersion: String = BuildConfig.NEWPIPE_EXTRACTOR_VERSION): AboutDetails

internal fun LazyListScope.aboutSection(details: AboutDetails, onOpenSource: () -> Unit, onOpenLicenses: () -> Unit)
```

Новый `ui/settings/AboutViewModel.kt`: `@HiltViewModel class AboutViewModel @Inject constructor(info: AppInfo) : ViewModel()` с
`val details: AboutDetails = aboutDetails(info)`.

`aboutSection` — по образцу `diagnosticsSection`: заголовок `titleSmall` (ключ `"about-title"`) и четыре `ListItem` (ключи `about-version`,
`about-extractor`, `about-source`, `about-licenses`):

| Строка | headline | supporting | касание |
|---|---|---|---|
| версия | `settings_about_version` | `details.versionLine` | — |
| экстрактор | `settings_about_extractor` | `details.extractorLine` | — |
| исходники | `settings_about_source` | `settings_about_source_hint` | `onOpenSource` |
| лицензии | `settings_about_licenses` | `settings_about_licenses_hint` | `onOpenLicenses` |

`SettingsScreen`: новая сигнатура `SettingsScreen(onOpenLicenses: () -> Unit, modifier: Modifier = Modifier, …прежние…, about: AboutViewModel = hiltViewModel())`;
`aboutSection(about.details, onOpenSource = { uriHandler.openUri(about.details.sourceUrl) }, onOpenLicenses = onOpenLicenses)` —
**последним** в `LazyColumn`, после `vendorGuideItem` (страж проверяет порядок по тексту). Ссылка идёт через уже имеющийся
`rememberSafeUriHandler()`: телефон без браузера получает короткое сообщение, а не сбой.

`ProjectLinks.SOURCE = "https://github.com/Puflik/Plinth"` (константа рядом с `RELEASES` и `NEW_ISSUE`).

Строки (EN → RU; `RussianTranslationTest` требует перевод, не равный английскому, и те же плейсхолдеры, поэтому версии и
имена собираются в Kotlin, а не лежат в ресурсах):

| Ключ | EN | RU |
|---|---|---|
| `settings_about_title` | About | О приложении |
| `settings_about_version` | Version | Версия |
| `settings_about_extractor` | YouTube extractor | Экстрактор YouTube |
| `settings_about_source` | Source code | Исходный код |
| `settings_about_source_hint` | Plinth is free software under the AGPL-3.0 licence | Plinth — свободная программа под лицензией AGPL-3.0 |
| `settings_about_licenses` | Licenses | Лицензии |
| `settings_about_licenses_hint` | Third-party components and their licences | Сторонние компоненты и их лицензии |
| `licenses_title` | Licenses | Лицензии |
| `licenses_load_failed` | Could not read the licence text. | Не удалось прочитать текст лицензий. |

(«licence»/«license» — как принято в соседних строках файла; тест на это не смотрит.)

### В. Экран «Лицензии»

`Destination.Licenses(route = "licenses")` — без вкладки и значка, как `Player`. В `PlinthNavHost`:
`composable(Destination.Licenses.route) { LicensesScreen(onBack = back) }`, а `SettingsScreen(onOpenLicenses = { navController.navigate(Destination.Licenses.route) })`.
Нижняя панель на таком маршруте скрыта сама (`MainActivity`: не вкладка).

Новый `ui/settings/LicensesScreen.kt`: `LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier)` — `Column`: `BackTopBar(title = stringResource(R.string.licenses_title), onBack)`,
под ним прокручиваемый (`verticalScroll`) `Text(bodySmall)` с содержимым asset `NOTICE` (UTF-8). Чтение — не на главном потоке
(`produceState` + `Dispatchers.IO`). **Не прочиталось — на экране `licenses_load_failed` цветом ошибки**, а не пустота.

### Г. Версия экстрактора — один источник

`gradle/libs.versions.toml` (`newpipeExtractor = "v0.26.5"`) не меняется. В `app/build.gradle.kts`, `defaultConfig`:
`buildConfigField("String", "NEWPIPE_EXTRACTOR_VERSION", "\"${libs.versions.newpipeExtractor.get()}\"")`. Тега-литерала в
`build.gradle.kts` нет (страж ищет). Значение в BuildConfig — ровно тег каталога, с `v`; показывается как есть.

### Д. Заголовки в исходниках

Решение планировщика: **заголовок — только у `online/youtube/YoutubeDownloader.kt`**. Он реализует `Downloader` экстрактора и устроен
по образцу `DownloaderImpl` приложения NewPipe (структура запроса, заголовок `User-Agent`, `Response`), хотя переписан на
`HttpURLConnection` и таблицу статусов ядра. Блочный комментарий **над `package`**, по-русски, с лицензионными ссылками:

```kotlin
/*
 * Сеть для NewPipeExtractor (GPL-3.0-or-later, https://github.com/TeamNewPipe/NewPipeExtractor): реализация его `Downloader`.
 * Устроена по образцу `DownloaderImpl` приложения NewPipe (GPL-3.0-or-later, https://github.com/TeamNewPipe/NewPipe),
 * переписана на `HttpURLConnection` и таблицу статусов ядра Plinth. Plinth — AGPL-3.0; раздел 13 GPL-3.0 разрешает такое
 * соединение. Сторонние компоненты — в NOTICE.
 */
```

Остальные файлы `online/youtube/` (`CallBudget`, `HttpStatusTable`, `NewPipeYoutubeSource`, `YoutubeSource`) исполнитель читает на
происхождение: код, написанный по API экстрактора (вызовы `ServiceList.YouTube`, `StreamInfo`…), заимствованием не считается.
Фрагмент, **перенесённый** из NewPipe или экстрактора (общие идентификаторы, комментарии, последовательность шагов), — стоп и
вопрос Opus; заголовков «на всякий случай» не ставить. Итог проверки — по строке на файл в отчёт.

### Е. `fastlane/metadata/android/en-US/full_description.txt`

Текст остаётся английским, ≤ 4000 знаков, HTML-теги как были. Правки:

1. В «What it does now» — пункт про YouTube: поиск и игра через NewPipe-экстрактор, без аккаунта Google и без библиотек Google;
   сказать, что YouTube меняется без предупреждения и источник может ненадолго перестать работать.
2. В «Privacy» вместо «While online sources are on, searches and streams go to archive.org.» — что запросы идут на `archive.org` и,
   если включён YouTube, на `youtube.com` и серверы Google, которым виден IP-адрес; **оба источника включены по умолчанию**,
   отключаются в «Online sources» настроек. Старое предложение остаётся только в новой редакции (страж ищет его старую форму).
3. Абзац перед предложением про ИИ: YouTube — несвободный сетевой сервис (`non-free`), F-Droid помечает приложение `NonFreeNet`;
   **«Plinth is not affiliated with or endorsed by YouTube or Google.»**
4. `short_description.txt` и `title.txt` не менять (≤ 80 и ≤ 50 знаков — страж держит).

### Ж. `docs/BUILD.md` — раздел «Выпуск и F-Droid»

Новый раздел `## Выпуск и F-Droid` (русский, как остальной файл; перед «Размер APK»):

1. Где живёт метка: `AntiFeatures: NonFreeNet` — в метаданных `fdroiddata`, не в этом репозитории; `.fdroid.yml` не нужен
   (решение Opus 2026-10-24, п. 9). YouTube входит в обе сборки (решение автора 2026-10-19).
2. Готовая запись для `fdroiddata` — блок ```` ```yaml ````: `Categories`, `License: AGPL-3.0-only` (в `LICENSE`/README нет «или новее»),
   `SourceCode`, `IssueTracker`, `Changelog`, `AntiFeatures` с `NonFreeNet` (с английским пояснением: YouTube — несвободный сетевой
   сервис, отключается в «Online sources»), `Builds` (`versionName`/`versionCode`/`commit` — **заполнители в угловых скобках**: их
   выставляет выпуск; `subdir: app`, `gradle: [fdroid]`, `ndk: 28.2.13676358`), `UpdateCheckMode: Tags`, `AutoUpdateMode: Version`.
   Поля и их форма — по Build Metadata Reference; расхождение с этим черновиком — запись по документу, расхождение — в отчёт.
3. **Не проверено** и так и написано: сборка Rust-ядра на сервере F-Droid (rustup, `cargo-ndk`, `rust-version = "1.98"`) — нужен
   локальный `fdroid build` при подаче; JitPack как источник зависимости экстрактора — у NewPipe он принят.
4. «Обновление экстрактора»: сменили тег `newpipeExtractor` → `:app:dependencies` для `githubReleaseRuntimeClasspath`, сверить с
   `NOTICE` (новые библиотеки, сменившиеся лицензии), править **оба** файла `NOTICE`; перечитать `R8OutputGuardTest.KEPT_BY_NAME`.

### З. README

`README.md` и `README.ru.md`: в разделе «Licence»/«Лицензия» одна строка со ссылкой `[NOTICE](NOTICE)` — сторонние компоненты и
их лицензии. Версию, «Что дальше», «F-Droid после v1.0» не трогать — их ведёт выпуск 0.3 (Opus).

### Отвергнуто

- **Копировать `NOTICE` из корня в assets задачей Gradle**: AGP 9 просит зарегистрированную задачу и `addGeneratedSourceDirectory` —
  новое место для ошибок ради файла в 10 КБ; два файла под стражем равенства проще и громко падают при расхождении.
- **Плагин отчёта лицензий** (OSS Licenses и подобные): новая зависимость сборки, у плагина Google закрытый код; список короткий и
  ведётся руками по POM.
- **Отдельный экран «О приложении»**: решение Opus — секция в настройках; на экран выносится только длинный текст («Лицензии»).
- **Версии зависимостей в `NOTICE`**: устаревают при каждом обновлении экстрактора; для MPL достаточно адреса и пометки,
  что версию задаёт выпуск экстрактора.
- **Полные тексты GPL/MPL/Apache в APK**: ~100 КБ ради лицензий, чьи тексты общедоступны; MIT/BSD (короткие, требуют воспроизведения) — целиком.
- **`.fdroid.yml` в корне**, **флаг сборки «без YouTube» для `fdroid`**: решения Opus и автора.

## Инварианты

1. `NOTICE` в корне и в assets — один текст; экран «Лицензии» показывает asset как есть, без правок.
2. Версия экстрактора записана один раз — в `libs.versions.toml`; приложение показывает значение из `BuildConfig`, литерала тега в
   исходниках нет.
3. Ошибка чтения `NOTICE` видна человеку (`licenses_load_failed`), экран не падает и не пуст.
4. Ссылка на исходники открывается через `rememberSafeUriHandler()`: нет браузера — сообщение, не сбой.
5. Никаких новых зависимостей, разрешений, ключей DataStore и сетевых вызовов; поведение настроек и онлайн-источников прежнее.
6. Все новые строки есть на английском и русском (`RussianTranslationTest` зелёный).
7. В `fastlane` и `NOTICE` нет утверждений, которых нет в приложении: оба источника включены по умолчанию — так и сказано.

## Файлы

- **Менять:**
  - `NOTICE` (новый), `app/src/main/assets/NOTICE` (новый)
  - `app/build.gradle.kts` (только `buildConfigField` в `defaultConfig`)
  - `app/src/main/kotlin/io/github/puflik/plinth/core/config/ProjectLinks.kt` (`SOURCE`)
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/settings/About.kt` (новый), `AboutViewModel.kt` (новый), `LicensesScreen.kt` (новый), `SettingsScreen.kt`
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/navigation/Destination.kt`, `PlinthNavHost.kt`
  - `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml`
  - `app/src/main/kotlin/io/github/puflik/plinth/online/youtube/YoutubeDownloader.kt` (только заголовок над `package`)
  - `fastlane/metadata/android/en-US/full_description.txt`
  - `docs/BUILD.md`, `README.md`, `README.ru.md`
  - `docs/work/yu3g3.md` (только «Отчёт исполнителя»)
- **Не трогать:** `gradle/libs.versions.toml`, `settings.gradle.kts`, `app/proguard-rules.pro`, `.github/`, остальные файлы
  `online/youtube/`, `CHANGELOG.md`, `tasks*.md`, `docs/decisions.md`, `CLAUDE.md`, `plan.md`, `docs/plan/`, существующие
  тесты (в том числе `RussianTranslationTest` — список `SAME_IN_BOTH` не расширять), версию приложения (`versionName`/`versionCode`).
- **Защищённые тесты** (из коммита тестов; не менять, не удалять, не глушить):
  - `app/src/test/kotlin/io/github/puflik/plinth/RepoFiles.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/Yu3g3NoticeTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/Yu3g3ReleaseFilesTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/ui/settings/Yu3g3AboutTest.kt`

## Тесты (написаны в коммите тестов)

_Заполняется после написания тестов._

## Проверки

```text
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
.\gradlew.bat ktlintCheck detekt testGithubDebugUnitTest testFdroidDebugUnitTest lintGithubDebug assembleFdroidDebug
.\gradlew.bat :app:dependencies --configuration githubReleaseRuntimeClasspath   (сверка со списком NOTICE; то же для fdroidRelease…)
```

Известное: на Windows падают 4 теста `DataStore*SettingsTest` — не из-за задачи; в CI на Linux проходят.

**Руками на эмуляторе** (`Medium_Phone_API_36.0`, `$env:ANDROID_SERIAL = "emulator-5554"`; память проекта «Ручная проверка экрана на
эмуляторе без окна»: `uiautomator dump` + `input tap`) — что увидел, в отчёт:

1. Настройки → прокрутка до конца: секция «About» последняя; версия и flavor (`0.2.3-debug (github)` на отладочной сборке), экстрактор
   `NewPipeExtractor v0.26.5`, строки «Source code» и «Licenses».
2. «Source code» — открывается браузер с `https://github.com/Puflik/Plinth` (или сообщение, если открыть нечем).
3. «Licenses» — экран с заголовком и текстом `NOTICE`, нижней панели нет, текст прокручивается до конца, «назад» возвращает в настройки.
4. То же на русском (язык приложения) и в тёмной теме — текст читается.

## Готово, когда

- `Yu3g3NoticeTest`, `Yu3g3ReleaseFilesTest`, `Yu3g3AboutTest` зелёные в обоих flavor; весь набор без новых падений (кроме 4 известных на Windows).
- `ktlintCheck`, `detekt`, `lintGithubDebug` чисто; `assembleFdroidDebug` собирается.
- Список из `:app:dependencies` сверен с `NOTICE`; лишних и пропущенных компонентов нет (или расхождение описано в отчёте и спрошено).
- Четыре пункта ручной проверки на эмуляторе пройдены, результат в отчёте.
- Для каждого файла `online/youtube/` в отчёте строка «своё»/«выведен из …».
- Критерия скорости нет: задача не меняет пути воспроизведения.

## Остановиться и написать, если

- защищённый тест кажется неверным;
- для решения нужно менять файл вне списка «Менять»;
- проверки упали дважды подряд (одна попытка исправить, потом стоп);
- лицензия компонента в POM или в репозитории не совпала с таблицей раздела А (в том числе nanojson и JNA);
- `:app:dependencies` показывает библиотеку, которой нет в таблице и нет в семействах AndroidX/Compose/Media3/Dagger/Hilt/Kotlin;
- в `online/youtube/` (кроме `YoutubeDownloader.kt`) найден фрагмент, похожий на перенесённый из NewPipe;
- экран «Лицензии» не читает asset или не открывается на эмуляторе и одна попытка исправления не помогла.

## На что смотреть при чтении плана (Opus)

_Заполняется после написания тестов._

## Временная реализация и мутации

_Заполняется после проверки._

## Отчёт исполнителя

_Заполняет исполнитель: что сделано, что не вышло и почему, коммиты, вывод проверок (коротко)._
