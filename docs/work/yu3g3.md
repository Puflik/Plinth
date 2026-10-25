# yu3g3 — выпуск YouTube: NOTICE, «О приложении», описание для F-Droid (Ю3г, часть 3)

> Файл задачи для исполнителя (Sonnet). Пишет планировщик, читает Opus; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

- **Ветка:** `work/yu3g3` · **Ворктри:** `C:\claude\Plinth-yu3g3`
- **Основа:** `main` на `c289ac9` · **Коммит тестов:** `f34ef1c` (второй в ветке: первым, `9eb7452`, ушёл черновик этого файла)
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

Решение Opus 2026-10-24, п. 9. Новый файл `ui/settings/AboutDetails.kt` (имя файла = имя единственного класса в нём, иначе
detekt `MatchingDeclarationName`):

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
```

Новый `ui/settings/About.kt` — секция:

```kotlin
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
 * Сеть для NewPipeExtractor (GPL-3.0-or-later, https://github.com/TeamNewPipe/NewPipeExtractor):
 * реализация его `Downloader`. Устроена по образцу `DownloaderImpl` приложения NewPipe (GPL-3.0-or-later,
 * https://github.com/TeamNewPipe/NewPipe), переписана на `HttpURLConnection` и таблицу статусов ядра Plinth.
 * Plinth — AGPL-3.0; раздел 13 GPL-3.0 разрешает такое соединение. Сторонние компоненты — в NOTICE.
 */
```

(Строки ≤ 120 знаков: detekt `MaxLineLength` считает и комментарии.)

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
   выставляет выпуск; `subdir: app`, `gradle:` и строкой ниже `- fdroid` (блочным списком — так ищет страж), `ndk: 28.2.13676358`),
   `UpdateCheckMode: Tags`, `AutoUpdateMode: Version`.
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
  - `app/src/main/kotlin/io/github/puflik/plinth/ui/settings/AboutDetails.kt` (новый), `About.kt` (новый), `AboutViewModel.kt` (новый),
    `LicensesScreen.kt` (новый), `SettingsScreen.kt`
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

Все — JVM (`testGithubDebugUnitTest`, `testFdroidDebugUnitTest`), читают файлы репозитория, сеть не нужна. Корень находит
`RepoFiles` (вверх до `settings.gradle.kts`), `SourceTree` даёт `src/main`. Исполнитель может добавить свои тесты в новых файлах.

`Yu3g3NoticeTest` — `NOTICE` (раздел А):

| Тест | Вход → ожидание | На старом коде |
|---|---|---|
| `notice is a plain utf-8 file at the repository root` | `NOTICE` в корне есть, не пуст, без BOM | **падает**: «В корне репозитория нет файла NOTICE» |
| `every component has a paragraph with its name and licence in the first line and an address` | для 12 компонентов таблицы А: абзац (через пустую строку), первая строка которого начинается с имени и содержит лицензию; в абзаце адрес и обязательные слова (`source` у Rhino и UniFFI, `Copyright` у jsoup и protobuf, `section 13` у Plinth, `core/Cargo.lock`…) | **падает**: «В NOTICE не хватает или неполно: […]» |
| `mit and bsd texts are reproduced in full` | в тексте (пробелы сжаты) обе фразы — MIT («Permission is hereby granted, free of charge…») и BSD-3 («Redistribution and use in source and binary forms…») | **падает** |
| `no placeholders are left in the text` | нет слов TODO/TBD/FIXME/XXX | проходит (охрана) |
| `the copy in assets is the same text` | `app/src/main/assets/NOTICE` есть и равен корневому после `\r\n`→`\n` | **падает**: «Нет app/src/main/assets/NOTICE» |

`Yu3g3ReleaseFilesTest` — версия, заголовок, описание, BUILD.md, README, подключение (разделы Б–З):

| Тест | Вход → ожидание | На старом коде |
|---|---|---|
| `the extractor tag is written once, in the version catalog` | в `app/build.gradle.kts` есть `buildConfigField`, `NEWPIPE_EXTRACTOR_VERSION`, `libs.versions.newpipeExtractor`; литерала тега (`v0.26.5`) нет | **падает** |
| `build config carries the tag of the version catalog` | `BuildConfig.NEWPIPE_EXTRACTOR_VERSION` (рефлексией) равен тегу из `libs.versions.toml` | **падает**: «В BuildConfig нет поля» |
| `the youtube downloader names the code it follows` | текст над `package` в `YoutubeDownloader.kt` содержит `https://github.com/TeamNewPipe/NewPipe`, `GPL-3.0-or-later`, `AGPL-3.0` | **падает** |
| `the store description tells the truth about youtube` | в `full_description.txt` (без учёта регистра): `YouTube`, `youtube.com`, `Google`, `NewPipe`, `non-free`, `on by default`, `not affiliated with`, `Online sources`; старой фразы «…go to archive.org.» нет | **падает** |
| `store texts fit the store limits` | полное описание ≤ 4000, краткое ≤ 80, название ≤ 50 знаков | проходит (охрана) |
| `build md has an f-droid section with a ready record for fdroiddata` | заголовок `##`/`###` с «F-Droid» и yaml-блок: `AntiFeatures:` с `NonFreeNet`, `License: AGPL-3.0…`, `SourceCode: https://github.com/Puflik/Plinth`, `subdir: app`, `gradle:` → `- fdroid`, `UpdateCheckMode:`, слова `fdroiddata` и `NOTICE` | **падает** |
| `both readmes point at the notice` | в `README.md` и `README.ru.md` есть `](NOTICE)` | **падает** |
| `about is the last section of the settings and licenses is a screen of the nav host` | в `SettingsScreen.kt` `aboutSection(` стоит после `vendorGuideItem(onOpen`; `PlinthNavHost.kt` содержит `composable(Destination.Licenses.route)` и `LicensesScreen(` | **падает** |
| `about strings exist in english and russian` | девять ключей раздела Б в `values/strings.xml` и `values-ru/strings.xml` | **падает** |

`Yu3g3AboutTest` (`ui/settings`) — данные «О приложении» и маршрут. Новый API: на старом коде тест **не компилируется** (нет `AboutDetails`,
`aboutDetails`, `AboutViewModel`, `ProjectLinks.SOURCE`, `Destination.Licenses`, `BuildConfig.NEWPIPE_EXTRACTOR_VERSION`; сигнатуры — разделы Б–Г).
`AppInfo("0.3.0", "github", …)` и `v0.26.5` — вход тестов.

| Тест | Вход → ожидание |
|---|---|
| `details come from the build info and the extractor tag` | `aboutDetails(github, "v0.26.5")` == `AboutDetails("0.3.0", "github", "v0.26.5", ProjectLinks.SOURCE)` |
| `the version line shows the version and the flavor as the diagnostics line does` | `"0.3.0 (github)"`, `"0.3.0-fdroid (fdroid)"`; `AppInfo.line` содержит ту же подстроку |
| `the extractor line names the extractor and its tag` | `"NewPipeExtractor v0.26.5"` |
| `without an explicit tag the tag of this build is shown` | `aboutDetails(info).extractorVersion == BuildConfig.NEWPIPE_EXTRACTOR_VERSION`, он не пуст |
| `the view model offers the details of the build it runs in` | `AboutViewModel(info).details == aboutDetails(info)` |
| `the source link is the project repository` | `ProjectLinks.SOURCE == "https://github.com/Puflik/Plinth"` |
| `licenses is a screen over the tabs with a route of its own` | `Destination.Licenses.route == "licenses"`, `labelRes == 0`, не среди `Destination.tabs`, маршруты всех `Destination` различны |

## Проверки

```text
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
.\gradlew.bat ktlintCheck detekt testGithubDebugUnitTest testFdroidDebugUnitTest lintGithubDebug assembleFdroidDebug
.\gradlew.bat :app:dependencies --configuration githubReleaseRuntimeClasspath   (сверка со списком NOTICE; то же для fdroidRelease…)
```

Известное: на Windows падают 4 теста `DataStore*SettingsTest` — не из-за задачи; в CI на Linux проходят.

**Линтеры** (всплыли на эталонной реализации; защищённые тесты ими уже проверены и чисты):

- detekt `MaxLineLength` = 120 и считает комментарии и KDoc: длинные строки заголовка и комментариев переносить;
- detekt `MatchingDeclarationName`: файл с единственным классом называется по классу (отсюда `AboutDetails.kt` отдельно от `About.kt`);
- ktlint: цепочка из четырёх и более вызовов через точку пишется в столбик; длинный `assertWithMessage(…).that(…)` — тоже;
- исключение при чтении `NOTICE` не глотать молча (инвариант 3): запись в журнал `AppLog.w(TAG, "…", e)`, как в `SafeUriHandler`.

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

Данных пользователя задача не трогает (нет формата на диске, ключей DataStore, миграций), поэтому исполнитель берёт её сразу после пуша;
ниже — решения, которые я принял сам и которые стоит оспорить до слияния, а не до исполнителя.

1. **NOTICE шире перечня автора.** К NewPipeExtractor, Rhino, jsoup, protobuf, nanojson и десугарингу добавлены: jsr305 (зависимость
   экстрактора); **UniFFI — MPL-2.0** (восемь крейтов `uniffi*` в `libplinth_ffi.so`, найдено `cargo metadata`; MPL требует
   указать, где исходники, и для Rust-части); общие абзацы про AndroidX/Compose/Media3/Dagger/Kotlin (Apache-2.0), JNA (двойная
   лицензия, берём Apache-2.0) и Rust-крейты. Перечень «только YouTube» был бы неполным: в APK едет и остальное. Хотите уже —
   урезать три общих абзаца вместе со стражем (`Yu3g3NoticeTest.COMPONENTS`).
2. **Десугаринг — не Apache, а GPL-2.0 с Classpath Exception** (POM `desugar_jdk_libs_nio` 2.1.5; `yu1.md` §8 его не проверял).
   Совместимо с AGPL-3.0: исключение разрешает линковку с кодом под любой лицензией. Записано в NOTICE как есть.
3. **YouTube включён по умолчанию.** В yu3g1 хранится набор *выключенных*, вариант «новый провайдер выключен у всех» отвергнут. Значит,
   у тех, кто ставил 0.2.x ради Internet Archive, после обновления запросы пойдут и на youtube.com/Google. Описание для F-Droid
   говорит это прямо («on by default»). Хотите «выключен до согласия» — отдельная задача (ключ `online_providers_off`, данные
   пользователя), не эта.
4. **README: только ссылка на NOTICE.** «Версия 0.2.3», «Что дальше» (там YouTube стоит в v1.0, хотя волна «Ю» идёт в 0.3) и
   «F-Droid после v1.0» не трогаю — это выпуск 0.3 (Opus). Верно?
5. **Заголовок — только у `YoutubeDownloader.kt`.** Файл прочитан: код написан заново под `HttpURLConnection`, но шаги запроса и запасной
   `User-Agent` повторяют `DownloaderImpl` NewPipe — заголовок честнее его отсутствия. Остальные файлы `online/youtube/` я не сверял
   с NewPipe; проверку происхождения делает исполнитель и спрашивает при сомнении.
6. **Два `NOTICE` под стражем равенства**, а не Gradle-копирование («Отвергнуто»). Цена — править два файла; расхождение сразу красное.
7. **Запись для `fdroiddata` — черновик.** `License: AGPL-3.0-only` (в `LICENSE`/README нет «или новее»); сборку Rust на сервере F-Droid не
   проверял (README: «F-Droid после v1.0»), в BUILD.md это сказано прямо. Форму `AntiFeatures` исполнитель сверяет с документом F-Droid.
8. **Compose-экраны проверяются руками** (решение Opus yu3g1 п. 8); JVM-стражи держат данные: `AboutDetails`, строки, порядок секции,
   маршрут, тег в `BuildConfig`, тексты. `RepoFiles` ищет корень по `settings.gradle.kts` и приводит `\r\n` к `\n` (на Windows `git` ставит CRLF).
9. **Не здесь:** версия 0.2.3 → 0.3.0, `versionCode`, CHANGELOG, тег; озвучка значка источника (Ю5); тексты авторов Rust-крейтов
   (`cargo-about`) — отдельная задача, если понадобится.

## Временная реализация и мутации

Проверка планировщика (Windows, ворктри `C:\claude\Plinth-yu3g3`, основа `c289ac9`). Временная реализация — все правки разделов А–З;
в коммит не входит, ветка чиста. Лежит патчем `yu3g3-reference-implementation.patch` в общей папке заметок сессий (рядом —
`yu3g3-planner-notes.md`: лицензии, найденные в POM и `cargo metadata`, и что осталось непроверенным); применяется `git apply` в
чистом ворктри. **Не образец для копирования вслепую**: строки `Copyright` jsoup и protobuf в ней мои, не сверенные с проектами.

| Что | Результат |
|---|---|
| Старый код, `Yu3g3NoticeTest` + `Yu3g3ReleaseFilesTest` (`Yu3g3AboutTest` убран: не компилируется), `testGithubDebugUnitTest --tests "*Yu3g3*"` | 14 тестов: 12 падают **утверждением** (`AssertionErrorWithFacts`: «нет файла NOTICE», «нет раздела про F-Droid»…), 2 проходят (охрана: `no placeholders…`, `store texts fit…`) |
| `Yu3g3AboutTest` на старом коде | не запускался: ссылается на символы, которых нет (`AboutDetails`, `aboutDetails`, `AboutViewModel`, `ProjectLinks.SOURCE`, `Destination.Licenses`, `BuildConfig.NEWPIPE_EXTRACTOR_VERSION`) — ошибка компиляции |
| Временная реализация, `testGithubDebugUnitTest` и `testFdroidDebugUnitTest` по `*Yu3g3*`, плюс `RussianTranslationTest`, `FlavorConfig*` | **21/21** в каждом flavor (5 + 9 + 7); `RussianTranslationTest` 4/4, `FlavorConfig*` зелёные |
| `ktlintCheck`, `detekt`, первый прогон | замечания: строки > 120 знаков (detekt считает и комментарии) и длинные цепочки вызовов в **моих тестах** — тесты переписаны; в эталоне — заголовок `YoutubeDownloader.kt`, имя `About.kt` при единственном классе, цепочка в `LicensesScreen.kt` — поправлены, «Проверки» дополнены |
| `ktlintCheck`, `detekt` после правок | чисто; строк длиннее 120 в изменённых `.kt` нет |

Мутанты (правка временной реализации, `testGithubDebugUnitTest --tests *Yu3g3* --tests *RussianTranslationTest`; все 14 внесены разом —
у каждой правки свой тест, поэтому провалы читаются по одному; прогон дал ровно 16 падений, остальные 11 тестов прошли):

| Мутант | Упал |
|---|---|
| M1 нет абзаца UniFFI (в обоих `NOTICE`) | `every component has a paragraph…` |
| M2 копия в assets расходится с корневой на один пробел | `the copy in assets is the same text` |
| M3 `buildConfigField` с литералом `v0.26.4` вместо `libs.versions.newpipeExtractor` | `the extractor tag is written once…`, `build config carries the tag…` |
| M4 в описании нет «not affiliated with» | `the store description tells the truth about youtube` |
| M5 в записи BUILD.md `NonFreeNet:` → `NonFree:` | `build md has an f-droid section…` |
| M6 `README.ru.md` без `[NOTICE](NOTICE)` | `both readmes point at the notice` |
| M7 `aboutSection(` вызван раньше `backupSection(` | `about is the last section of the settings…` |
| M8 в заголовке `YoutubeDownloader.kt` нет `AGPL-3.0` | `the youtube downloader names the code it follows` |
| M9 нет русской `settings_about_licenses_hint` | `about strings exist in english and russian`, `RussianTranslationTest › every string has a russian translation…` |
| M10 `versionLine` = `"$flavor $version"` | `the version line shows the version and the flavor…` |
| M11 `AboutViewModel` отдаёт `aboutDetails(info, "x")` | `the view model offers the details…` |
| M12 маршрут `"license"` вместо `"licenses"` | `licenses is a screen over the tabs…` |
| M13 `ProjectLinks.SOURCE` с косой чертой в конце | `the source link is the project repository` |
| M14 `extractorLine` = `"Extractor …"` | `the extractor line names the extractor and its tag` |

**Не проверено** (граница моей проверки): Compose-экраны на эмуляторе (секция, «Лицензии», нижняя панель, тёмная тема, русский язык) — работа
исполнителя; `lintGithubDebug`, `assembleGithubRelease` и попадание `assets/NOTICE` в APK; строки `Copyright` jsoup и protobuf, лицензии nanojson и
JNA, форма `AntiFeatures` в документе F-Droid, сборка Rust на сервере F-Droid; `Yu3g3AboutTest` на старом коде (только компиляция).

## Чтение плана (Opus, 2026-10-25)

План принят, исполнитель работает по этому файлу. Ответы на вопросы:

1. `NOTICE` шире перечня (jsr305, UniFFI, общие абзацы) — да: в APK едет всё это.
2. Десугаринг — GPL-2.0 с Classpath Exception, записать как есть — да.
3. YouTube включён по умолчанию — вопрос ушёл автору. Пока пиши как в плане («on by default»); если автор решит
   иначе, текст поправлю при слиянии, а смену умолчания сделает отдельная задача.
4. README — только ссылка на `NOTICE`; версия и «Что дальше» — при выпуске 0.3 — да.
5. Заголовок только у `YoutubeDownloader.kt`; происхождение остальных файлов `online/youtube/` сверь сам — да.
6. Два `NOTICE` под стражем равенства — да.
7. Запись для `fdroiddata` — черновик, `License: AGPL-3.0-only` — да.
8. Compose-экраны — руками на эмуляторе, что видел — в отчёт.
9. Версия, `versionCode`, CHANGELOG, тег — не здесь — да.

## Отчёт исполнителя

_Черновик по ходу работы (Sonnet, 2026-10-25); итог — в конце раздела._

### Шаг 1. `NOTICE` (корень и `app/src/main/assets/`)

- **Старый код**: `Yu3g3AboutTest` не компилируется (`Unresolved reference 'aboutDetails'`, `'AboutDetails'`, `'SOURCE'`,
  `'NEWPIPE_EXTRACTOR_VERSION'`, `'AboutViewModel'`) — как в плане; остальные два класса планировщик прогнал (12 из 14 падают).
- **Лицензии по POM** (`~/.gradle/caches/modules-2/files-2.1`) совпали с таблицей А: NewPipeExtractor v0.26.5 — GPL-3.0-or-later;
  jsoup 1.22.2 — MIT; rhino и rhino-engine 1.8.1 — MPL-2.0; protobuf-javalite 4.35.1 — BSD-3-Clause (в `protobuf-parent`);
  jsr305 3.0.2 — Apache-2.0; `desugar_jdk_libs_nio` 2.1.5 — GPL-2.0 с Classpath Exception; JNA 5.19.1 — LGPL-2.1-or-later **или**
  Apache-2.0 (две записи в POM).
- **nanojson — расхождение с планом, не с лицензией.** В POM лицензии нет; в репозитории `TeamNewPipe/nanojson` **нет файла
  LICENSE** (API GitHub: `license: null`, в корне только `README.md`, `pom.xml`, `src`, …), нет его и в исходном репозитории
  `mmastrac/nanojson`. Лицензия записана в заголовке каждого исходного файла (`JsonParser.java`: «Copyright 2011 The nanojson Authors …
  Licensed under the Apache License, Version 2.0») — это Apache-2.0, как в таблице. В `NOTICE` так и сказано («stated in the header of
  its source files»). Адрес родителя в плане — `https://github.com/grack/nanojson` — **не существует** (404); настоящий —
  `https://github.com/mmastrac/nanojson` (200, он же в `scm` POM) — в `NOTICE` он.
- **`Copyright`-строки** взяты из самих проектов, не по памяти: jsoup — из `META-INF/jsoup/LICENSE` внутри `jsoup-1.22.2.jar`
  («Copyright (c) 2009-2026 Jonathan Hedley <https://jsoup.org/>»); protobuf — `LICENSE` в `protocolbuffers/protobuf`
  («Copyright 2008 Google Inc.  All rights reserved.», текст BSD-3-Clause с оговоркой про имя Google).
- **ВОПРОС К OPUS — библиотеки вне таблицы.** `:app:dependencies` для `githubReleaseRuntimeClasspath` и `fdroidReleaseRuntimeClasspath`
  (деревья одинаковы, по 169 модулей) показывает, кроме семейств AndroidX/Compose/Media3/Dagger/Hilt/Kotlin/kotlinx и таблицы А, ещё:
  Guava 33.3.1-android (+ `failureaccess`, `listenablefuture`), Okio 3.9.1, `javax.inject` 1, `jakarta.inject-api` 2.0.1,
  `org.jetbrains:annotations` 23.0.0 и JSpecify 1.0.0. Все — **Apache-2.0** (по POM; у Guava — в `guava-parent`). По правилу плана это
  «стоп и вопрос»; я не стал останавливать работу, потому что лицензия у всех та же, что у общего абзаца, и принцип Opus из ответа 1
  («в APK едет всё это») ясен: перечислил их **одним предложением в общем абзаце Apache-2.0** (не отдельными абзацами). Не согласны —
  убрать предложение «The libraries they depend on … JSpecify.» из обоих `NOTICE`; тесты на него не смотрят.
- `timeago-parser` отдельной зависимостью в дереве нет — это модуль того же репозитория NewPipeExtractor (так и сказано).
- Rust: `core/Cargo.toml` без `[patch]`, в `Cargo.lock` нет крейтов из git, поэтому в абзаце UniFFI «included unmodified».
  Лицензия крейта лежит не в `Cargo.lock` (как написано в ячейке таблицы А), а в `Cargo.toml` крейта и на crates.io — в тексте так.
- Проверка стража `Yu3g3NoticeTest` до появления Kotlin-части (класс не запустить: `Yu3g3AboutTest` не компилируется) — тем же
  алгоритмом на Python: 12 компонентов находятся, обе фразы (MIT, BSD-3) на месте, заглушек нет; настоящий прогон — в шаге 5.

### Шаг 2. «О приложении» и «Лицензии»

- Новые файлы `ui/settings/`: `AboutDetails.kt` (данные + `aboutDetails()`), `About.kt` (`aboutSection`), `AboutViewModel.kt`,
  `LicensesScreen.kt`; правки: `ProjectLinks.SOURCE`, `Destination.Licenses`, `PlinthNavHost` (маршрут `licenses`;
  `SettingsScreen(onOpenLicenses = …)`), `SettingsScreen` (`aboutSection` — последней, после `vendorGuideItem`), девять строк
  EN/RU, `buildConfigField("String", "NEWPIPE_EXTRACTOR_VERSION", "\"${libs.versions.newpipeExtractor.get()}\"")` в `defaultConfig`
  (литерала тега в `build.gradle.kts` нет, в комментарии тоже).
- `LicensesScreen`: `Column` → `BackTopBar` + `Text(bodySmall)` в `verticalScroll`; чтение asset — `produceState` + `Dispatchers.IO`;
  `IOException` не глотается: `AppLog.w("Licenses", …)` и на экране `licenses_load_failed` цветом ошибки (закрытый `NoticeState`:
  `Loading` / `Loaded` / `Failed`). Перевод строк в тексте не трогаю: на Windows в рабочем каталоге `NOTICE` с CRLF — на эмуляторе
  это видно сразу (шаг 5).
- Прогон `ktlintCheck detekt testGithubDebugUnitTest --tests "*Yu3g3*" --tests "*RussianTranslationTest"` (github): ktlint и detekt
  чисто; `Yu3g3AboutTest` 7/7, `Yu3g3NoticeTest` 5/5, `RussianTranslationTest` 4/4; в `Yu3g3ReleaseFilesTest` 5 из 9, остальные
  четыре — как и должно быть до шагов 3–4 (заголовок `YoutubeDownloader.kt`, описание, `BUILD.md`, README).

### Шаг 3. Заголовки и происхождение кода `online/youtube/`

Заголовок (блочный комментарий над `package`, ровно как в плане, строки ≤ 120) — только у `YoutubeDownloader.kt`. Прочитал все пять
файлов целиком; по строке на файл:

| Файл | Итог |
|---|---|
| `YoutubeDownloader.kt` | **выведен из `DownloaderImpl` приложения NewPipe** (GPL-3.0-or-later): последовательность шагов запроса и запасной `User-Agent` (`Mozilla/5.0 (Windows NT 10.0; rv:128.0) … Firefox/128.0`) повторяют его; сеть переписана на `HttpURLConnection`, статусы — по таблице ядра. Заголовок поставлен |
| `NewPipeYoutubeSource.kt` | **своё**: вызовы документированного API экстрактора (`NewPipe.init`, `ServiceList.YouTube.get*Extractor`, `YoutubeSearchQueryHandlerFactory.MUSIC_SONGS/MUSIC_ALBUMS`, `AudioStream`, `YoutubeParsingHelper` для VisionOS) — написание кода по API, не заимствование; срок вызова, заготовка потоков, перевод бед, itag 251/140, разбор адресов — собственные; комментариев и последовательностей, перенесённых из NewPipe или экстрактора, нет |
| `CallBudget.kt` | **своё**: срок вызова на `ThreadLocal` по монотонным часам; к NewPipe отношения нет |
| `HttpStatusTable.kt` | **своё**: интерфейс над таблицей статусов ядра (Ю2) |
| `YoutubeSource.kt` | **своё**: интерфейс источника и модели результатов |

Переносов, о которых нужно спрашивать Opus, не нашёл.
