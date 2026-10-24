# yu3g2 — R8 и release-сборка YouTube на эмуляторах (Ю3г, часть 2)

> Файл задачи для исполнителя (Sonnet). Пишет планировщик; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

- **Ветка:** `work/yu3g2` · **Ворктри:** `C:\claude\Plinth-yu3g2`
- **Основа:** `main` на `c6f5939` · **Коммит тестов:** `<хеш>` (первый в ветке)
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
- **Пять строк `-dontwarn java.beans.*` из Ю3в удаляются.** Планировщик проверил: с набором выше `minifyGithubReleaseWithR8` проходит и без них (41 с, предупреждений нет) — они были
  нужны, пока Rhino не стоял под `-keep`. Исполнитель повторяет это для обеих flavor; если R8 всё же падает на `java.beans.*` — вернуть одной строкой `-dontwarn java.beans.**` с честным
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

Всё в одной команде PowerShell (среда между вызовами не живёт). APK: `app\build\outputs\apk\github\release\app-github-release.apk` (проверять на эмуляторах — его; fdroid —
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

## Отчёт исполнителя

_Заполняет исполнитель: что сделано, что не вышло и почему, коммиты, вывод проверок (коротко)._

**Таблица устройств** (заполнить по ходу, строка на API):

| API | Эмулятор | Поиск «piano» / кириллицей (YouTube, строк) | Старт игры, с | Позиция через 60 с (было → стало) | Перемотка (→ 70 % / → 10 %; возобновление, с) | Ошибки в logcat |
|---|---|---|---|---|---|---|
| 36 | `Medium_Phone_API_36.0` | | | | | |
| 30 | `Plinth_API_30` | | | | | |
| 26 | `Plinth_API_26` | | | | | |
