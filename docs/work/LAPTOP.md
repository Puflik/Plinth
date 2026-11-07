# Работа на ноутбуке Linux Mint

Фактическая подготовка руководства: **2026-11-06**, сверка ноутбука **2026-11-07**.
Codex на ноутбуке установлен.
Это точка входа для переноса; актуальное состояние — [CODEX.md](CODEX.md),
правила — [README.md](README.md), роли — [AGENT_ROLES.md](AGENT_ROLES.md).

1. Клонируйте последний main `https://github.com/Puflik/Plinth.git`, выполните
   fetch и проверьте status. Настройте доступ GitHub и существующее авторство
   на ноутбуке самостоятельно; секреты и приватные ключи с Windows не копируются.
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
5. План и тесты **yu5f-ready** уже опубликованы в `origin/work/yu5f-ready`
   (`6fa3e6b`, опорные тесты `9e00868`); подтверждённого RED и Astra-review пока нет.
   Linux worktree восстановлен в `/home/puflik/src/Plinth-yu5f-ready`.
   Следующий шаг — подготовка JVM-запуска; yu5f production заблокирована.
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
