# yu3g2 — R8 и release-сборка YouTube на эмуляторах (Ю3г, часть 2)

> Файл задачи для исполнителя (Sonnet). Пишет планировщик; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

- **Ветка:** `work/yu3g2` · **Ворктри:** `C:\claude\Plinth-yu3g2`
- **Основа:** `main` на `c6f5939` · **Коммит тестов:** `b85368a` (первая редакция тестов и файла — `dcd9b98`; `R8OutputGuardTest` поправлен после прогона на настоящей сборке)
- **Откуда:** набросок `docs/work/yu3g1.md`, «Дальше: остальные задачи Ю3г», пункт yu3g2; `docs/work/yu3a.md` §5 п. 1, 4, 7 и §7;
  `docs/work/yu3v.md` п. К «R8» и «Что не проверено» («релиз до Ю3г не выпускать»).

## Цель

Release-сборка YouTube работает. `app/proguard-rules.pro` держит то, до чего экстрактор и Rhino доходят по имени, и это закреплено двумя стражами
(текст файла правил и вывод R8); CI собирает release обеих flavor в бюджете размера и проверяет вывод R8; release-APK на эмуляторах API 36, 30 и 26
находит музыку в YouTube, играет минуту и перематывает. Итог по трём API — таблицей в отчёте, ручной сценарий — `docs/testing/youtube-live.md`.
После этой задачи и `yu3g3` релиз 0.3 можно выпускать.

## Контекст

Читать диапазонами; большие файлы целиком не читать.

- `app/proguard-rules.pro` — целиком (≈ 40 строк). Блок «YouTube (Ю3в)» — только `-dontwarn java.beans.*`; его комментарий обещает «рабочие `-keep`-правила — Ю3г».
- `git show spike/yu3a:app/proguard-rules.pro` — проверенный набор (последний блок, «Спайк Ю3а»); он повторяет правила приложения NewPipe.
- `docs/work/yu3a.md`: §5 п. 1, 4, 7 (строки 155–176) — риски; §7 (190–196) — что не проверено; §8 (198–212) — телефон.
- `docs/work/yu3v.md`: п. К «R8» (243–247), «Что не проверено» (507–514).
- `.github/workflows/ci.yml`: шаг «Release APK within the size budget» (103–114) и «Upload reports» (122–131).
- `docs/testing/apk-size.md` — целиком; `docs/testing/v0.2-checklist.md` — строки 1–30 (формат таблиц «Эмулятор» / «Телефон»).
- `app/build.gradle.kts`, строки 69–91 — **только читать**: подпись release (переменные окружения `PLINTH_KEYSTORE_FILE`, `PLINTH_KEYSTORE_PASSWORD`,
  `PLINTH_KEY_ALIAS`, `PLINTH_KEY_PASSWORD` либо `keystore.properties`), `isMinifyEnabled`, `isShrinkResources`.
- Защищённые тесты: `app/src/test/kotlin/io/github/puflik/plinth/R8RulesGuardTest.kt`, `R8OutputGuardTest.kt`; помощник `SourceTree.kt` (там же).

## Решение

### А. Правила R8 — `app/proguard-rules.pro`

Блок «YouTube (Ю3в)» заменяется блоком «YouTube (Ю3г)». Набор — спайка Ю3а; из её `-keep` берутся только те, что что-то держат:

```text
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
```

- Первые четыре правила — **обязательные**, их требует `R8RulesGuardTest` (текст нормализуется: комментарии, пробелы и переносы не важны). Перед каждым — комментарий в
  одну-две строки: что ищется по имени и что сломается в release без правила (не «чтобы работало»).
- Из спайка **не берутся** `-keep class org.mozilla.javascript.* {*;}`, `…engine.**` (покрыты `org.mozilla.javascript.**`), `-keep class javax.script.**` и
  `-keep class jdk.dynalink.**` (таких классов на Android нет: `rhino-engine-1.8.1.jar` несёт только `org/mozilla/javascript/engine/*`). Если R8 всё же попросит —
  добавить и записать в отчёт.
- **Пять строк `-dontwarn java.beans.*` из Ю3в удаляются.** Планировщик проверил: с набором выше `minifyGithubReleaseWithR8` проходит и без них (41 с, предупреждений нет).
  Исполнитель повторяет это для обеих flavor; если R8 всё же падает на `java.beans.*` — вернуть одной строкой `-dontwarn java.beans.**` с честным
  комментарием (Rhino ссылается на пакет, которого на Android нет, эти ветки не исполняются) и написать об этом в отчёте. Слов «только -dontwarn», «до Ю3г», «релиз не выпускать»
  в файле не остаётся.
- **Предупреждение `Unexpected reference to missing service class: META-INF/services/javax.script.ScriptEngineFactory`.** Источник: `rhino-engine-1.8.1.jar`
  несёт этот файл (в нём `org.mozilla.javascript.engine.RhinoScriptEngineFactory`), а интерфейса `javax.script` на Android нет; экстрактор зовёт Rhino напрямую
  (`org.mozilla.javascript.Context`), через `ServiceLoader` его никто не ищет. **Планировщик проверил:** на текущих правилах `minifyGithubReleaseWithR8` печатает это предупреждение
  (`WARNING: …base.jar: R8: Unexpected reference to missing service class: META-INF/services/javax.script.ScriptEngineFactory.`), а с набором выше (вместе с пятью `java.beans`)
  `minifyGithubReleaseWithR8` и `minifyFdroidReleaseWithR8` не печатают **ни одного** предупреждения. Исполнитель повторяет и пишет в отчёт; если предупреждение вернулось — снять
  правилом или объяснить в комментарии блока одной строкой. Исключать файл из APK (`packaging.resources.excludes`) здесь нельзя: это `app/build.gradle.kts`, вне списка «Менять»;
  можно предложить в отчёте.
- **Rhino не сужается** (+0,66 МиБ, 69 % прироста — `yu3a.md` §5 п. 7). Сужение допустимо только с проверкой каждого шага сценарием на устройстве; такого сценария у задачи
  нет, а бюджет 30 МБ держится с запасом: **замер планировщика** (release `github`, все ABI, без подписи) — 17 856 866 байт на текущих правилах → 18 299 458 с набором раздела А
  (+442 592 байта, +0,42 МиБ); `fdroid` — 18 299 458 (тот же размер). Оставить широкий `-keep`, записать цифры (свои, по своей сборке) в `apk-size.md` (раздел Д).
- **Без правил R8 уже портит Rhino и языки** (планировщик, вывод R8 на текущих правилах): переименованы `org.mozilla.javascript.Context`, `Kit`, `ScriptRuntime`, `Scriptable`,
  `VMBridge` и классы `…timeago.patterns.*` (`af`, `am`, `ar`, …) — в debug этого не видно.

### Б. Стражи (написаны планировщиком, защищены)

- `R8RulesGuardTest` — читает `app/proguard-rules.pro` по тексту: четыре обязательных правила YouTube, прежние правила ядра/плеера/номеров строк, и что R8 не выключен
  (`-dontobfuscate`, `-dontshrink`, `-dontoptimize`, `-ignorewarnings`, `-dontwarn *`/`**`). Идёт в обычных `testGithubDebugUnitTest` / `testFdroidDebugUnitTest`.
- `R8OutputGuardTest` — читает вывод настоящей сборки (`app/build/outputs/mapping/<flavor>Release/{mapping.txt,seeds.txt}`): шесть классов-представителей (Rhino, `ClassFileWriter`,
  языки `en`, `ru`) стоят в `mapping.txt` под своими именами и есть в `seeds.txt`; ни один класс пакетов `org.mozilla.javascript.` и `…timeago.patterns.` не переименован
  (`ClassFileWriter` держится правилом без `**` — только сам класс, его вложенные R8 вправе переименовать; он проверяется по имени); у `Context` в `seeds.txt` есть члены
  (значит, `{ *; }` сработало). Без вывода R8 тест **пропускается** (обычный прогон unit-тестов release не собирает); при `PLINTH_REQUIRE_R8_OUTPUT=1` отсутствие файлов
  обеих flavor — **провал**. Две другие проверки класса — на фикстурах разбора, идут всегда. `mapping.txt` (66–70 МБ) читается потоком. **`mapping.txt` пишет только
  `assemble…Release`** (`minify…WithR8` отдаёт `seeds.txt`, `usage.txt`, `configuration.txt`, но не `mapping.txt`) — гонять страж надо после `assemble`.
- Исполнитель сам тестов стражей не пишет. Нужны другие правила (R8 или прогон показали) — правило в `proguard-rules.pro` и **свой тест в новом файле**
  рядом (например, `R8RulesGuardExtraTest.kt`); защищённые файлы не менять.

### В. CI — `.github/workflows/ci.yml`

1. Шаг «Release APK within the size budget» → «Release APKs within the size budget»: `./gradlew assembleGithubRelease assembleFdroidRelease`; цикл по APK **обеих** flavor
   (`app/build/outputs/apk/github/release/*.apk`, `app/build/outputs/apk/fdroid/release/*.apk`); бюджет прежний — 30 000 000 байт на каждый APK. Пустой glob не должен
   проходить молча: нет APK — шаг падает. Комментарий над шагом обновить (две flavor, зачем fdroid: F-Droid собирает из исходников своим конвейером, и красный release
   дешевле увидеть в нашем CI).
2. Новый шаг сразу после него — «R8 keeps what the rules promise»:
   ```text
   ./gradlew testGithubDebugUnitTest --tests '*R8OutputGuardTest' --rerun
   ```
   с `env: PLINTH_REQUIRE_R8_OUTPUT: "1"`. В `gradle.properties` стоит `org.gradle.caching=true`, а ни переменная окружения, ни вывод R8 не входят во входы задачи `test`: без `--rerun`
   Gradle может взять старый зелёный результат из кэша, и страж «пройдёт», не выполнившись. Поэтому сразу за Gradle в том же шаге — проверка, что тест выполнялся, а не был пропущен:
   ```text
   xml=app/build/test-results/testGithubDebugUnitTest/TEST-io.github.puflik.plinth.R8OutputGuardTest.xml
   grep -q 'tests="3"' "$xml" && grep -q 'skipped="0"' "$xml"
   ```
   (формат проверен планировщиком: `tests` и `skipped` стоят в первом теге `<testsuite …>`; в пропущенном прогоне там `skipped="1"`).
3. В «Upload reports» добавить `app/build/outputs/mapping/*/seeds.txt` и `…/configuration.txt` (по ним читается красный страж). `mapping.txt` (≈ 57 МБ) **не** грузить.
4. Остальное в файле не менять.

### Г. Release на эмуляторах API 36, 30, 26

Эмуляторы: `Medium_Phone_API_36.0`, `Plinth_API_30`, `Plinth_API_26` (все x86_64). **По одному**: поднять, проверить, погасить (`adb -s emulator-5554 emu kill`).

**Сборка и подпись.** Release без ключа неподписанный — на эмулятор не встанет. Подписывать **отладочным ключом Android** (он есть на машине, публичный; ключей автора не трогать):

```text
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
$env:PLINTH_KEYSTORE_FILE = "$env:USERPROFILE\.android\debug.keystore"
$env:PLINTH_KEYSTORE_PASSWORD = "android"; $env:PLINTH_KEY_ALIAS = "androiddebugkey"; $env:PLINTH_KEY_PASSWORD = "android"
.\gradlew.bat assembleGithubRelease assembleFdroidRelease
```

Всё в одной команде PowerShell (среда между вызовами не живёт). Планировщик проверил: так собирается подписанный `app-github-release.apk` (17 869 154 байт на текущих правилах;
`apksigner verify --print-certs` из `build-tools\36.0.0` — `CN=Android Debug`). APK: `app\build\outputs\apk\github\release\app-github-release.apk` (проверять на эмуляторах — его; fdroid —
собрать и измерить, а запустить достаточно на API 36). Пакет release — `io.github.puflik.plinth` — **тот же, что у рабочего приложения автора на телефоне**: подписанный отладочным ключом,
он не встанет поверх (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), а удаление стёрло бы данные. Поэтому **каждый** вызов `adb` — с `-s emulator-5554`; простой `adb install` запрещён, телефон
автора бывает подключён. Телефон не трогать совсем.

**Запуск эмулятора** (без окна; рецепт проекта):

```text
Start-Process "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList "-avd","Plinth_API_30","-no-window","-no-audio","-no-snapshot-save","-gpu","swiftshader_indirect"
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 shell getprop sys.boot_completed      (ждать «1»)
```
(`ANDROID_HOME` = `C:\Users\User\AppData\Local\Android\Sdk`, если не задан.) Ставить: `adb -s emulator-5554 install -r <apk>`. Ведут приложение руками: `adb shell uiautomator dump /sdcard/ui.xml` →
`adb exec-out cat /sdcard/ui.xml` → центр нужного `bounds` → `adb shell input tap X Y`; скриншот — `cmd /c "adb -s emulator-5554 exec-out screencap -p > файл.png"` (PowerShell портит двоичный вывод).
`run-as` на release **не работает** (не отлаживаемый) — файлы приложения не править; всё через интерфейс.

**Сценарий на каждом API** (чистые данные: после установки приложение пустое):
1. Мастер первого запуска: «Skip all», в диалоге доступа к музыке — «Don't allow». Настройки → «Online sources»: все три строки включены (по умолчанию так).
2. Поиск «piano», затем кириллицей (например, «пианино»): в результатах секция «YouTube» не пуста. Названия YouTube до Ю5 могут отображаться как есть или как ID — достаточно того,
   что секция есть и в ней ≥ 5 строк; если не читается — ID из `adb logcat -d`.
3. Тап по треку YouTube — пошла игра. **Минута игры:** `adb -s emulator-5554 shell dumpsys media_session` → `PlaybackState {state=3 (PLAYING), position=…}`; два замера с разрывом 60 с,
   позиция выросла на 60 ± 3 с, `state=3` держится (не 6 — BUFFERING, не 7 — ERROR). Записать также время от тапа до `state=3`.
4. **Перемотка:** ползунок в плеере (тап по полосе примерно на 70 % длины), затем на 10 %; после каждого тапа — `dumpsys media_session`: позиция прыгнула, `state=3` вернулся
   за ≤ 10 с. Запасной способ: `adb -s emulator-5554 shell input keyevent 90` (вперёд) / `89` (назад).
5. `adb -s emulator-5554 logcat -d` — найти и привести строки с `FATAL EXCEPTION`, `AndroidRuntime`, `NoSuchMethodError`, `NoSuchFieldError`, `ClassNotFoundException`, `ReferenceError`
   и `403` (если нет — так и записать). На API 26 и 30 это заодно проверка десугаринга Ю3б (`URLEncoder.encode` с `Charset`, `Collectors.toUnmodifiableList`).
6. После API: `adb -s emulator-5554 uninstall io.github.puflik.plinth`, погасить эмулятор.

Результат — **таблица в отчёте** (форма — в конце файла) и те же строки в `docs/testing/youtube-live.md`, колонка «Эмулятор». Что-то не удалось (нет сети до YouTube, не поднялся образ) —
записать причину и числа, не выдумывать.

### Д. Документы

- **`docs/testing/youtube-live.md`** (новый) — ручной сценарий «живой YouTube», **не в CI**; формат таблиц — как `v0.2-checklist.md` (колонки «№ · Проверка · Как · Эмулятор · Телефон»).
  Разделы: вводная (зачем: экстрактор зависит от внешнего сервиса и ломается без нашей правки — `yu3a.md` §5 п. 1; когда прогонять: перед выпуском, после смены версии экстрактора
  `libs.versions.toml`/`newpipeExtractor`, после правок R8); «Подготовка» (сборка release, подпись отладочным ключом, эмуляторы, запрет на телефон автора — как в разделе Г); «Проверки»
  (таблица ниже); «Если не играет» (короткий порядок: версия экстрактора → `logcat` → правила R8 и стражи → сеть/403); «Прогоны» (журнал: дата, сборка, версия экстрактора, где прогнано).
  Строки таблицы «Проверки»: **Я1** поиск латиницей и кириллицей; **Я2** минута игры без остановок и ошибок; **Я3** перемотка вперёд и назад; **Я4** качество — какой поток выбран
  (формат и битрейт; из лога, если есть, иначе «не наблюдалось»); **Я5** срок ссылки (~6 ч, `yu3a.md` §5 п. 2): трек, поставленный на паузу дольше срока, играет снова (на эмуляторе — сдвиг часов или «не проверено, причина»);
  **Я6** 403 на чтении потока (`yu3a.md` §5 п. 10): вызвать нельзя — если встретился, записать время, сеть и действие приложения; **Я7** игра в фоне с погашенным экраном — только телефон (Ю6).
  Колонка «Эмулятор» — результат шага Г (`✅`/`❌` и цифры по API 36 / 30 / 26); колонка «Телефон» — «ждёт телефона (Ю6)».
- **`docs/testing/apk-size.md`** — новый раздел «С YouTube (Ю3г)» после «Итог»: размер release-APK всех ABI для `github` и `fdroid` (байты и МБ), прирост к 16,3 МБ из таблицы v0.2,
  размер `classes*.dex` (из `unzip -lv`), доля бюджета 30 МБ, строка «Rhino не сужался: …» с причиной. Существующие разделы не переписывать.

## Инварианты

- **Поведение приложения не меняется**: код приложения и ядра — вне задачи (только правила R8, CI и документы). Debug-сборка работает как раньше.
- **R8 включён**: `isMinifyEnabled` и `isShrinkResources` остаются, в правилах нет `-dontobfuscate`, `-dontshrink`, `-dontoptimize`, `-dontwarn *`/`**`.
- **Rhino и языки `timeago.patterns` в release остаются под своими именами** (стражи А и Б держат это на тексте и на выводе R8).
- **Бюджет**: release-APK каждой flavor ≤ 30 000 000 байт; число — в отчёте и `apk-size.md`.
- **Ничего чужого в репозитории**: ни ключей, ни `keystore.properties`, ни APK, ни `mapping.txt`; отладочный ключ — только в командах и в окружении.
- **Телефон автора не тронут**: во всех командах `adb` есть `-s emulator-…`.
- Предупреждение про `javax.script.ScriptEngineFactory` снято или объяснено — но не заглушено блоковым `-dontwarn`.

## Файлы

- **Менять:**
  - `app/proguard-rules.pro`
  - `.github/workflows/ci.yml`
  - `docs/testing/apk-size.md`
  - `docs/testing/youtube-live.md` (новый)
  - `docs/work/yu3g2.md` — только «Отчёт исполнителя»
  - новые тесты исполнителя (если нужны) — новые файлы в `app/src/test/kotlin/io/github/puflik/plinth/`
- **Не трогать:** остальное в `app/src/**`; `app/build.gradle.kts`, `build-logic/`, `gradle/`, `gradle.properties` (сборка); `core/**`; `docs/BUILD.md` (его правит `yu3g3`);
  `docs/testing/v0.2-checklist.md`; `CHANGELOG.md`, `tasks*.md`, `docs/decisions.md`, `CLAUDE.md`, `plan.md`, `README*.md`.
- **Защищённые тесты** (из коммита тестов; не менять, не удалять, не глушить):
  - `app/src/test/kotlin/io/github/puflik/plinth/R8RulesGuardTest.kt`
  - `app/src/test/kotlin/io/github/puflik/plinth/R8OutputGuardTest.kt`

## Тесты (написаны в коммите тестов)

`R8RulesGuardTest` (текст `app/proguard-rules.pro`):

| Тест | Вход → ожидание | На старом коде |
|---|---|---|
| `the timeago patterns of the extractor keep their names` | в файле есть `-keep class …timeago.patterns.** { *; }` | **падает**: утверждение «В …proguard-rules.pro нет правил: […]» |
| `rhino is kept whole` | есть `-keep class org.mozilla.javascript.** { *; }` и `-keep class org.mozilla.classfile.ClassFileWriter` | **падает**, то же |
| `protobuf messages keep their fields` | есть `-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }` | **падает**, то же |
| `the rules of the core bridge and the player stay` | прежние правила (номера строк, `Player$Listener`, JNA, `ffi.generated`) на месте | проходит (охраняет прежнее от случайной правки) |
| `r8 is not switched off and warnings are not silenced wholesale` | нет `-dontobfuscate`/`-dontshrink`/`-dontoptimize`/`-ignorewarnings`/`-dontwarn *` | проходит (охрана) |

`R8OutputGuardTest` (вывод R8):

| Тест | Вход → ожидание | На старом коде |
|---|---|---|
| `mapping txt tells a kept class from a renamed one` | фикстура `mapping.txt`: `a.Kept -> a.Kept:` против `b.Renamed -> x.y:`, члены и комментарии пропущены | проходит (проверка разбора) |
| `seeds txt lists a class by name and a member after a colon` | фикстура `seeds.txt` | проходит (проверка разбора) |
| `release builds keep the extractor and rhino under their own names` | вывод `assembleGithubRelease` / `…FdroidRelease`: классы-представители под своими именами и в `seeds.txt`, ни один класс пакетов `org.mozilla.javascript.` и `…timeago.patterns.` не переименован, у `Context` есть члены | без вывода R8 — пропуск (`skipped`); после `assembleGithubRelease` на старом коде **падает**: «githubRelease: R8 переименовал классы под правилом keep: [org.mozilla.javascript.Context, …Kit, …ScriptRuntime, …Scriptable, …VMBridge, …patterns.af, …am, …ar, …az, …be]» |

Классы-представители — проверены по jar-файлам версии `v0.26.5` / Rhino `1.8.1` (`org.mozilla.javascript.Context`, `ScriptRuntime`, `…engine.RhinoScriptEngineFactory`,
`org.mozilla.classfile.ClassFileWriter`, `…timeago.patterns.en`, `…patterns.ru`). Смена версии экстрактора или Rhino — повод перечитать список в `R8OutputGuardTest.KEPT_BY_NAME`.

## Проверки

Из `C:\claude\Plinth-yu3g2`, PowerShell, `$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"` (в каждой команде). Холодная сборка Rust-ядра в новом ворктри — 5–15 минут:
запускать в фоне.

```text
.\gradlew.bat ktlintCheck detekt testGithubDebugUnitTest testFdroidDebugUnitTest
.\gradlew.bat testGithubDebugUnitTest --tests "*R8RulesGuardTest" --rerun
$env:PLINTH_REQUIRE_R8_OUTPUT = "1"; .\gradlew.bat assembleGithubRelease assembleFdroidRelease; .\gradlew.bat testGithubDebugUnitTest --tests "*R8OutputGuardTest" --rerun
Get-ChildItem app\build\outputs\apk\github\release\*.apk, app\build\outputs\apk\fdroid\release\*.apk | Select-Object Name, Length
```

**Ловушка Gradle:** `app/proguard-rules.pro` и вывод R8 не входы задачи `test` (а в `gradle.properties` включён кэш сборки). Правишь правила — страж без `--rerun` может ответить
«UP-TO-DATE» старым зелёным; поэтому вторая и третья строки — с `--rerun`, а в отчёте — число тестов из свежего `TEST-…xml`. Подписанные сборки для эмуляторов (раздел Г) пишут вывод R8
в те же каталоги `app/build/outputs/mapping/<flavor>Release/`, так что страж можно гонять и после них.

Известное: на Windows падают 4 теста `DataStore*SettingsTest` (+ 3 `DataStoreOnlineSettingsTest`, `IOException` в `FileStorage.kt:114`) — не из-за задачи; в CI на Linux проходят.
Показатель к отчёту: `R8RulesGuardTest` 5/5, `R8OutputGuardTest` 3/3 **без пропусков** (в `app/build/test-results/testGithubDebugUnitTest/TEST-…R8OutputGuardTest.xml`: `skipped="0"`).
Время (планировщик, Windows): холодное ядро + `assembleGithubRelease` — 4 мин 41 с; `assembleGithubRelease assembleFdroidRelease` при тёплом кэше — 2 мин 15 с.

CI ветки читается без токена: `https://api.github.com/repos/Puflik/Plinth/actions/runs?branch=work/yu3g2` (интересует последний прогон по твоему последнему коммиту). Красный шаг стража — сначала `seeds.txt` и
`configuration.txt` из артефакта `reports`.

Эмуляторы — раздел Г; результат — таблица в отчёте.

## Готово, когда

1. Защищённые тесты зелёные: `R8RulesGuardTest` 5/5 в `testGithubDebugUnitTest` и `testFdroidDebugUnitTest`; `R8OutputGuardTest` 3/3, **не пропущен**, при `PLINTH_REQUIRE_R8_OUTPUT=1` после `assembleGithubRelease assembleFdroidRelease`.
2. `ktlintCheck` и `detekt` зелёные; `minifyGithubReleaseWithR8` и `minifyFdroidReleaseWithR8` (через `assemble…Release`) без ошибок; в отчёте — список предупреждений R8, которые остались, и почему.
3. `app/proguard-rules.pro`: блок YouTube — по разделу А; у каждого правила комментарий «что ищется по имени → что сломается»; пять `-dontwarn java.beans.*` удалены (или возвращены одной строкой с причиной); про `javax.script` — результат проверки; слов «до Ю3г», «релиз не выпускать» нет.
4. CI: оба release в бюджете, шаг стража выполняется и зелёный; CI ветки зелёный на последнем коммите (ссылка на прогон в отчёте).
5. Размеры: `apk-size.md` дополнен; бюджет 30 МБ держится (число и запас — в отчёте).
6. Таблица устройств в отчёте заполнена по API 36, 30 и 26: поиск, старт игры, позиция через 60 с, перемотка, ошибки в логе. Сбои — с причиной.
7. `docs/testing/youtube-live.md` создан по разделу Д; колонка «Эмулятор» совпадает с таблицей отчёта.
8. Изменены только файлы из «Менять»; защищённые тесты не тронуты (`git diff <коммит тестов> HEAD -- <два теста>` пуст); телефон автора не трогался.

## Остановиться и написать, если

- защищённый тест кажется неверным (например, класс из `KEPT_BY_NAME` не находится в `mapping.txt` при верных правилах и не является багом правил);
- для решения нужно менять файл вне списка «Менять» (в том числе `app/build.gradle.kts`: исключение ресурса, смена `packaging`);
- проверки упали дважды подряд (одна попытка исправить, потом стоп);
- release **не играет** YouTube на эмуляторе, а причина не в правилах R8 (сеть до YouTube, 403, ошибка экстрактора, баг приложения): записать журнал и числа, код приложения не чинить;
- нужны правила вне набора раздела А — допустимо, если их нашли ошибки R8 или `logcat` (правило + свой тест в новом файле + строка в отчёте); если правило оказывается «исправлением» бага приложения — стоп;
- эмулятор не поднимается или не хватает диска/памяти: записать, какой API не удалось, остальные доделать;
- любая команда требует `adb` без `-s emulator-…` или установки на телефон.

## На что смотреть при чтении плана (Opus)

Данных пользователя задача не трогает (правила сборки, CI, документы, ручные прогоны) — исполнитель берёт её сразу после пуша. Вопросы к Opus:

1. **Страж вывода R8 — JVM-тест с пропуском, а не скрипт в `tools/`.** Выбрано ради единообразия со стражами (`BackupRulesTest`, `YoutubeBoundaryTest`) и чтобы фикстурные проверки разбора шли в обычном прогоне.
   Цена: настоящая проверка идёт только в CI-шаге после release и локально — при `PLINTH_REQUIRE_R8_OUTPUT=1`. Альтернатива — `tools/check_r8_output.py` + шаг CI; решение не необратимо, тесты тогда переписываются.
2. **`--rerun` в шаге CI и локально** — файл правил и вывод R8 не входы задачи `test`, кэш сборки включён: без `--rerun` страж может «пройти», не выполнившись. В CI добавлена ещё проверка по
   `TEST-…xml` (`skipped="0"`). Надёжнее было бы объявить `proguard-rules.pro` входом задачи в `app/build.gradle.kts` (`tasks.withType<Test> { inputs.file("proguard-rules.pro") }`) —
   это вне списка «Менять»; предлагаю отдельной правкой Opus. Если `--rerun` не нравится, другой способ гарантии — `cleanTestGithubDebugUnitTest` перед шагом.
3. **`assembleFdroidRelease` в CI**, а не только `minifyFdroidReleaseWithR8`: Rust-часть общая (повторной сборки ядра нет), добавляются R8 и упаковка — порядка минуты-двух. Если CI-время важнее — хватит `minifyFdroidReleaseWithR8` и сверки `mapping.txt`; тогда править раздел В и `VARIANTS` в тесте (+ пересобрать).
4. **Отладочный ключ для release на эмуляторе.** Пакет release равен пакету рабочего приложения автора. На эмуляторах конфликта нет; запрет на телефон записан в «Инварианты» и «Остановиться». Согласен ли, что ключ и пароль `android` стоят в тексте задачи? Это публичные значения стандартного отладочного хранилища.
5. **`-keep javax.script.**` и `jdk.dynalink.**` из спайка не берутся** (на Android этих классов нет). Если Opus знает причину, по которой NewPipe их держит (сборка под JVM?), — вернуть.
6. **Rhino не сужается** (см. А). Если автору важно вернуть 0,66 МиБ — отдельная задача со сценарием на устройстве.
7. **Сеть эмуляторов до YouTube.** Ю3а ходил; проверка идёт с компьютера автора (в РФ YouTube может замедляться). Если сеть режет, эмулятор не пройдёт — стоп-условие написано.
8. **Телефон** задачей не охвачен (Ю6; в `youtube-live.md` колонка «Телефон» остаётся «ждёт телефона»). Релиз 0.3 выпускать после `yu3g3` и, по желанию автора, после прогона на телефоне.
9. **Бюджет окна.** Исполнителю: две сборки release + три эмулятора — часы; результаты по API писать в отчёт по ходу (обрыв лимитом не должен съесть уже сделанное).

## Временная реализация и мутации

Проверка планировщика (Windows, ворктри `C:\claude\Plinth-yu3g2`, основа `c6f5939`). Временная реализация — набор раздела А дописан в конец `proguard-rules.pro` (в коммит не входит,
ветка чиста); сравнение — на настоящей сборке, а не на фикстурах.

| Что | Результат |
|---|---|
| `ktlintCheck`, `detekt` по двум тестам | чисто |
| Текущие правила, `R8RulesGuardTest` | 5 тестов: 3 падают утверждением `R8RulesGuardTest.kt:61` («В …proguard-rules.pro нет правил: […]»), 2 проходят (охрана прежнего) |
| Текущие правила, `R8OutputGuardTest` без вывода R8 | 3 теста: 2 проходят, настоящий — `skipped` («R8 не запускали…») |
| Текущие правила, `assembleGithubRelease` (4 мин 41 с, APK 17 856 866 байт), затем `R8OutputGuardTest` | настоящий тест **падает**: «githubRelease: R8 переименовал классы под правилом keep: [org.mozilla.javascript.Context, …Kit, …ScriptRuntime, …Scriptable, …VMBridge, …patterns.af, …am, …ar, …az, …be]»; R8 печатает предупреждение про `javax.script.ScriptEngineFactory` |
| Временная реализация (набор А + пять `java.beans`), `assembleGithubRelease assembleFdroidRelease` (2 мин 15 с), `PLINTH_REQUIRE_R8_OUTPUT=1` | оба APK по 18 299 458 байт (+442 592 к текущим правилам); R8 **без предупреждений**; `R8RulesGuardTest` 5/5; `R8OutputGuardTest` 3/3, `skipped=0` |
| Тот же прогон, первый раз | настоящий тест упал на `ClassFileWriter$BootstrapEntry`, `$ClassFileFormatException`, `$MHandle`, `$StackMapTable`: проверка «семейство не переименовано» шла по префиксу `ClassFileWriter`, а правило держит только сам класс. **Ошибка теста, исправлена** (семейства — только пакеты; `ClassFileWriter` — по имени); прогнано заново, зелёное |
| Без пяти `-dontwarn java.beans.*` (набор А + `minifyGithubReleaseWithR8`) | проходит, 41 с, без предупреждений |

Мутанты текстового стража (правка `proguard-rules.pro` на временной реализации, `testGithubDebugUnitTest --tests *R8RulesGuardTest --rerun`):

| Мутант | Упал |
|---|---|
| M0 без правки | никто (5/5) |
| M1 удалено правило `timeago.patterns` | `the timeago patterns of the extractor keep their names` |
| M2 удалено `-keep class org.mozilla.javascript.** { *; }` | `rhino is kept whole` |
| M3 удалено `-keep class org.mozilla.classfile.ClassFileWriter` | `rhino is kept whole` |
| M4 удалено правило protobuf (три строки) | `protobuf messages keep their fields` |
| M5 Rhino сужен до `org.mozilla.javascript.Context { *; }` | `rhino is kept whole` |
| M6 удалено `-keep class com.sun.jna.** { *; }` | `the rules of the core bridge and the player stay` |
| M7 добавлено `-dontobfuscate` | `r8 is not switched off and warnings are not silenced wholesale` |
| M8 добавлено `-dontwarn **` | то же |
| M9 те же правила, записанные иначе (`**{*;}` в одну строку, `{ *; }` в три строки) | никто (5/5): страж не придирается к форме |

Настоящий страж вывода (мутанты по R8 не гонялись — каждый стоит несколько минут сборки): его красный вид показал прогон на текущих правилах (все три семейства не держатся), зелёный — на
временной реализации.

## Чтение плана (Opus, 2026-10-24)

План принят, исполнитель работает по этому файлу. Ответы на вопросы:

1. Страж вывода R8 — JVM-тест с пропуском — да.
2. `--rerun` в шаге CI и локально — да; `app/build.gradle.kts` в этой задаче не трогать.
3. `assembleFdroidRelease` в CI — да.
4. Стандартное отладочное хранилище (`android`) для release на эмуляторе — да. Рабочий ключ автора
   (`keystore.properties`) не трогать и не копировать; release — только на эмуляторы, на телефон — нет.
5. `javax.script.**` и `jdk.dynalink.**` не держать, если release играет на всех трёх эмуляторах — да.
6. Rhino не сужать — да.
7. Сеть: не проходит — стоп по условию из файла.
8. Телефон — Ю6, колонка «Телефон» ждёт — да.
9. Итоги по каждому API — в отчёт сразу, коммит и пуш после каждого шага.

## Отчёт исполнителя

Работа идёт шагами, после каждого — коммит и пуш; ниже — по шагам.

**А. Правила R8 — готово** (`293d665`).
- `app/proguard-rules.pro`: блок «YouTube (Ю3г)» вместо «YouTube (Ю3в)» — четыре обязательных `-keep` и четыре `-dontwarn` (`JavaToJSONConverters`, `tools.**`, `javax.script.**`,
  `jdk.dynalink.**`), у каждого правила комментарий. Пять `-dontwarn java.beans.*` удалены: покрыты `-dontwarn …JavaToJSONConverters` (все пять классов нужны одному методу этого класса).
- R8 обеих flavor (`--no-build-cache`, оба release за 89–91 с): **ни ошибок, ни предупреждений** (в логе нет ни одной строки warning/missing). Предупреждение про
  `javax.script.ScriptEngineFactory` снято строкой `-dontwarn javax.script.**` и объяснено в комментарии. Правил вне набора раздела А не понадобилось.
- Опыт «без четырёх `-dontwarn`»: R8 падает на «Missing classes» — `java.beans` 5, `javax.script` 10 (`org.mozilla.javascript.engine.*`), `jdk.dynalink` 18 (`Bootstrapper`, линкеры) — и печатает
  предупреждение про service class. **`-dontwarn …tools.**` в этой сборке ничего не подавляет** (без него ошибок нет): оставлен по набору раздела А, при желании его можно убрать.
- Причины правил (разбор jar `v0.26.5` и Rhino 1.8.1 через `javap`) — комментарии в файле говорят то, что проверено. (1) `timeago.patterns`: `PatternMap` зовёт `<язык>.getInstance()` прямыми вызовами,
  по имени классы не ищутся — **функционально правило не нужно**, оно держит имена по требованию стража и как у NewPipe; утверждение плана «разбор дат упал бы» для этой версии **не подтвердилось**.
  (2) `ClassFileWriter`: экстрактор вызывает `Context.setInterpretedMode(true)`, ветка сборки байт-кода не исполняется. (3) Rhino ищет по строковым именам `VMBridge_jdk18`, `Interpreter`, `RegExpImpl`,
  `XMLLibImpl`, `JavaAdapter`, `optimizer.Codegen`, `resources.Messages` — здесь правило существенно. (4) protobuf: 11 классов `GeneratedMessageLite` в экстракторе (токены продолжения плейлистов).
- Проверки (Windows): `R8RulesGuardTest` 5/5 в `testGithubDebugUnitTest` и `testFdroidDebugUnitTest`; `R8OutputGuardTest` 3/3, `skipped="0"` (после `assembleGithubRelease assembleFdroidRelease`,
  `PLINTH_REQUIRE_R8_OUTPUT=1`, свежий `TEST-…xml`); `ktlintCheck`, `detekt` зелёные; юнит-тесты обеих flavor: 853, падают 7 — все `DataStore*SettingsTest` (известное: Windows,
  `FileStorage.kt:114`; не из-за задачи).
- Размеры (мой замер, release, все ABI): без подписи `app-github-release-unsigned.apk` и `app-fdroid-release-unsigned.apk` по **18 299 458** байт (17,45 МиБ, 61,0 % бюджета 30 000 000);
  подписанные отладочным ключом — **18 311 746** байт (+12 288).

**В. CI — готово** (`3fd9acd`): шаг «Release APKs within the size budget» (обе flavor, пустой glob и превышение бюджета роняют шаг — цикл проверен локально на трёх случаях: нет APK fdroid → падает,
оба на месте → проходит, больше 30 000 000 → падает), шаг «R8 keeps what the rules promise» (`--rerun`, `PLINTH_REQUIRE_R8_OUTPUT=1`, проверка `tests="3"` и `skipped="0"` в XML),
`seeds.txt` и `configuration.txt` в «Upload reports». Прогон CI по последнему коммиту — ниже, когда придёт.

**Г. Эмуляторы.**

*API 36* (`Medium_Phone_API_36.0`, `app-github-release.apk`, подпись отладочным ключом, пустые данные; на эмуляторе уже стояла старая установка 0.1.0 от 24 сентября — удалена `adb -s emulator-5554 uninstall`, телефон не тронут):
сценарий пройден целиком, цифры — в строке таблицы ниже. Мастер: «Skip all» → «Don’t allow»; Настройки → «Online sources»: главный переключатель, «Internet Archive» и «YouTube» включены.
Тап по результату YouTube открывает страницу трека (кнопка «Play», строка «Source: YouTube»), игра идёт после «Play»; перемотка — ползунок в полноэкранном плеере (нажатие на дорожку).
Заметки: (а) значок источника у строки трека YouTube имеет описание «From Internet Archive» (до Ю5, названия и значок «YouTube» — их задача); (б) юникод в ответе читается (`Álvaro Fernández`, `范逸臣` в секции YouTube).
*fdroid* (`app-fdroid-release.apk`, 0.2.3-fdroid, тот же эмулятор): запуск, поиск «piano» — секция YouTube есть; игра пошла через 1,4 с после «Play», за 30 с позиция 0 → 28,4 с, `state=3`; `FATAL EXCEPTION`,
`NoSuch*`, `ClassNotFound`, `ReferenceError`, 403 — нет. После прогона `uninstall`, эмулятор погашен.

*API 30* (`Plinth_API_30`, `app-github-release.apk`, пустые данные; отладочный `….plinth.debug` на эмуляторе не тронут): сценарий пройден целиком. Отличия от 36: разрешение на музыку — «Deny»;
в `uiautomator dump` нижнее меню и «Skip all» без координат — тапы по скриншоту; формат `dumpsys media_session` — `PlaybackState {state=3, …}` без имён (мой разбор сперва не понял — исправил, игра шла).
Для замера старта игру сначала поставил на паузу и нажал «Play» заново (трек пошёл с 0). После прогона `uninstall`, эмулятор погашен.

*API 26* (`Plinth_API_26`, `app-github-release.apk`, пустые данные; отладочный `….plinth.debug` на эмуляторе не тронут): сценарий пройден целиком, десугаринг Ю3б (`URLEncoder.encode` с `Charset`,
`Collectors.toUnmodifiableList`) работает — поиск и разбор ответа идут без `NoSuchMethodError`. Разрешение на музыку — «DENY». **Выброс:** первая перемотка вперёд (в ещё не загруженное место) держалась в `BUFFERING`
20–23 с — больше порога 10 с из сценария; три повтора того же тапа — 1,4–1,5 с, возвратные — 0,5 с. В `logcat` на уровне W/E причин нет (таймаут сети эмулятора, как в `yu3a.md` §5 п. 5, — гипотеза, не проверялась).
После прогона `uninstall`, эмулятор погашен.

**Д. Документы — готово.** `docs/testing/youtube-live.md` создан по разделу Д: вводная, «Подготовка» (сборка release, подпись отладочным ключом, эмуляторы, запрет на телефон автора), «Проверки» Я1–Я7
(колонка «Эмулятор» совпадает с таблицей ниже; «Телефон» — «ждёт телефона (Ю6)»), журнал `logcat`, подводные камни ручного ведения, «Если не играет», «Прогоны». `docs/testing/apk-size.md` — новый раздел
«С YouTube (Ю3г)» после «Итога»: размеры обеих flavor (байты и МБ), прирост к 16,3 МБ (+1,15 МБ), `classes*.dex`, доля бюджета (61,0 %, запас 11 700 542 Б), строка «Rhino не сужался».

**По «Готово, когда»:**
1. ✅ `R8RulesGuardTest` 5/5 в `testGithubDebugUnitTest` и `testFdroidDebugUnitTest`; `R8OutputGuardTest` 3/3, `skipped="0"`, при `PLINTH_REQUIRE_R8_OUTPUT=1` после `assembleGithubRelease assembleFdroidRelease`.
2. ✅ `ktlintCheck`, `detekt` зелёные; `minifyGithubReleaseWithR8` и `minifyFdroidReleaseWithR8` без ошибок. **Предупреждений R8 не осталось** (список пуст): `javax.script.ScriptEngineFactory` и «Missing classes» закрыты четырьмя `-dontwarn`.
3. ✅ Блок YouTube по разделу А; у каждого правила комментарий; пять `-dontwarn java.beans.*` удалены (не вернулись, R8 не просил); про `javax.script` — результат проверки (см. А); слов «до Ю3г», «релиз не выпускать» в файле нет.
4. ✅/⏳ CI: оба release в бюджете и шаг стража — см. «CI» ниже.
5. ✅ `apk-size.md` дополнен; бюджет держится: 18 299 458 Б из 30 000 000 (61,0 %, запас 11 700 542 Б).
6. ✅ Таблица устройств заполнена по API 36, 30, 26. Сбои: кириллицей не набрано (ни на одном API); API 26 — один выброс перемотки 20–23 с.
7. ✅ `docs/testing/youtube-live.md` создан; колонка «Эмулятор» совпадает с таблицей.
8. ✅ Изменены только файлы из «Менять»: `app/proguard-rules.pro`, `.github/workflows/ci.yml`, `docs/testing/apk-size.md`, `docs/testing/youtube-live.md`, `docs/work/yu3g2.md` (отчёт). Защищённые тесты не тронуты
   (`git diff b85368a HEAD -- <два теста>` пуст). Телефон автора не трогался: каждая команда `adb` — с `-s emulator-5554`; на эмуляторе API 36 стояла старая установка 0.1.0 (24 сентября) — удалена, это эмулятор.

**Находки и предложения Opus:**
- **`-keep …timeago.patterns.**` в v0.26.5 функционально не нужен** (`PatternMap` вызывает `<язык>.getInstance()` напрямую, `javap`): план обещал падение разбора дат — не подтвердилось. Правило оставлено (его требует `R8RulesGuardTest`, как у NewPipe, комментарий честный).
  Если когда-нибудь захочется сэкономить размер — решение за вами, но тогда править и защищённые тесты (`R8OutputGuardTest.KEPT_BY_NAME`).
- **`-dontwarn org.mozilla.javascript.tools.**` ничего не подавляет** (без него R8 не жалуется) — оставлен по набору раздела А; можно убрать, страж на него не смотрит.
- **Перемотка на API 26: один раз 20–23 с `BUFFERING`** (больше порога 10 с): перемотка в не загруженное ещё место, три повтора 1,4–1,5 с, на 36 и 30 такого нет. Причину не нашёл (сеть эмулятора?). Проверить на телефоне (Ю6).
- **Кириллица в поиске не проверена:** `adb input text` и `emu event text` — только ASCII; нужна экранная клавиатура руками, отладочный хук или ADBKeyboard. Строка Я1 в `youtube-live.md` помечена ⏸.
- **UX до Ю5:** тап по результату YouTube открывает страницу трека («Play» на ней), а у значка источника в строке трека описание «From Internet Archive» при подписи «Source: YouTube» — задача Ю5.
- **CI отменяет прогон при каждом новом пуше:** пуши по ходу работы отменяли друг друга; вердикт CI брать по последнему прогону на неподвижной ветке. Шаг «R8 keeps what the rules promise» отдельно проверен локально (`--rerun`, XML `tests="3"`, `skipped="0"`).
- Известное (не из-за задачи): на Windows падают 7 `DataStore*SettingsTest` в каждой flavor (`IOException` в `FileStorage.kt:114`).
- Предложение из плана остаётся в силе: объявить `proguard-rules.pro` входом задачи `test` в `app/build.gradle.kts` (вне «Менять») — тогда `--rerun` не нужен.

**Таблица устройств** (заполнить по ходу, строка на API):

| API | Эмулятор | Поиск «piano» / кириллицей (YouTube, строк) | Старт игры, с | Позиция через 60 с (было → стало) | Перемотка (→ 70 % / → 10 %; возобновление, с) | Ошибки в logcat |
|---|---|---|---|---|---|---|
| 36 | `Medium_Phone_API_36.0` | «piano»: секция «YouTube», ≥ 19 строк (9 + 10 на двух экранах), названия и исполнители читаются; **кириллицей — не набрана** (`adb input text` и `emu event text` — только ASCII) | 1,5 с от «Play» | 0 → 59,5 с за 60 с (через 10 с: 8,4 · 17,5 · 29,3 · 38,5 · 47,4 · 59,5), `state=3` на всех замерах | → 70 %: 153,7 → 217,3 с, `PLAYING` через 0,4 с; → 10 %: 220,3 → 30,9 с, `PLAYING` через 0,7 с; игра идёт дальше (38,4 → 59,5 с за 20 с) | нет `FATAL EXCEPTION`, `NoSuchMethodError`, `NoSuchFieldError`, `ClassNotFoundException`, `ReferenceError`, 403 (в процессе Plinth) |
| 30 | `Plinth_API_30` | «piano»: секция «YouTube» есть, ≥ 13 строк; **кириллицей — не набрана** (как на 36) | 3,1 с от «Play» (с опросом `adb`, ≈ 0,4 с на опрос) | 0 → 59,6 с за 60 с (через 10 с: 8,6 · 17,4 · 29,4 · 38,4 · 47,5 · 59,6), `state=3` на всех замерах | → 70 %: 72,1 → 219,7 с, `BUFFERING` ≈ 3,5 с, `PLAYING` через 3,8 с; → 10 %: 219,7 → 32,1 с, `PLAYING` через 0,8 с; игра идёт дальше (32,1 → 44,3 с за ≈ 13 с) | нет `FATAL EXCEPTION`, `NoSuchMethodError`, `NoSuchFieldError`, `ClassNotFoundException`, `ReferenceError`, 403 (в процессе Plinth); только предупреждения «hidden API» и `InputConnection` |
| 26 | `Plinth_API_26` | «piano»: секция «YouTube» есть, ≥ 8 строк на экране; **кириллицей — не набрана** (как на 36) | 1,0 с от «Play» | 0 → 59,5 с за 60 с (через 10 с: 8,4 · 17,3 · 29,4 · 38,5 · 47,5 · 59,5), `state=3` на всех замерах | → 70 %: **первый раз `BUFFERING` 20–23 с** (позиция 69,2 → 219,7 с; выброс — позже в трёх повторах 1,4–1,5 с), → 10 %: 0,5 с (пять раз); игра идёт дальше (32,1 → 44,4 с за ≈ 13 с) | нет `FATAL EXCEPTION`, `NoSuchMethodError`, `NoSuchFieldError`, `ReferenceError`, 403 в процессе Plinth; три `ClassNotFoundException: android.webkit.TracingController` — в чужом процессе (pid 2313, `Chrome.apk`; у Plinth pid 3865) до запуска приложения |
