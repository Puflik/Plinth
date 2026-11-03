## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- Do NOT run `graphify update .` (with or without `--force`). Here it builds long
  IDs and refuses to overwrite the merged graph (fewer nodes); `--force` would
  drop the document nodes. Code edits reach the graph at the end of a wave via
  `tools/graphify_update.py` (see «Recording answers & decisions»); until then the
  graph may lag behind the code — when it matters, check the code itself.

## Крупные документы

`plan.md` — только точка входа и оглавление. Текст плана разложен по главам в
`docs/plan/` (27 файлов, каждый 1–8 тыс. токенов), правки вносятся туда, а не в
`plan.md`.

Ни один документ не читается целиком, если он тяжелее ~9 тыс. токенов — это
`tasks*.md` и любые будущие большие файлы. Порядок: `graphify query "<вопрос>"`
→ оглавление через `grep -n "^#"` → чтение нужного диапазона с `offset`/`limit`
или `sed -n 'A,Bp'`. Хук `.codex/hooks/guard-big-docs.py` предназначен для запрета
полного чтения; его перенос в Codex ещё не проверен, ограничение соблюдать вручную.
Причина запрета: один такой Read держит
контекст раздутым до конца чата и сжигает лимит сессии.

## Бюджет чата и тяжёлые операции

Контекст внутри чата только растёт; уменьшает его только `/compact`, который
набирает автор. Отсюда правила:

- **Тяжёлые операции выполняются только в отдельном чате, первым сообщением:**
  `/graphify` с семантической экстракцией, запуск субагентов (`Task`), разовое
  чтение крупного документа целиком, массовый рефакторинг. Одна такая операция
  добавляет сотни тысяч токенов к счёту и выбивает лимит сессии, а в рабочем
  чате этот вес остаётся до сжатия.
- **Пятичасовое окно** (автор, 2026-10-16, после обрыва Р9): перед тяжёлой
  операцией — `get_usage_limits` в Codex (5-часовое и недельное окна).
  Тариф Pro: пятеро ревьюеров Opus параллельно выбрали окно за 14 минут — у
  каждого контекст дорос до 250–325 тыс., а каждый вызов пересылает его заново.
  Поэтому агенты — по одному, перед каждым — проверка окна; выше ~65 %
  5-часового или ~85 % недельного новый не запускать. Агент пишет находки в файл
  по ходу, а не только в итоговый ответ: обрыв по лимиту не съедает работу.
  Охват агента — узкий, с ориентиром по числу вызовов.
- **Недельное окно — ровно на всю неделю** (автор, 2026-10-20: «нужно размазать
  токены для коммитов на всю неделю, а ты уже на 2 день всё тратишь»). Суточная
  норма — остаток недельного окна, делённый на дни до обнуления (суббота, 10:00
  UTC в прежней среде; в Codex — фактический `resetsAt`). Перед новой сессией —
  `get_usage_limits`. Сессия, которая выведет расход за
  норму дня, ждёт завтра. Ориентир цены: планировщик или исполнитель Sonnet max —
  4–8 % недели. Когда контекст Opus большой, его собственные ходы тоже дороги:
  каждая заметка сессии будит его и пересылает весь контекст. Поэтому `/compact`
  предлагать автору раньше порога 600 тыс.
- **Каждый день — коммит в `main`** (автор, 2026-10-23: «гит пишет, что за
  21,22,23 числа коммитов нет»). График вкладов GitHub считает только основную
  ветку и ставит коммит по дате автора. Коммиты в `work/*` не видны, пока ветка не
  слита. Сутки автора кончаются в 00:00 +05:00, то есть в 19:00 UTC. До этого в
  `main` должен лечь хотя бы один коммит с настоящей работой: слияние, решения,
  документы. Задачи дробить так, чтобы слияния шли каждый день. Пустых коммитов не делать.
  Пропущенный вчерашний день можно закрыть коммитом задним числом (автор,
  2026-10-27): обе даты — вчерашние, в коммите — настоящая работа; дальше
  вчерашнего дня — нет.
  Разовое исключение автора от 2026-11-04: коммиты с настоящими изменениями
  за 2026-10-31 и 2026-11-01/02/03; обе даты Git — указанные. Фактическое
  решение записывается 2026-11-04. Постоянный запрет дальше вчера сохраняется.
- **Большой контекст — `/compact`, а не новый чат** (автор, 2026-10-12): работа
  продолжается здесь же, по сводке. Перед сжатием состояние и решения уходят в
  `docs/decisions.md`: сводка теряет подробности, файл — нет. Промпт на
  продолжение не пишется. Новый чат Codex сам не предлагает — ни по смене
  задачи (автор, 2026-09-24: «пока 600-700 не будет — не думай переходить в
  другой чат»), ни по размеру; он нужен только под тяжёлую операцию, и тогда
  первое сообщение там — одна строка: что делать и где это записано.
- **Codex сам предупреждает пользователя**, что пора `/compact`, не дожидаясь
  вопроса. Прежние пороги Claude — 600/700 тыс.; в Codex сначала учитывать
  фактическое окно выбранной модели, а не ждать исторического числа.
- `.codex/hooks/chat-budget.py` ещё разбирает транскрипт Claude. Его корректная
  работа с Codex не подтверждена; не заявлять, что он измерил текущий контекст.
  Размер чтения и расход окон контролировать вручную, состояние сохранять в файлы.

## Recording answers & decisions (persist to the graph)

When the user answers a questionnaire, states a requirement, or makes a project
decision, do NOT let it live only in chat. Persist it:

1. Append it to `docs/decisions.md` under a dated `## YYYY-MM-DD` heading, as a
   concise bullet: the topic/question and the user's answer/decision.
2. The knowledge graph is updated at the end of a wave and before the gap audit
   (G1 in `tasks.md`), not after every decision: run `tools/graphify_update.py`
   (`prepare` → extraction → `semantic` → `merge` → `cluster` → `finish`; see its
   docstring). A document that was only appended to since the last build is
   extracted by its new lines alone, which is cheap. Changed code goes through
   the same run: `prepare` extracts its AST, no tokens. Code edits between
   waves need no graph step — `graphify update .` does not work on this graph.
3. Confirm to the user what was recorded; say when the graph is behind.

`docs/decisions.md` is git-tracked (unlike graphify-out/), so answers persist in the
repo and become queryable via `graphify query`.

## Исследователь и исполнители в Codex

С 2026-11-04 по просьбе автора сохранено разделение прежних Opus/Sonnet/Haiku:
**Astra** исследует и ревьюит; **Sol** реализует логику и Rust; **Terra** делает
UI, проверки экранов и локальные правки; **Luna** выполняет простые задачи и
извлечение для графа. Точные model IDs и effort — `docs/work/AGENT_ROLES.md`.
Исследование и ревью — Astra xhigh; сохранность данных — Astra max до кода
и после. План — Sol xhigh; код — Sol high или Terra high; простое — Luna medium,
извлечение графа — Luna high. Сложному исполнению можно назначить xhigh в задаче.
Настройки — `.codex/config.toml`, `.codex/agents/*.toml`. В `spawn_agent` без
именованных ролей передавать model/effort явно и `fork_turns="none"`.
Перед каждым агентом — лимиты; одновременно один агент. Не создавать новый
пользовательский чат без просьбы автора. Процесс и шаблон — `docs/work/README.md`,
`docs/work/TEMPLATE.md`; история Claude — `docs/work/CLAUDE-WORKFLOW.md`.

- **Задача — файл `docs/work/<id>.md`.** Пишет планировщик; всё, что нужно
  исполнителю, — в файле, переписка не передаётся.
- **Ветка `work/<id>`, ворктри `C:\claude\Plinth-<id>`.** Первый коммит —
  планировщик: файл задачи и тесты в отдельных файлах, падающие на текущем
  коде. Задачам — непересекающиеся списки файлов (следит координатор).
  CI гоняет пуши в `work/**`.
- **Исполнитель (Sol/Terra/Luna)** работает только в своём ворктри и ветке, коммитит и
  пушит её; в `main` не пишет, не сливает, не перебазирует. Защищённые тесты
  (список в файле задачи) не меняет, не удаляет и не глушит — ни
  `#[ignore]`/`@Ignore`, ни `#[allow]`/`@Suppress`, ни ослабленных утверждений.
  Файлы вне списка «Менять», `CHANGELOG.md`, `tasks*.md`, `docs/decisions.md`,
  `AGENTS.md`, план и зависимости не трогает. Сомнение в решении или тесте —
  стоп и вопрос в «Отчёте исполнителя», а не догадка.
- **Лимит попыток:** проверки упали — одна попытка исправить; упали снова —
  стоп, отчёт, коммит, пуш.
- **Независимое ревью — Astra, слияние — координатор:** защищённые тесты не тронуты (`git diff <коммит
  тестов> origin/work/<id> -- <тесты>` пуст), CI зелёный, инварианты держатся.
  Затем перебазирование на `main`, перемотка, CHANGELOG/tasks/decisions,
  удаление ветки и ворктри.

## Продолжение в Codex

Рабочая памятка после знакомства с проектом 2026-11-04 —
`docs/work/CODEX.md`: точка продолжения, правила из памяти Claude,
местные особенности сборок и непроверенные части переноса инструментов.
Перед продолжением прочитать её; актуальность статуса сверять с Git и
последними решениями. Наличие `.codex/hooks.json` не доказывает работу
перенесённых предохранителей: ограничивать чтение больших файлов вручную.
