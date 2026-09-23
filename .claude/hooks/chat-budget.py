# -*- coding: utf-8 -*-
"""Бюджет чата: следит за размером контекста и держит тяжёлые операции
в отдельных чатах.

UserPromptSubmit — при превышении порога подсказывает Claude, что пора
закрывать чат (тихо, пока контекст в норме).
PreToolUse      — разрешает субагентов и тяжёлые скиллы только в первых
                  ходах чата. От размера контекста это не зависит: субагент
                  стоит одинаково в любом чате, а вот его результат остаётся
                  в рабочем чате до конца — поэтому место таким операциям
                  в начале свежего чата.

Любая внутренняя ошибка = молчание: хук не должен ломать работу.
"""
import json
import os
import sys

SOFT = 120000     # пора планировать завершение чата
HARD = 180000     # новую задачу здесь не начинать
HEAVY_TURNS = 2   # тяжёлые операции — только в первых стольких ходах
TAIL = 1000000    # сколько байт хвоста транскрипта читать

HEAVY_SKILLS = ('graphify',)

# Строки типа user, которые пользователь не набирал: вывод локальных
# команд вроде /model, отметка о прерванном ответе, уведомление о конце
# фоновой задачи. Вызов скилла (/graphify) начинается с <command-message>
# и считается ходом.
NOT_PROMPTS = ('<local-command-', '<command-name>', '[Request interrupted',
               '<task-notification>')


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


def prompt_text(d):
    """Текст сообщения пользователя или None, если это не его ход."""
    if (d.get('type') != 'user' or d.get('isMeta') or d.get('isSidechain')
            or d.get('isCompactSummary')):
        return None
    # Новые транскрипты помечают источник строки; ход — только от человека.
    origin = d.get('origin')
    if isinstance(origin, dict) and origin.get('kind') not in (None, 'human'):
        return None
    c = (d.get('message') or {}).get('content')
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        if any(isinstance(x, dict) and x.get('type') == 'tool_result' for x in c):
            return None
        return ''.join(x.get('text') or '' for x in c if isinstance(x, dict))
    return None


def prompt_count(path):
    n = 0
    with open(path, 'rb') as fh:
        for raw in fh:
            if b'"type":"user"' not in raw:
                continue
            try:
                d = json.loads(raw.decode('utf-8', 'replace'))
            except ValueError:
                continue
            text = prompt_text(d)
            if text is not None and not text.lstrip().startswith(NOT_PROMPTS):
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
    event = data.get('hook_event_name') or ''

    if event == 'UserPromptSubmit':
        ctx = context_tokens(tp)
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
        if not what:
            return
        turns = prompt_count(tp)
        if turns <= HEAVY_TURNS:
            return
        emit({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": (
                u"Тяжёлая операция ({0}) на ходу {1} чата. Субагенты и "
                u"семантическая экстракция запускаются только в первых {2} "
                u"ходах свежего чата: их результат остаётся в контексте до "
                u"конца чата и утяжеляет каждый следующий ход. "
                u"Скажи пользователю открыть новый чат в этой папке и запустить "
                u"её там первым сообщением."
            ).format(what, turns, HEAVY_TURNS),
        }})
        return


try:
    main()
except Exception:
    pass
