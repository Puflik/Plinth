# Работа на ноутбуке Linux Mint

Фактическая подготовка руководства: **2026-11-06**, сверка ноутбука **2026-11-07**.
Codex на ноутбуке установлен.
Это точка входа для переноса; актуальное состояние — [CODEX.md](CODEX.md),
правила — [README.md](README.md), роли — [AGENT_ROLES.md](AGENT_ROLES.md).

1. Клонируйте последний main `https://github.com/Puflik/Plinth.git`, выполните
   fetch и проверьте status. Настройте доступ GitHub и существующее авторство
   на ноутбуке самостоятельно; секреты и приватные ключи с Windows не копируются.
   Учётные записи автора различаются: GitHub — `puflik`, GitVerse — `pufli4`;
   почты тоже разные, конкретные адреса ещё не указаны. Не переносите настройки
   авторства и авторизацию одного сервиса на другой.
2. Отдельное локальное дополнение содержит семь проектных ECC-навыков,
   graphify skill, tools/graphify_update.py, .graphifyignore и устаревший граф.
   Проверьте ZIP по внешней SHA256, распакуйте в новый каталог, прочитайте
   README_RU.md, выполните dry-run `python3 restore.py --repo /путь/Plinth`,
   затем ту же команду с `--apply`. Отличающиеся файлы не перезаписываются.
   Глобальный skill требует `--with-graphify-skill`; хуки не активируются.
3. Откройте существующий каталог проекта в Codex, прочитайте AGENTS.md и
   CODEX.md. Проектная .codex/config.toml применяется после вашего решения
   доверять проекту ([документация](https://learn.chatgpt.com/docs/config-file/config-basic)).
   Локальные .agents/skills и глобальные ~/.agents/skills — стандартные
   [пути навыков](https://learn.chatgpt.com/docs/build-skills); клиентские system
   skills и домашние профили копировать не нужно.
4. Настройте JDK/SDK/NDK/Rust по актуальному [BUILD.md](../BUILD.md).
   Создайте local.properties со своим Linux SDK-путём; Windows-файл не переносите.
   Запускайте wrapper через `bash ./gradlew`: Git хранит его без executable bit.
   Выбирайте Linux-пути явно, например ~/src/Plinth и ~/src/Plinth-yu5f-ready;
   прежние C:\claude\... служат историческими примерами.
5. **yu5f-ready принята 2026-11-13**: независимые ревью PASS, CI
   `43258909999` на `85d8de7` SUCCESS. Линейный перенос в main: production
   `c8b01e0`, итоговый protected baseline `f3711cf`, annotation `1293542`.
   Код/тесты совпадают с проверенной рабочей веткой. JVM 66/66 и AndroidTest
   compile ранее PASS; после механических правок полный ktlint/detekt и
   свежие readiness JVM 12/12 PASS. Старый scoped init не использовать.
   CI main `43358730931` на `419ae48` также SUCCESS. Завершённые
   `work/yu5f-ready` и `/home/puflik/src/Plinth-yu5f-ready` убраны 14 ноября.
6. Следующая задача — `yu5f`; Linux worktree `/home/puflik/src/Plinth-yu5f`,
   ветка `work/yu5f`, проверяемый handoff `362ab9e`, исправление VM `f812ee8`. 15 ноября Astra xhigh принял
   protected baseline `92c78bd`: 12 путей, 23 readiness guards и восемь
   migrations, B1/B2 закрыты. Полные style PASS; до production compile RED9
   только по обязательному readiness-параметру. Sol high получил ограниченный
   этап `SearchViewModel.kt` и завершил его: свежие 78 JVM tests, полные
   ktlint/detekt и AndroidTest compile PASS, защищённые тесты сохранены.
   Вечернее Astra review требует VM-B1: retry до актуального context после
   grace. CI-C1 требует миграции старого assertion Yu4g (fixture уже разделяет
   providerSearches/bulk searches). Sol подготовил candidate `b52b256`:
   85 JVM tests, только три новых VM-B1 assertion RED, прежние 78+4 PASS.
   Полные style и обе Kotlin test compilations PASS после одной коррекции
   переносов; production/старые 12 protected неизменны, защита расширена до14.
   Независимый Astra xhigh review PASS: `b52b256` принят как baseline14,
   отчёт `yu5f-vmb1-tests-review-20261115.md`. Следующий этап — Sol high VM
   correction только SearchViewModel/отчёт выполнена17ноября: `f812ee8`,
   handoff `362ab9e`. Свежие85/85, полные style/обе test compilations PASS,
   независимое Astra review PASS; raw query дополнительно прослежен в коде.
   Полный CI44116043234 на точном `362ab9e` SUCCESS, подтверждено18ноября;
   следующий этап — UI/emulator acceptance перед слиянием всей задачи.
   После первичного этапа окна 86%/69%; вечером primary сбросился, но неделя
   достигла 77%, расход дня ≈20 пунктов. Новый агент превысит суточную норму.
   Позднее автор разрешил расходовать остаток: на входе 60%/81%, перед Sol
   63%/82% использовано. Завершён только планировщик VM-B1/CI-C1, без
   production; итоговый handoff `7a903ee` опубликован. В конце окна 89%/86%,
   следующий агент выше обоих порогов.
   Перед продолжением проверить окна и пороги 65%/85%.
   Автор разрешил только одно ревью сверх weekly-порога: оно завершено,
   окна перед ним 11%/88%, после него 39%/92%. Исполнитель не запускался;
   reset-кредит не использован. Main `0889a99` CI `43723483054` SUCCESS.
   Сверка 17 ноября: main `2a067fe` CI `43749054749` SUCCESS; рабочий `7534c77`
   CI `43749054774` — только три ожидаемых VM-B1 failures из1114 Github tests,
   1110 PASS и1skip, Yu4g4/4 PASS. Fdroid XML отсутствуют. Отчёт
   `yu5f-ci-audit-20261117.md`; ZIP скачан через прямой IPv4 без proxy.
   API после временных ошибок 15%/97% использовано; новых агентов не запускали.
   Вечером окна обновились: перед Sol4%/1%, перед review37%/6%, после66%/10%.
   Heartbeat `plinth` ранее продолжал этот чат с проверкой окон/суточной нормы.
   18ноября автор отменил таймер; автоматизация удалена, не восстанавливать.
   Read-only preflight UI17ноября: emulator/system-images в SDK отсутствуют,
   `/dev/kvm` скрыт в sandbox, свободно57GiB; SDK tools/adb доступны.
   Проверка18ноября с require_escalated показала настоящий `/dev/kvm`
   (root:kvm, ACL), CPU vmx и загруженные kvm/kvm_intel. Не создавать device
   заново: для проверки доступа/запуска эмулятора нужна корректная среда вне
   sandbox. Телефон не использовался; создание AVD и baseline ещё впереди.
   Terra setup остановился на зависшем bootstrap нового Android CLI (`8d496c2`).
   Отдельный Luna-этап `3e9db35` восстановил CLI1.1.16919734 в
   `/home/puflik/Android/Sdk/cmdline-tools/android-cli-20261118/android-cli`;
   wrapper использовать с per-invocation ANDROID_CLI_BIN этим путём.
   Version/help/local list PASS; детали/hash в `android-cli-bootstrap-20261118.md`.
   Отдельный SDK этап `06c2357` завершён: emulator37.2.13, образ API35 Google APIs
   x86_64 revision9.0.0, NDK28.2.13676358/r28c. Metadata/source.properties,
   emulator version и accel-check PASS; KVM usable. Подробности в
   `android-sdk-packages-20261118.md`. Cargo-ndk уже установлен; Rust1.98.1
   содержит aarch64/armv7/i686/x86_64 Android targets и host x86_64 Linux.
   При ручном продолжении создан AVD `Plinth_yu5f_api35` в
   `/tmp/plinth-yu5f-ui-baseline-20261118/avd`, serial `emulator-5560`, adb5038;
   qemu=1 и boot_completed=1 подтверждены. Для сборки эмулятор остановлен
   из-за доступных7.6GiB памяти. Все4 native ABI собраны, debug APK создан;
   общий успех debug+AndroidTest build и UI runtime ещё не подтверждены.
   Отчёт `91dcda0`: сборка прервана по команде автора, test APK отсутствует;
   созданные эмулятор и adb5038 остановлены, чужой5037 сохранён.
   Логи — `/tmp/plinth-yu5f-ui-baseline-20261118`. Перед установкой снова
   проверить serial/qemu; чужой adb5037 не останавливать. Автор ограничил
   завершение пятью минутами; новые тесты не запускались.
   Не запускать зависающий bootstrap latest/bin/android.
   После подготовки окна77%/28%, расход18ноября12пунктов недели; следующий
   heartbeat был перенесён на19ноября00:02+05. Затем автор отменил таймер
   и попросил продолжить: при11%/32% начат ограниченный Terra runtime baseline.
   19ноября Luna завершил оба APK (`bdf9431`), assemble SUCCESS за1м14с;
   подписи/zipalign/native4/runner target проверены, хеши в задаче yu5f.
   Terra обнаружил отсутствие временного AVD (`e180284`), runtime не запущен.
   Luna создал постоянный `/home/puflik/.android/avd/Plinth_yu5f_api35.avd`
   (`3728193`), profile pixel_2/API35. Sol `0e922f8` подтвердил headless
   boot/qemu и оба install Success с отдельным adb5038. Runner завершился
   startup ANR через29.217s, фактически0 начатых/завершённых tests;
   UI не принят, main-thread stack отсутствует. Следующий этап — диагностика
   app startup, без предположения о виновности SearchScreen/native.
   Emulator/adb5038 остановлены, cleanup подтверждён на host. Логи сохранены
   в app/build/yu5f-runtime-20261119 и дополнительно в локальном архиве main
   artifacts/yu5f-runtime-20261119.tar.gz. Окна74%/51%, дневные≈10пунктов
   потрачены; таймер только проверит возможность продолжения без переноса.
   Автор потребовал делегировать и простые задачи; APK этап выполнен Luna.
   По новому запросу создан ровно один heartbeat `plinth` на19ноября05:22:58+05,
   COUNT=1; удалить при срабатывании, без повторов и переносов.
   В main эта VM не перенесена.
7. 15 ноября подготовлен APK из CI `43680512364`, snapshot `808b22b`:
   `/home/puflik/src/Plinth/artifacts/Plinth-YouTube-2026-11-15-808b22b.apk`.
   Размер 35 083 627 байт; SHA-256
   `7a0934ff7b98c2376a6727098530bbaa89f20f68dd5b7460ee5bc26bbd5afdac`.
   `apksigner verify` PASS (v2), `zipalign -c -P 16 4` PASS; package
   `io.github.puflik.plinth.debug`, minSdk 26, версия `0.2.3-debug`, четыре ABI.
   Native ядра и YouTube/NewPipe classes в APK подтверждены. Файл игнорируется
   Git; для скачивания из CI см. `docs/testing/test-apk.md`. Телефон, установка
   и живое воспроизведение не проверялись. Выпуск/тег не создавались.

Граф построен на 7c5192a (2026-10-19), сильно отстаёт; Windows CLI сломан.
На Linux 2026-11-07 `graphify query` выполнен успешно, установлен graphify 0.9.103;
он предупреждает о старой схеме ID и skill 0.8.49. Перестроение не выполнялось.
Перенос снимка не означает настройку Linux runtime. Старый .graphify_python
не переносится. Updater требует graphify 0.9+ и ресурс extraction-spec.md
по историческому ~/.claude/skills/graphify/references/ пути; явное восстановление
global skill создаёт только этот совместимый ресурс дополнительно к .agents.
Никогда не запускайте `graphify update .`, включая --force: только локальный
tools/graphify_update.py по его docstring после отдельной проверки среды.

Старые хуки Claude/Codex не доказаны как защита; чтения ограничивайте вручную.
Перед агентами проверьте лимиты и выберите экономичную роль по сложности;
одновременно один агент, реальная модель/effort записывается в задачу.
Защищённые тесты и разрешённые файлы соблюдаются на любой машине.
Git internals, кеши, SDK, секреты, переписки и данные телефона не переносятся.
Новый пользовательский чат автоматически не создаётся; продолжение — через
актуальные документы проекта.

## Проверка Linux 2026-11-07

- Основной каталог `/home/puflik/src/Plinth`; задача — соседний
  `/home/puflik/src/Plinth-yu5f-ready`, ветка `work/yu5f-ready`, HEAD `6fa3e6b`.
- JDK 21.0.13: `/home/puflik/.local/opt/jdk21`. Rust 1.98.1 установлен;
  для этой задачи задавать `RUSTUP_TOOLCHAIN=1.98.1`, default Rust не менять.
- Gradle 9.7.1 загружен штатным wrapper; `bash ./gradlew --version` завершился
  успешно. Кэш — `/home/puflik/.gradle`. Лог:
  `/tmp/plinth-yu5f-ready-linux-gradle-version.log`.
- Command-line tools 23.0 установлены в
  `/home/puflik/Android/Sdk/cmdline-tools/latest`. Архив
  `commandlinetools-linux-16111833_latest.zip` проверен по SHA1 из официального
  `repository2-3.xml`: `e025545c62a8e64c7559119566a569fb1dec5f60`.
- Старый `sdkmanager` запускает загрузчик Android CLI; первая установка SDK
  остановлена `timeout 300`, код 124, на `Downloading Android CLI...`.
  Прямой официальный бинарник загружен через IPv4 и установлен рядом как
  `bin/android-cli`; проверена версия `1.1.16717390`. Повторная установка
  `platforms/android-37.0` и `build-tools/37.0.0` завершилась успешно, код 0;
  оба каталога созданы. Команда установки использовала `--no-metrics`.
  Логи: `/tmp/plinth-yu5f-ready-linux-sdk-install.log` и
  `/tmp/plinth-yu5f-ready-linux-sdk-retry.log`. Пакет 37.0 найден в официальном
  каталоге; прежняя веб-страница preview не определяет актуальное наличие SDK.
- Ограниченный запуск прошёл `uniffiKotlin` и `compileGithubDebugKotlin`.
  `compileGithubDebugUnitTestKotlin` завершился exit 1 за 17m34s: в новом
  `Yu5fOnlineReadinessTest.kt` отсутствуют заранее объявленные readiness API
  `OnlineConfiguration`, `OnlineReadiness`, `OnlineReady`, `awaitConfiguration`
  и возникают связанные ошибки вывода типов. Это compile RED, не runtime тестов;
  production и защищённые тесты неизменны. Scoped ktlint сегодня не запускался.
  Лог: `/tmp/plinth-yu5f-ready-linux-compile.log`.
- Gradle дополнительно установил Build-Tools 36.0.0 и Platform-Tools 37.0.1.
  Временные ошибки SSL при скачивании crates.io преодолены штатными повторами;
  host Rust build и генерация завершились. NDK/APK/устройство не использовались.

Окружение и фактически выполненная ограниченная команда:

```bash
export JAVA_HOME=/home/puflik/.local/opt/jdk21
export ANDROID_HOME=/home/puflik/Android/Sdk
export GRADLE_USER_HOME=/home/puflik/.gradle
export RUSTUP_TOOLCHAIN=1.98.1
export JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true
export PATH="$JAVA_HOME/bin:$PATH"
# Из /home/puflik/src/Plinth-yu5f-ready, без старого Windows init:
bash ./gradlew --no-daemon -x :app:cargoNdkBuild :app:compileGithubDebugUnitTestKotlin --rerun
```

Не исключать `uniffiKotlin`: используются настоящие биндинги текущего checkout.
Не устанавливать NDK и не запускать APK/устройство для этого шага. Compile RED
подтверждён; последующие форматирование, scoped ktlint и независимое
Astra-review выполнены 8 ноября; выявленный B1 закрыт доработкой и повторным
ревью 9 ноября. Актуальный baseline указан в пункте 5 выше.
7 ноября нового агента не запускали из-за суточной нормы; в новой сессии
проверять фактические окна заново.

Официальные источники: [каталог SDK](https://dl.google.com/android/repository/repository2-3.xml),
[Android CLI](https://developer.android.com/tools/agents/android-cli),
[бинарник Linux](https://dl.google.com/android/cli/latest/linux_x86_64/android-cli).

## Проверка стиля: исправление памятки 2026-11-12

Прежний scoped init больше не использовать. Его успешные запуски 8–9 ноября
не подтверждают проверку всех заявленных файлов: CI на `1ded175` и `bd04e34`
упал в `ktlintTestSourceSetCheck`. Журнал run `43063804434` указывает
`Yu5fOnlineReadinessTest.kt:318:110`, `Expected newline before '.'`.
12 ноября полный локальный `ktlintCheck` воспроизвёл ту же единственную ошибку
(1m11s, 19 executed/1 from cache/10 up-to-date). Семантические JVM-проверки
и независимый review этим не отменяются, но прежний scoped PASS не является
доказательством чистого стиля. Точная причина пропуска в старом init не исследована.

Для этой задачи использовать штатную команду, совпадающую с CI, с окружением
из предыдущего раздела; NDK, устройство и компиляция приложения ей не нужны:

```bash
bash ./gradlew --no-daemon ktlintCheck
```

Вывод сохранять в файл, проверять exit code и конкретные выполненные задачи.
Форматирование защищённого теста выполняет планировщик отдельным коммитом;
координатор принимает его только после проверки неизменности утверждений.
