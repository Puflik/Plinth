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
5. Реализация **yu5f-ready** опубликована в `origin/work/yu5f-ready`
   (production `1ded175`, сохранённое ревью `bd04e34`); baseline — `005ba5e`.
   JVM 66/66, AndroidTest compile и scoped ktlint PASS; независимое ревью
   реализации PASS. Native runtime/телефон не проверены. На 11 ноября
   подтверждение CI `42517435908` и интеграция ещё ожидаются.
   Linux worktree — `/home/puflik/src/Plinth-yu5f-ready`.
   Исходный handoff находится в
   `origin/work/yu5f:docs/work/yu5f.md`. Ветка опубликована, исходный HEAD
   365fbb3aee3eb405b15ebd9968bc97867e503554; baseline защищённых тестов
   cbe6934fcd82f203622a490031e9a0e054272fee. Сверьте с fetch перед работой.

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

## Scoped ktlint после проверки 2026-11-08

Проверенный Astra init сохранён ниже, поскольку `/tmp` не является постоянным
хранилищем. Он проверяет ровно два подготовительных теста и запрещает задачи
компиляции/тестов приложения и Rust. Скопируйте блок во временный init; запуск
из worktree задачи с Linux-окружением выше:

```bash
bash ./gradlew --no-daemon -I /tmp/plinth-ready-ktlint.init.gradle :app:plinthReadyScopedLint --rerun-tasks
```

Результат 8 ноября: PASS, 11 tasks executed, 2m10s. Runtime тестов этим не проверен.
Содержимое `/tmp/plinth-ready-ktlint.init.gradle`:

```groovy
gradle.projectsEvaluated {
    def app = gradle.rootProject.findProject(':app')
    if (app == null) return
    def isCheck = { task ->
        def klass = task.getClass()
        while (klass != null) {
            if (klass.name == 'org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask') return true
            klass = klass.superclass
        }
        false
    }
    def selected = app.tasks.findAll { isCheck(it) }.sort { it.name }.first()
    def expected = [
        app.file('src/test/kotlin/io/github/puflik/plinth/online/Yu5fOnlineReadinessTest.kt'),
        app.file('src/test/kotlin/io/github/puflik/plinth/online/Yu5fCoreReadinessContractTest.kt')
    ] as Set
    selected.setSource(app.files(expected))
    selected.doFirst {
        assert selected.source.files == expected
        println('SCOPED_KTLINT_TASK=' + selected.path)
        println('SCOPED_KTLINT_FILES=' + selected.source.files*.name.sort())
    }
    app.tasks.register('plinthReadyScopedLint') { dependsOn(selected) }
}
gradle.taskGraph.whenReady { graph ->
    def forbidden = graph.allTasks.findAll {
        it.project.path == ':app' && (it.name.startsWith('compile') || it.name.startsWith('test') || it.name.contains('cargo') || it.name.contains('uniffi'))
    }
    assert forbidden.empty : 'Out-of-scope tasks: ' + forbidden*.path
}
```
