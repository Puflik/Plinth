# -*- coding: utf-8 -*-
"""PreToolUse-предохранитель: запрещает втягивать крупные документы целиком.

Срабатывает на Read без limit/offset и на `cat` в Bash, если файл тяжелее
MAX_TOKENS. Отвечает deny с рецептом, как прочитать нужный кусок дёшево.
Любая внутренняя ошибка = молчание (allow): хук не должен ломать работу.
"""
import json
import os
import re
import sys

MAX_TOKENS = 9000          # ~19 800 знаков кириллицы
CHARS_PER_TOKEN = 2.2
WATCHED_EXT = ('.md', '.txt', '.rst')


def est_tokens(path):
    try:
        with open(path, encoding='utf-8', errors='replace') as fh:
            return int(len(fh.read()) / CHARS_PER_TOKEN)
    except OSError:
        return 0


def deny(reason):
    # только байты UTF-8, см. chat-budget.py
    sys.stdout.buffer.write(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }, ensure_ascii=False).encode('utf-8'))
    sys.stdout.buffer.flush()
    sys.exit(0)


def recipe(path, tokens):
    name = os.path.basename(path)
    return (
        u"{0} — примерно {1} токенов, читать целиком нельзя: это раздувает "
        u"контекст на весь оставшийся чат.\n"
        u"Как получить нужное дешевле:\n"
        u"1. graphify query \"<вопрос>\" — подграф вместо текста;\n"
        u"2. grep -n '^#' {2} — оглавление, затем Read с offset/limit "
        u"или sed -n 'A,Bp' на нужный диапазон;\n"
        u"3. если файл действительно нужен целиком — отдельным чатом, "
        u"не этим."
    ).format(name, tokens, name)


def main():
    data = json.load(sys.stdin)
    tool = data.get('tool_name') or ''
    ti = data.get('tool_input') or {}
    root = os.environ.get('CLAUDE_PROJECT_DIR') or os.getcwd()

    if tool == 'Read':
        path = ti.get('file_path') or ''
        if not path.lower().endswith(WATCHED_EXT):
            return
        if ti.get('limit') or ti.get('offset'):
            return
        if not os.path.isfile(path):
            return
        tokens = est_tokens(path)
        if tokens > MAX_TOKENS:
            deny(recipe(path, tokens))
        return

    if tool == 'Bash':
        cmd = ti.get('command') or ''
        if not re.search(r'(^|[|;&]\s*)cat\s', cmd):
            return
        # срез уже ограничен — пропускаем
        if re.search(r'\b(head|tail|sed\s+-n|grep|awk|wc)\b', cmd):
            return
        # Запись — не чтение: `cat >> decisions.md <<'EOF'` дописывает файл,
        # а в теле heredoc имена документов — просто текст. Выкидываем тела
        # heredoc и цели перенаправления вывода, остаётся то, что cat читает.
        cmd = re.sub(r"<<-?\s*(['\"]?)(\w+)\1.*?\n.*?^\2[ \t]*$", '', cmd,
                     flags=re.S | re.M)
        cmd = re.sub(r'\d*>>?\s*\S+', '', cmd)
        for token in re.findall(r'[\w./\-]+\.(?:md|txt|rst)', cmd):
            cand = token if os.path.isabs(token) else os.path.join(root, token)
            if not os.path.isfile(cand):
                continue
            tokens = est_tokens(cand)
            if tokens > MAX_TOKENS:
                deny(recipe(cand, tokens))
        return


try:
    main()
except Exception:
    pass
