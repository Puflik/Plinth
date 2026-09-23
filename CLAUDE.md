## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## Крупные документы

`plan.md` — только точка входа и оглавление. Текст плана разложен по главам в
`docs/plan/` (27 файлов, каждый 1–8 тыс. токенов), правки вносятся туда, а не в
`plan.md`.

Ни один документ не читается целиком, если он тяжелее ~9 тыс. токенов — это
`tasks*.md` и любые будущие большие файлы. Порядок: `graphify query "<вопрос>"`
→ оглавление через `grep -n "^#"` → чтение нужного диапазона с `offset`/`limit`
или `sed -n 'A,Bp'`. Хук `.claude/hooks/guard-big-docs.py` запрещает полное
чтение таких файлов и выдаёт рецепт. Причина запрета: один такой Read держит
контекст раздутым до конца чата и сжигает лимит сессии.

## Бюджет чата и тяжёлые операции

Контекст внутри чата только растёт — сам он не уменьшается. Отсюда правила:

- **Тяжёлые операции выполняются только в отдельном чате, первым сообщением:**
  `/graphify` с семантической экстракцией, запуск субагентов (`Task`), разовое
  чтение крупного документа целиком, массовый рефакторинг. Одна такая операция
  добавляет сотни тысяч токенов к счёту и выбивает лимит сессии, а в рабочем
  чате этот вес остаётся до самого конца.
- **Новый чат при смене задачи** и ориентировочно каждые 30–50 сообщений.
  Перед переходом состояние и решения уходят в `docs/decisions.md`: переписка
  не переживает закрытие чата, файл — переживает.
- **Claude сам предупреждает пользователя**, что пора в новый чат, не дожидаясь
  вопроса. Пороги: ~120 тыс. токенов контекста — предупреждение, ~180 тыс. — стоп,
  новую задачу здесь не начинать.
- **Конец чата — с готовыми промптами.** Объявляя «Контекст этого чата
  исчерпан», Claude тут же выдаёт промпты для нового чата: по одному на задачу,
  каждый отдельным блоком кода, готовым к копированию. Промпт самодостаточен —
  что уже сделано, что прочитать (`CLAUDE.md`, `docs/decisions.md`, нужный
  диапазон `tasks*.md`), что именно делать дальше. Переписка не переживает
  закрытие чата: без такого промпта следующий чат переизобретает уже принятое.
- Следит за этим хук `.claude/hooks/chat-budget.py`: считает реальный размер
  контекста по транскрипту и подсказывает на `UserPromptSubmit`; тяжёлые
  операции пропускает только в первых двух ходах чата — независимо от
  размера контекста.

## Recording answers & decisions (persist to the graph)

When the user answers a questionnaire, states a requirement, or makes a project
decision, do NOT let it live only in chat. Persist it:

1. Append it to `docs/decisions.md` under a dated `## YYYY-MM-DD` heading, as a
   concise bullet: the topic/question and the user's answer/decision.
2. Fold it into the knowledge graph by running the graphify skill in update mode:
   `/graphify . --update` (semantically extracts the changed doc into graph.json).
   You may batch several answers in one session and run the update once at the end.
3. Confirm to the user what was recorded and that the graph was updated.

`docs/decisions.md` is git-tracked (unlike graphify-out/), so answers persist in the
repo and become queryable via `graphify query`.
