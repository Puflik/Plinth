# -*- coding: utf-8 -*-
"""Бюджет чата: следит за размером контекста и держит тяжёлые операции
в отдельных чатах.

UserPromptSubmit — при превышении порога подсказывает Claude, что пора
закрывать чат (тихо, пока контекст в норме).
PreToolUse      — запрещает запуск субагентов и тяжёлых скиллов в чате,
                  который уже раздут: их место в свежем чате.

Любая внутренняя ошибка = молчание: хук не должен ломать работу.
"""
import json
import os
import sys

SOFT = 80000      # пора планировать завершение чата
HARD = 120000     # новую задачу здесь не начинать
HEAVY = 60000     # выше этого тяжёлые операции только отдельным чатом
TAIL = 1000000    # сколько байт хвоста транскрипта читать

HEAVY_SKILLS = ('graphify',)


def context_tokens(path):
    size = os.path.getsize(path)
    with open(path, 'rb') as fh:
        if size > TAIL:
            fh.seek(size - TAIL)
        chunk = fh.read()
    for raw in reversed(chunk.split(b'\n')):
        if b'"usage"' not in raw:
            continue
        try:
            d = json.loads(raw.decode('utf-8', 'replace'))
        except ValueError:
            continue
        u = (d.get('message') or {}).get('usage')
        if isinstance(u, dict):
            n = (u.get('input_tokens', 0) + u.get('cache_read_input_tokens', 0)
                 + u.get('cache_creation_input_tokens', 0))
            if n:
                return n
    return 0


def prompt_count(path):
    n = 0
    with open(path, 'rb') as fh:
        for raw in fh:
            if (b'"type":"user"' in raw and b'"tool_use_id"' not in raw
                    and b'"isSidechain":true' not in raw):
                n += 1
    return n


def emit(payload):
    # stdout на Windows по умолчанию в кодировке консоли — пишем байты UTF-8,
    # иначе кириллица приезжает кракозябрами
    sys.stdout.buffer.write(json.dumps(payload, ensure_ascii=False).encode('utf-8'))
    sys.stdout.buffer.flush()
    sys.exit(0)


def main():
    data = json.load(sys.stdin)
    tp = data.get('transcript_path') or ''
    if not tp or not os.path.isfile(tp):
        return
    ctx = context_tokens(tp)
    if not ctx:
        return
    event = data.get('hook_event_name') or ''

    if event == 'UserPromptSubmit':
        if ctx < SOFT:
            return
        turns = prompt_count(tp)
        if ctx >= HARD:
            msg = (u"БЮДЖЕТ ЧАТА ИСЧЕРПАН: контекст ~{0} тыс. токенов, ход {1}. "
                   u"Новую задачу здесь не начинай. Доведи текущую до точки, "
                   u"запиши состояние и решения в docs/decisions.md и скажи "
                   u"пользователю перейти в новый чат — контекст сам не "
                   u"уменьшится, он только растёт.").format(ctx // 1000, turns)
        else:
            msg = (u"Бюджет чата: контекст ~{0} тыс. токенов, ход {1} "
                   u"(порог {2} тыс.). Заверши текущую задачу, вынеси решения "
                   u"в docs/decisions.md и предложи пользователю новый чат "
                   u"под следующую.").format(ctx // 1000, turns, HARD // 1000)
        emit({"hookSpecificOutput": {
            "hookEventName": "UserPromptSubmit",
            "additionalContext": msg,
        }})
        return

    if event == 'PreToolUse':
        tool = data.get('tool_name') or ''
        ti = data.get('tool_input') or {}
        what = None
        if tool in ('Task', 'Agent'):
            what = u'субагент «{0}»'.format(ti.get('description') or tool)
        elif tool == 'Skill' and str(ti.get('skill', '')).lower().endswith(HEAVY_SKILLS):
            what = u'скилл /{0}'.format(ti.get('skill'))
        if not what or ctx < HEAVY:
            return
        emit({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": (
                u"Тяжёлая операция ({0}) в чате с контекстом ~{1} тыс. токенов. "
                u"Такие операции запускаются только в свежем чате: субагенты и "
                u"семантическая экстракция добавляют сотни тысяч токенов к "
                u"общему счёту и выбивают лимит сессии. "
                u"Скажи пользователю открыть новый чат в этой папке и запустить "
                u"её там первым сообщением."
            ).format(what, ctx // 1000),
        }})
        return


try:
    main()
except Exception:
    pass
