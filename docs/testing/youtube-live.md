# Живой YouTube: ручной сценарий

Ручная проверка YouTube на настоящих серверах — **не в CI**: тесты и CI ходят только на фикстуры и петлю, а сам YouTube — внешний сервис. Написан в Ю3г
(`docs/work/yu3g2.md`), прогнан 2026-10-24 на эмуляторах API 36, 30 и 26; на телефоне ещё не прогонялся (Ю6).

## Зачем и когда прогонять

Экстрактор (NewPipeExtractor, `gradle/libs.versions.toml`, `newpipeExtractor`) зависит от внешнего сервиса: YouTube меняет плеер, подписи адресов потоков и политику выдачи без
предупреждения, и разбор ломается без нашей правки. Запасных путей нет, всё держит один клиент (`VISIONOS`, `docs/work/yu3a.md` §5 п. 1). Тесты на фикстурах этого не видят.

Прогонять:

- перед выпуском (релиз 0.3 и дальше);
- после смены версии экстрактора или Rhino (`newpipeExtractor` в `libs.versions.toml`);
- после правок правил R8 (`app/proguard-rules.pro`): debug работает без R8, а стражи `R8RulesGuardTest` и `R8OutputGuardTest` держат только имена классов, а не работу;
- когда пользователи пишут, что YouTube не играет.

## Подготовка

1. **Сборка release.** PowerShell, одна команда (среда между вызовами не живёт). Подпись — стандартным отладочным ключом Android (публичный, пароль `android`);
   рабочий ключ автора (`keystore.properties`) не трогать, не копировать, не печатать.

   ```text
   $env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
   $env:PLINTH_KEYSTORE_FILE = "$env:USERPROFILE\.android\debug.keystore"
   $env:PLINTH_KEYSTORE_PASSWORD = "android"; $env:PLINTH_KEY_ALIAS = "androiddebugkey"; $env:PLINTH_KEY_PASSWORD = "android"
   .\gradlew.bat assembleGithubRelease assembleFdroidRelease
   ```

   APK: `app\build\outputs\apk\github\release\app-github-release.apk` (проверять на всех API) и `…\fdroid\release\app-fdroid-release.apk` (запустить достаточно на одном).
2. **Эмуляторы** (x86_64, без окна, по одному: поднять, проверить, погасить): `Medium_Phone_API_36.0`, `Plinth_API_30`, `Plinth_API_26`.

   ```text
   Start-Process "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList "-avd","Plinth_API_30","-no-window","-no-audio","-no-snapshot-save","-gpu","swiftshader_indirect"
   adb -s emulator-5554 wait-for-device
   adb -s emulator-5554 shell getprop sys.boot_completed      (ждать «1»)
   ```

   `ANDROID_HOME` = `C:\Users\User\AppData\Local\Android\Sdk`, если не задан. Погасить: `adb -s emulator-5554 emu kill`.
3. **Телефон автора не трогать.** Пакет release `io.github.puflik.plinth` — тот же, что у рабочего приложения на телефоне: подписанный отладочным ключом, он не встанет поверх
   (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), а удаление стёрло бы данные. Поэтому **каждая** команда `adb` — с `-s emulator-5554`, простой `adb install` запрещён.
   Ставить: `adb -s emulator-5554 install -r <apk>`; после прогона — `adb -s emulator-5554 uninstall io.github.puflik.plinth`.
4. **Вести приложение руками:** `adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml`, `adb -s emulator-5554 exec-out cat /sdcard/ui.xml`, центр нужного `bounds`,
   `adb -s emulator-5554 shell input tap X Y`. Скриншот: `cmd /c "adb -s emulator-5554 exec-out screencap -p > файл.png"` (PowerShell портит двоичный вывод).
   `run-as` на release не работает — файлы приложения не править, всё через интерфейс. Чистые данные: после установки приложение пустое.

## Проверки

| № | Проверка | Как | Эмулятор | Телефон |
|---|---|---|---|---|
| Я1 | Поиск латиницей и кириллицей | Мастер первого запуска: «Skip all», в диалоге доступа к музыке отказать («Don’t allow»; на API 30 и 26 — «Deny»). Настройки → «Online sources»: все три строки включены (так по умолчанию). Поиск → «piano», затем «пианино»: секция «YouTube» не пуста (≥ 5 строк; названия до Ю5 могут отображаться как есть) | ✅ латиницей: **36** — ≥ 19 строк, **30** — ≥ 13, **26** — ≥ 8; названия и исполнители читаются, юникод в ответе цел (`Álvaro Fernández`, `范逸臣`); fdroid (36) — секция есть. ⏸ **кириллицей не проверено:** `adb shell input text` и `adb emu event text` набирают только ASCII — набрать на экранной клавиатуре эмулятора | ждёт телефона (Ю6) |
| Я2 | Минута игры без остановок и ошибок | Тап по результату YouTube открывает страницу трека — «Play» на ней запускает игру. `adb -s emulator-5554 shell dumpsys media_session` → `PlaybackState {state=3 (PLAYING), position=…}` (на API 36 — `state=PLAYING(3)`); два замера с разрывом 60 с: позиция выросла на 60 ± 3 с, `state=3` держится (не 6 — BUFFERING, не 7 — ERROR). Записать время от «Play» до `state=3` | ✅ **36:** старт 1,5 с, 0 → 59,5 с; **30:** 3,1 с, 0 → 59,6 с; **26:** 1,0 с, 0 → 59,5 с; `state=3` на всех замерах (через каждые 10 с). fdroid на 36: старт 1,4 с, за 30 с 0 → 28,4 с | ждёт телефона (Ю6) |
| Я3 | Перемотка вперёд и назад | Полноэкранный плеер: тап по дорожке ползунка на ≈ 70 % длины, затем на ≈ 10 %; после каждого тапа `dumpsys media_session`: позиция прыгнула, `state=3` вернулся за ≤ 10 с. Запасной способ: `adb -s emulator-5554 shell input keyevent 90` (вперёд) / `89` (назад) | ✅ **36:** → 70 % за 0,4 с, → 10 % за 0,7 с; **30:** 3,8 с и 0,8 с. ⚠ **26:** → 10 % — 0,5 с (пять раз), → 70 % — 1,4–1,5 с (три раза), **но первый раз `BUFFERING` держался 20–23 с** — выброс на перемотке в ещё не загруженное место; на 36 и 30 такого не было, причина не найдена (в `logcat` на уровне W/E ничего), повторить на телефоне | ждёт телефона (Ю6) |
| Я4 | Качество: какой поток выбран | Формат и битрейт — из лога, если приложение их пишет, иначе «не наблюдалось» | не наблюдалось: приложение не пишет формат выбранного потока в `logcat`, в `dumpsys media_session` его нет | ждёт телефона (Ю6) |
| Я5 | Срок жизни ссылки (≈ 6 ч, `yu3a.md` §5 п. 2) | Трек, поставленный на паузу дольше срока, играет снова. На эмуляторе — сдвиг системных часов на 6 ч и больше вперёд, иначе «не проверено» с причиной | не проверено: часы эмулятора в этом прогоне не сдвигали | ждёт телефона (Ю6) |
| Я6 | 403 на чтении потока (`yu3a.md` §5 п. 10) | Вызвать нельзя: если встретился — записать время, сеть и действие приложения. Искать в `adb -s emulator-5554 logcat -d` по строке `403` | ✅ не встретился: в `logcat` процесса Plinth на 36, 30, 26 (и fdroid на 36) нет ни одной строки с 403 | ждёт телефона (Ю6) |
| Я7 | Игра в фоне с погашенным экраном | Минута игры при погашенном экране (`KEYCODE_SLEEP`); на HiOS смотреть, не замораживает ли оболочка процесс (`yu3a.md` §8 п. 5) | — (только телефон) | ждёт телефона (Ю6) |

**Журнал после каждого API.** `adb -s emulator-5554 logcat -d`: найти и привести строки с `FATAL EXCEPTION`, `AndroidRuntime`, `NoSuchMethodError`, `NoSuchFieldError`,
`ClassNotFoundException`, `ReferenceError` и `403`; если нет — так и записать. На API 26 и 30 это заодно проверка десугаринга Ю3б (`URLEncoder.encode` с `Charset`,
`Collectors.toUnmodifiableList`). Результат 2026-10-24: **ни одной такой строки в процессе Plinth** на 36, 30 и 26; на API 26 три `ClassNotFoundException: android.webkit.TracingController`
в чужом процессе (pid 2313, `Chrome.apk`; у Plinth pid 3865) до запуска приложения — не из Plinth.

**Подводные камни ручного ведения** (все пойманы в прогоне):

- `adb shell input text` и `adb emu event text` — только ASCII. Кириллицу — на экранной клавиатуре.
- Перед прокруткой результатов убрать экранную клавиатуру (`adb -s emulator-5554 shell input keyevent 4`, если в `dumpsys input_method` `mInputShown=true`): свайп по клавиатуре Gboard —
  жестовый ввод, он допишет слово в поле поиска.
- На API 26 и 30 `uiautomator dump` отдаёт нулевые `bounds` у «Skip all» и нижнего меню — тапать по скриншоту (экран эмулятора 1080×2340).
- Формат `dumpsys media_session`: на API 30 и 26 — `state=3`, на API 36 — `state=PLAYING(3)`. `position` в нём обновляется не непрерывно, а раз в несколько секунд, поэтому
  время возобновления после перемотки определять по `state`, а не по позиции.
- Ползунок плеера — на ≈ 96 пикселей выше подписей времени (экран 1080×2400: y ≈ 1342, x от 68 до 1010).

## Если не играет

1. **Версия экстрактора** (`gradle/libs.versions.toml`, `newpipeExtractor`; сейчас `v0.26.5`). YouTube мог сменить плеер: посмотреть выпуски NewPipeExtractor и открытые проблемы,
   обновить версию и повторить весь сценарий.
2. **`logcat`:** `adb -s emulator-5554 logcat -d`, процесс Plinth (`adb -s emulator-5554 shell pidof io.github.puflik.plinth`, затем `logcat -d --pid=…`). Искать `ParsingException`,
   `ExtractionException`, `Could not find deobfuscation function`, `ReferenceError` (Rhino не нашёл функцию подписи или `n`), `NoSuchMethodError`, `ClassNotFoundException`.
3. **Правила R8 и стражи.** Если в debug играет, а в release нет — виновен R8. Стражи (`R8RulesGuardTest`, `R8OutputGuardTest`, `docs/work/yu3g2.md`) красные — читать
   `seeds.txt` и `configuration.txt` из артефакта `reports` в CI. Класс, который Rhino или экстрактор находит по имени, — правило в `app/proguard-rules.pro` плюс свой тест.
4. **Сеть и 403.** Адрес потока привязан к IP (параметр `ip`) и живёт ≈ 6 ч; VPN и смена адреса между запросом `player` и чтением потока дают 403 (`yu3a.md` §5 п. 10); `User-Agent` и
   `Range` — `yu3a.md` §3.3 и §5 п. 3. Проверить с того же компьютера в браузере, открывается ли `youtube.com` (в РФ YouTube может замедляться).

## Прогоны

| Дата | Сборка | Экстрактор | Где | Итог |
|---|---|---|---|---|
| 2026-10-24 | `work/yu3g2`: `app-github-release.apk` 0.2.3 и `app-fdroid-release.apk` 0.2.3-fdroid (R8, подпись отладочным ключом) | NewPipeExtractor v0.26.5, Rhino 1.8.1 | эмуляторы API 36, 30, 26 (github), API 36 (fdroid) | Я1 латиницей ✅, кириллицей ⏸ (не набрана); Я2 ✅; Я3 ✅, на API 26 один выброс 20–23 с; Я4 не наблюдалось; Я5 не проверено; Я6 не встретился; Я7 — только телефон |
