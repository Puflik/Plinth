# -*- coding: utf-8 -*-
"""Точечное обновление графа graphify: переизвлекаются только изменённые файлы.

Штатный `graphify update` здесь не годится. Установленный graphify строит
длинные ID (полный путь), а граф живёт в короткой схеме «родительский каталог +
имя файла»; старые узлы документов при замене не вычищаются (docs/decisions.md,
разделы «Граф обновлён после B2» и «Граф обновлён после шага 4»). Скрипт — тот
же ручной рецепт, разложенный по этапам. Субагентов он не запускает: этап hints
печатает документы, раскладку по чанкам и путь к подсказке, чанки пишет агент.

Запуск — интерпретатором из graphify-out/.graphify_python (нужен graphify 0.9+):

    PY="$(cat graphify-out/.graphify_python)"
    "$PY" tools/graphify_update.py detect --dry-run   # только показать изменения
    "$PY" tools/graphify_update.py prepare            # detect + ast + hints
    #   субагенты пишут graphify-out/.graphify_chunk_NN.json
    "$PY" tools/graphify_update.py semantic --tokens 01=183000 --tokens 02=184000
    "$PY" tools/graphify_update.py merge              # база без документов, build_merge, проверка
    "$PY" tools/graphify_update.py cluster            # сообщества, перенос меток
    #   вписать пустые метки в graphify-out/.graphify_labels_new.json
    "$PY" tools/graphify_update.py finish             # graph.json, отчёт, манифест, cost, html

graph.json меняет только finish; до него любой этап можно перезапустить.
"""
import argparse
import glob
import hashlib
import inspect
import json
import os
import shutil
import subprocess
import sys
import tempfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parent.parent
GO = Path('graphify-out')
GRAPH = GO / 'graph.json'
MANIFEST = GO / 'manifest.json'
LABELS = GO / '.graphify_labels.json'          # постоянный: метки прошлого графа
STATE = GO / '.graphify_update_state.json'
DETECT = GO / '.graphify_detect_full.json'
OLD = GO / '.graphify_old.json'                # снимок graph.json на момент detect
AST = GO / '.graphify_ast.json'
HINTS = GO / '.graphify_id_hints.md'
SEMANTIC = GO / '.graphify_semantic.json'
BASE = GO / '.graphify_base.json'
EXTRACT = GO / '.graphify_extract.json'
ANALYSIS = GO / '.graphify_analysis.json'
PROPOSAL = GO / '.graphify_label_proposal.json'
NEW_LABELS = GO / '.graphify_labels_new.json'
CHUNK_GLOB = '.graphify_chunk_*.json'
TEMP = (STATE, DETECT, OLD, AST, HINTS, SEMANTIC, BASE, EXTRACT, ANALYSIS, PROPOSAL, NEW_LABELS)

SEMANTIC_CATS = ('document', 'paper', 'image')
FILE_TYPES = ('code', 'document', 'paper', 'image', 'rationale', 'concept')
EXTRACTION_SPEC = Path.home() / '.claude/skills/graphify/references/extraction-spec.md'
LABEL_JACCARD = 0.5      # старая метка переходит к сообществу, если узлы совпадают хотя бы наполовину
CHARS_PER_TOKEN = 2.2    # как в .claude/hooks/guard-big-docs.py
BIG_DOC_TOKENS = 9000    # порог guard-big-docs: крупнее — читать диапазонами
CHUNK_TOKENS = 25000     # ориентир объёма документов на одного субагента


# --- общее -------------------------------------------------------------------

def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def dump(path, obj, indent=None):
    Path(path).write_text(json.dumps(obj, indent=indent, ensure_ascii=False), encoding='utf-8')


def rel(path):
    """Путь относительно корня репозитория через «/»."""
    if not path:
        return ''
    p = Path(path)
    if p.is_absolute():
        try:
            p = p.resolve().relative_to(ROOT)
        except ValueError:
            pass
    return p.as_posix()


def md5(path):
    return hashlib.md5(Path(path).read_bytes()).hexdigest()


def est_tokens(path):
    return int(len(Path(path).read_text(encoding='utf-8', errors='replace')) / CHARS_PER_TOKEN)


def read_state():
    if not STATE.exists():
        sys.exit('Нет состояния обновления — сначала этап detect (или prepare).')
    return load(STATE)


def save_state(st):
    dump(STATE, st, 2)


def clean_temp():
    for p in TEMP:
        p.unlink(missing_ok=True)
    for c in glob.glob(str(GO / CHUNK_GLOB)):
        Path(c).unlink()


def require_graphify():
    """Под `python` 3.14 стоит graphify 0.8 без build_merge(ast_sources=…)."""
    try:
        from graphify.build import build_merge
        ok = 'ast_sources' in inspect.signature(build_merge).parameters
    except ImportError:
        ok = False
    if not ok:
        hint = GO / '.graphify_python'
        py = hint.read_text(encoding='utf-8').strip() if hint.exists() else '?'
        sys.exit(f'{sys.executable} видит graphify без build_merge(ast_sources=…).\n'
                 f'Нужен graphify 0.9+, запускай через интерпретатор {py}')


def long_prefix(path):
    """Префикс ID, который строит установленный graphify: весь путь без расширения."""
    from graphify.ids import make_id
    return make_id(PurePosixPath(path).with_suffix('').as_posix())


def short_prefix(path):
    """Префикс ID в схеме графа: родительский каталог + имя файла без расширения."""
    from graphify.ids import make_id
    p = PurePosixPath(path).with_suffix('')
    return make_id(p.parent.name, p.name)


def rewriter(prefix_map):
    longs = sorted(prefix_map, key=len, reverse=True)

    def rw(node_id):
        for lp in longs:
            if node_id == lp or node_id.startswith(lp + '_'):
                return prefix_map[lp] + node_id[len(lp):]
        return node_id
    return rw


# --- detect ------------------------------------------------------------------

def stage_detect(args):
    """Полный detect() и сверка с manifest.json по хешу содержимого.

    Возвращает True, если начат цикл обновления (записаны состояние и снимок графа).
    """
    from graphify.detect import detect
    if args.dry_run:
        # detect() ведёт свой stat-кеш в graphify-out/cache; сухой прогон не пишет и его.
        with tempfile.TemporaryDirectory() as tmp:
            res = detect(Path('.'), cache_root=Path(tmp))
    else:
        res = detect(Path('.'))
    manifest = load(MANIFEST) if MANIFEST.exists() else {}
    changed, tags, touched, corpus = {}, {}, [], set()
    for cat, files in res['files'].items():
        # Документам важен semantic_hash: AST-only `graphify update` его не трогает.
        hash_key = 'semantic_hash' if cat in SEMANTIC_CATS else 'ast_hash'
        for f in files:
            r = rel(f)
            corpus.add(r)
            entry = manifest.get(r)
            if entry is None:
                tags[r] = 'новый'
            elif md5(f) != entry.get(hash_key):
                tags[r] = 'изменён'
            else:
                if abs(os.path.getmtime(f) - entry.get('mtime', 0)) > 1e-6:
                    touched.append(r)
                continue
            changed.setdefault(cat, []).append(r)
    deleted = sorted(k for k in manifest if k not in corpus)

    print('Корпус:', {k: len(v) for k, v in res['files'].items()}, '| слов:', res.get('total_words'))
    for cat in sorted(changed):
        print(f'== {cat}: {len(changed[cat])}')
        for r in sorted(changed[cat]):
            print(f'   {tags[r]:8} {r}')
    unsupported = [c for c in changed if c != 'code' and c not in SEMANTIC_CATS]
    if unsupported:
        print('!! категории вне скрипта, пропущены:', unsupported)
    if touched:
        print(f'mtime сдвинут, содержимое прежнее ({len(touched)}): {sorted(touched)[:8]}')
    print('удалены из корпуса:', deleted or 'нет')
    print('пропущено как чувствительное:', len(res.get('skipped_sensitive', [])))
    code = sorted(changed.get('code', []))
    docs = sorted(f for c in SEMANTIC_CATS for f in changed.get(c, []))
    print(f'Итого: AST — {len(code)} кодовых файлов, субагентам — {len(docs)} документов, удалений — {len(deleted)}.')
    if args.dry_run:
        print('Сухой прогон: ничего не записано.')
        return False
    clean_temp()
    if not (code or docs or deleted):
        print('Граф актуален, обновлять нечего.')
        return False

    dump(DETECT, res)
    shutil.copyfile(GRAPH, OLD)
    save_state({
        'started': datetime.now(timezone.utc).isoformat(),
        'graph_md5': md5(GRAPH),
        'directed': bool(load(OLD).get('directed', False)),
        'code': code,
        'semantic_docs': docs,
        'deleted': deleted,
    })
    print(f'Состояние: {STATE}; снимок графа: {OLD}')
    return True


# --- ast ---------------------------------------------------------------------

def stage_ast(args):
    """AST изменённого кода, перепись ID на короткую схему, отброс петель."""
    from graphify.extract import extract
    st = read_state()
    base_nodes = {n['id']: n for n in load(OLD)['nodes']}
    code = st['code']
    if not code:
        dump(AST, {'nodes': [], 'edges': [], 'hyperedges': []})
        st.update(prefix_map={}, ast_files=[], ast_failed=[])
        save_state(st)
        print('Кодовых изменений нет — AST пуст.')
        return

    result = extract([Path(f) for f in code], cache_root=Path('.'), root=Path('.'))
    failed = sorted(rel(f) for f in result.get('failed_sources') or [])
    nodes, edges = result['nodes'], result['edges']
    print(f'AST: {len(nodes)} узлов, {len(edges)} рёбер; не разобраны: {failed or "нет"}')

    # Карта префиксов по всему коду корпуса: рёбра изменённых файлов ведут и в неизменённые.
    all_code = sorted({rel(f) for f in load(DETECT)['files'].get('code', [])})
    by_short = {}
    for f in all_code:
        by_short.setdefault(short_prefix(f), []).append(f)
    clashing = {f for fs in by_short.values() if len(fs) > 1 for f in fs}
    prefix_map = {long_prefix(f): short_prefix(f) for f in all_code
                  if f not in clashing and long_prefix(f) != short_prefix(f)}

    # Короткий ID не должен занять узел другого, неизменённого файла.
    rw = rewriter(prefix_map)
    code_set = set(code)
    taken = {}
    for n in nodes:
        sf = rel(n.get('source_file'))
        old = base_nodes.get(rw(n['id']))
        if sf and old and old.get('source_file') and old['source_file'] not in code_set:
            taken.setdefault(sf, []).append(rw(n['id']))
    for sf in taken:
        prefix_map.pop(long_prefix(sf), None)
    kept_long = sorted((clashing & code_set) | set(taken))
    if kept_long:
        print('!! короткая схема занята, ID оставлены длинными:', kept_long)
        for sf, ids in taken.items():
            print(f'   {sf}: {ids[:4]}')

    unknown = [f for f in code if f not in failed and not any(
        n['id'] == long_prefix(f) or n['id'].startswith(long_prefix(f) + '_')
        for n in nodes if rel(n.get('source_file')) == f)]
    if unknown:
        print('!! ID этих файлов не начинаются с ожидаемого префикса (формат graphify сменился?):', unknown)

    rw = rewriter(prefix_map)
    for n in nodes:
        n['id'] = rw(n['id'])
        if isinstance(n.get('_callable_class'), str):
            n['_callable_class'] = rw(n['_callable_class'])
    for e in edges:
        e['source'], e['target'] = rw(e['source']), rw(e['target'])

    # Вызов player.play() внутри play() AST разрешает в сам метод — ложная петля.
    loops = Counter(e['relation'] for e in edges if e['source'] == e['target'])
    edges = [e for e in edges if e['source'] != e['target']]
    print('отброшены петли:', dict(loops) or 'нет')

    dup = [i for i, c in Counter(n['id'] for n in nodes).items() if c > 1]
    if dup:
        print('!! дубли ID после переписи:', dup[:10])
    new_ids = {n['id'] for n in nodes}
    print(f'ID уже есть в графе: {len(new_ids & set(base_nodes))} из {len(new_ids)}')
    lost = [e for e in edges if not ({e['source'], e['target']} <= (new_ids | set(base_nodes)))]
    print(f'рёбра с концом вне AST и графа: {len(lost)}')
    for e in lost[:8]:
        print(f'   {e["source"]} -> {e["target"]} ({e["relation"]})')

    dump(AST, {'nodes': nodes, 'edges': edges, 'hyperedges': [], 'input_tokens': 0, 'output_tokens': 0}, 2)
    st.update(prefix_map=prefix_map, ast_files=[f for f in code if f not in failed], ast_failed=failed)
    save_state(st)
    print(f'Записан {AST}')


# --- hints -------------------------------------------------------------------

def plan_chunks(docs):
    chunks, cur, size = [], [], 0
    for d in docs:
        t = est_tokens(d)
        if cur and size + t > CHUNK_TOKENS:
            chunks.append(cur)
            cur, size = [], 0
        cur.append(d)
        size += t
    if cur:
        chunks.append(cur)
    return chunks


def stage_hints(args):
    """Файл-подсказка с существующими ID и всё, что нужно для запуска субагентов."""
    st = read_state()
    docs = st['semantic_docs']
    if not docs:
        print('Документов на переизвлечение нет — субагенты не нужны, дальше этап semantic.')
        return
    base = load(OLD)
    ast = load(AST)

    lines = ['# Существующие ID узлов', '',
             'Понятие по-прежнему есть в документе — бери его ID в точности. Новому понятию —',
             'новый ID в той же схеме. ID других файлов из этого списка годятся как концы рёбер.', '']
    for d in docs:
        ns = sorted((n for n in base['nodes'] if n.get('source_file') == d), key=lambda n: n['id'])
        lines.append(f'## {d} ({len(ns)} узлов)')
        lines += [f"- {n['id']} | {n.get('label')} | {n.get('file_type')}" for n in ns]
        lines.append('')

    # Код — только уровень файла и класса: без методов и без внешних заглушек.
    code_set = set(st['code'])
    code = {}
    for n in base['nodes']:
        sf = n.get('source_file') or ''
        if n.get('file_type') == 'code' and sf and sf not in code_set:
            code[n['id']] = (n.get('label') or '', sf)
    for n in ast['nodes']:
        if n.get('source_file'):
            code[n['id']] = (n.get('label') or '', rel(n['source_file']))
    by_file = {}
    for i, (label, sf) in code.items():
        if not label.startswith('.') and not label.endswith('()'):
            by_file.setdefault(sf, []).append(f'{i} ({label})')
    lines.append(f'# ID кода (уровень файла и класса, {sum(map(len, by_file.values()))}) — '
                 'к ним привязывай понятия документов')
    lines.append('')
    lines += [f'- `{sf}`: ' + ', '.join(sorted(by_file[sf])) for sf in sorted(by_file)]
    HINTS.write_text('\n'.join(lines) + '\n', encoding='utf-8')

    hint_abs = (ROOT / HINTS).as_posix()
    hint_tokens = est_tokens(HINTS)
    print('Документы на переизвлечение:')
    for d in docs:
        print(f'   {d}: {len(Path(d).read_text(encoding="utf-8").splitlines())} строк, ~{est_tokens(d)} ток.')
    print(f'Подсказка: {hint_abs} (~{hint_tokens} ток.)')
    print('Раскладка по субагентам (general-purpose, все вызовы одним сообщением):')
    for k, chunk in enumerate(plan_chunks(docs), 1):
        print(f'   чанк {k:02d} -> {(ROOT / GO / f".graphify_chunk_{k:02d}.json").as_posix()}: {", ".join(chunk)}')
    print(f'Промпт — {EXTRACTION_SPEC.as_posix()} дословно, плюс добавки:')
    print('   - Хук guard-big-docs не даёт читать крупные файлы целиком: Read диапазонами по 300 строк '
          '(offset/limit)' + (', файл-подсказку тоже.' if hint_tokens > BIG_DOC_TOKENS else '.'))
    print(f'   - Существующие ID — в {hint_abs}. Понятие осталось в документе — ID переиспользуй в точности; '
          'ID кода из подсказки — концы рёбер.')
    print('   - source_file — путь от корня репозитория через «/».')
    print('Затем: semantic --tokens NN=<usage агента> для каждого чанка.')


# --- semantic ----------------------------------------------------------------

def parse_tokens(items):
    usage = {}
    for item in items or []:
        key, _, val = item.partition('=')
        inp, _, out = val.partition(':')
        usage[key.zfill(2)] = (int(inp), int(out or 0))
    return usage


def stage_semantic(args):
    """Слияние чанков субагентов, кеш, пометка _origin=semantic."""
    from graphify.cache import save_semantic_cache
    st = read_state()
    docs = set(st['semantic_docs'])
    usage = parse_tokens(args.tokens)
    chunks = sorted(glob.glob(str(GO / CHUNK_GLOB)))
    if docs and not chunks:
        sys.exit(f'Документы ждут извлечения, а чанков {GO / CHUNK_GLOB} нет.')

    nodes, edges, hyper, tin, tout = [], [], [], 0, 0
    for c in chunks:
        d = load(c)
        key = Path(c).stem.rsplit('_', 1)[-1]
        if key in usage:
            d['input_tokens'], d['output_tokens'] = usage[key]
            dump(c, d, 2)
        if not isinstance(d.get('nodes'), list) or not isinstance(d.get('edges'), list):
            sys.exit(f'{c}: нет списков nodes/edges')
        bad = [n['id'] for n in d['nodes'] if n.get('file_type') not in FILE_TYPES]
        print(f'{c}: {len(d["nodes"])} узлов, {len(d["edges"])} рёбер, '
              f'{len(d.get("hyperedges", []))} гиперрёбер' + (f'; чужой file_type: {bad[:5]}' if bad else ''))
        nodes += d['nodes']
        edges += d['edges']
        hyper += d.get('hyperedges', [])
        tin += d.get('input_tokens', 0)
        tout += d.get('output_tokens', 0)
    for item in (*nodes, *edges, *hyper):
        if item.get('source_file'):
            item['source_file'] = rel(item['source_file'])

    seen, dedup = set(), []
    for n in nodes:
        if n['id'] not in seen:
            seen.add(n['id'])
            dedup.append(n)
    if len(dedup) < len(nodes):
        print(f'повторы ID между чанками: {len(nodes) - len(dedup)} (оставлен первый)')
    # Узел с чужим source_file следующее обновление не вычистит — лучше поправить чанк.
    stray = sorted({n.get('source_file') for n in dedup} - docs - {''})
    if stray and not args.force:
        sys.exit(f'Узлы из файлов вне списка документов: {stray}. Поправь source_file в чанках '
                 'или повтори с --force.')
    extracted = {n.get('source_file') for n in dedup}
    missed = sorted(docs - extracted)
    if missed:
        print('!! документы без узлов — их старые узлы останутся, в манифест не попадут:', missed)

    print(f'Закешировано файлов: {save_semantic_cache(dedup, edges, hyper, root=Path("."))}')
    # Субагенты пишут source_location "L<n>", и build_merge без метки счёл бы их слоем AST.
    for item in (*dedup, *edges, *hyper):
        item['_origin'] = 'semantic'
    dump(SEMANTIC, {'nodes': dedup, 'edges': edges, 'hyperedges': hyper,
                    'input_tokens': tin, 'output_tokens': tout}, 2)
    print(f'Семантика: {len(dedup)} узлов, {len(edges)} рёбер, {len(hyper)} гиперрёбер; '
          f'токены {tin:,} / {tout:,}')

    old = load(OLD)
    for d in sorted(docs & extracted):
        old_ids = {n['id'] for n in old['nodes'] if n.get('source_file') == d}
        new_ids = {n['id'] for n in dedup if n.get('source_file') == d}
        gone = sorted(old_ids - new_ids)
        print(f'   {d}: было {len(old_ids)}, стало {len(new_ids)}, переиспользовано '
              f'{len(old_ids & new_ids)}, ушли {gone[:10]}{"…" if len(gone) > 10 else ""}')
    st['semantic_failed'] = missed
    save_state(st)


# --- merge и проверка --------------------------------------------------------

def stage_merge(args):
    """Очистка базы от переизвлечённых документов, AST + семантика, build_merge."""
    from graphify.build import build_merge
    st = read_state()
    ast = load(AST) if AST.exists() else None
    if ast is None and st['code']:
        sys.exit('Нет AST — сначала этап ast.')
    sem = load(SEMANTIC) if SEMANTIC.exists() else None
    if sem is None and st['semantic_docs']:
        sys.exit('Нет семантики — сначала этап semantic.')
    ast = ast or {'nodes': [], 'edges': []}
    sem = sem or {'nodes': [], 'edges': [], 'hyperedges': []}

    # У старых узлов документов нет _origin, а source_location "L<n>": build_merge
    # относит их к AST и при замене семантики оставляет. Вычищаем сами.
    docs = {n.get('source_file') for n in sem['nodes']} - {''}
    base = load(OLD)
    before = len(base['nodes'])
    base['nodes'] = [n for n in base['nodes'] if n.get('source_file') not in docs]
    base['links'] = [e for e in base['links'] if e.get('source_file') not in docs]
    base['hyperedges'] = [h for h in base.get('hyperedges', []) if h.get('source_file') not in docs]
    dump(BASE, base)
    print(f'База: убрано {before - len(base["nodes"])} узлов документов {sorted(docs)}')

    # AST первым, но внешняя заглушка без source_file уступает семантическому узлу с тем же ID.
    sem_ids = {n['id'] for n in sem['nodes']}
    merged, seen = [], set()
    for n in ast['nodes']:
        if n['id'] in sem_ids and not n.get('source_file'):
            print('   заглушка уступила семантике:', n['id'])
            continue
        merged.append(n)
        seen.add(n['id'])
    merged += [n for n in sem['nodes'] if n['id'] not in seen]
    extraction = {'nodes': merged, 'edges': ast['edges'] + sem['edges'],
                  'hyperedges': sem.get('hyperedges', []),
                  'input_tokens': sem.get('input_tokens', 0), 'output_tokens': sem.get('output_tokens', 0)}

    G = build_merge([extraction], graph_path=str(BASE), prune_sources=st['deleted'] or None,
                    root='.', directed=st['directed'], ast_sources=st.get('ast_files', []))
    shrink = G.graph.get('unverified_semantic_shrink')
    if shrink:
        print('   build_merge: непроверенная усадка семантики:', shrink)
    out = {
        'nodes': [{'id': n, **d} for n, d in G.nodes(data=True)],
        'edges': [{**{k: v for k, v in d.items() if k not in ('_src', '_tgt', 'source', 'target')},
                   'source': d.get('_src', u), 'target': d.get('_tgt', v)}
                  for u, v, d in G.edges(data=True)],
        'hyperedges': list(G.graph.get('hyperedges', [])),
        'input_tokens': extraction['input_tokens'],
        'output_tokens': extraction['output_tokens'],
    }
    dump(EXTRACT, out)
    print(f'Слито: {len(out["nodes"])} узлов, {len(out["edges"])} рёбер, {len(out["hyperedges"])} гиперрёбер '
          f'(было {before} узлов)')
    if check(st, out):
        sys.exit(1)


def check(st, extraction):
    """Проверка целостности; возвращает список проблем."""
    from graphify.diagnostics import diagnose_extraction, format_diagnostic_report
    problems = []
    ids = {n['id'] for n in extraction['nodes']}
    longs = tuple(st.get('prefix_map', {}))
    leaked = [i for i in ids if any(i == lp or i.startswith(lp + '_') for lp in longs)]
    if leaked:
        problems.append(f'длинные ID просочились: {len(leaked)}, напр. {sorted(leaked)[:3]}')
    lost = [e for e in extraction['edges'] if e['source'] not in ids or e['target'] not in ids]
    if lost:
        problems.append(f'рёбра без конца: {len(lost)} {dict(Counter(e.get("relation") for e in lost))}')
    torn = [h.get('id') for h in extraction.get('hyperedges', []) if not set(h.get('nodes', [])) <= ids]
    if torn:
        problems.append(f'гиперрёбра с пропавшими узлами: {torn[:5]}')

    summary = diagnose_extraction(extraction, directed=st['directed'], root='.')
    print(format_diagnostic_report(summary))
    for key, label in (('dangling_endpoint_edges', 'висячие рёбра'),
                       ('missing_endpoint_edges', 'рёбра без конца (diagnostics)'),
                       ('self_loop_edges', 'петли'),
                       ('directed_same_endpoint_collapsed_edges', 'схлопнутые направленные рёбра'),
                       ('undirected_same_endpoint_collapsed_edges', 'схлопнутые рёбра')):
        if summary.get(key):
            problems.append(f'{label}: {summary[key]}')
    print('ЦЕЛОСТНОСТЬ: ' + '; '.join(problems) if problems else 'Целостность чистая.')
    return problems


def stage_check(args):
    if check(read_state(), load(EXTRACT)):
        sys.exit(1)


# --- cluster -----------------------------------------------------------------

def carry_labels(communities, old_nodes, old_labels):
    """Метки старых сообществ — новым, один к одному, по Жаккару пересечения узлов."""
    old_comm = {}
    for n in old_nodes:
        if n.get('community') is not None:
            old_comm.setdefault(n['community'], set()).add(n['id'])
    pairs = []
    for cid, members in communities.items():
        ms = set(members)
        for oc, onodes in old_comm.items():
            inter = len(ms & onodes)
            if inter:
                pairs.append((inter / len(ms | onodes), cid, oc))
    carried, used, closest = {}, set(), {}
    for j, cid, oc in sorted(pairs, reverse=True):
        closest.setdefault(cid, (oc, j))
        if j >= LABEL_JACCARD and cid not in carried and oc not in used and oc in old_labels:
            carried[cid] = old_labels[oc]
            used.add(oc)
    return carried, closest


def stage_cluster(args):
    """Кластеризация и перенос меток; безымянные сообщества — в .graphify_labels_new.json."""
    from graphify.build import build_from_json
    from graphify.cluster import cluster, score_all
    from graphify.analyze import god_nodes, surprising_connections
    st = read_state()
    extraction = load(EXTRACT)
    if check(st, extraction) and not args.force:
        sys.exit('Целостность не чистая — разберись или повтори с --force.')
    G = build_from_json(extraction, root='.', directed=st['directed'])
    if G.number_of_nodes() == 0:
        sys.exit('Граф пуст.')
    communities = cluster(G)
    dump(ANALYSIS, {
        'communities': {str(k): v for k, v in communities.items()},
        'cohesion': {str(k): v for k, v in score_all(G, communities).items()},
        'gods': god_nodes(G),
        'surprises': surprising_connections(G, communities),
    }, 2)
    print(f'Граф: {G.number_of_nodes()} узлов, {G.number_of_edges()} рёбер, {len(communities)} сообществ')

    old_labels = {int(k): v for k, v in load(LABELS).items()} if LABELS.exists() else {}
    carried, closest = carry_labels(communities, load(OLD)['nodes'], old_labels)
    dump(PROPOSAL, {str(k): v for k, v in carried.items()}, 2)
    todo = sorted(c for c in communities if c not in carried)
    dump(NEW_LABELS, {str(c): '' for c in todo}, 2)
    print(f'Метки перенесены: {len(carried)}, нужно назвать: {len(todo)} — вписать в {NEW_LABELS}')
    labels_by_id = {n['id']: n.get('label') for n in extraction['nodes']}
    for c in todo:
        oc, j = closest.get(c, (None, 0))
        near = old_labels.get(oc, '—')
        sample = [labels_by_id.get(m) for m in communities[c][:14]]
        print(f'   [{c}] n={len(communities[c])} ближайшая «{near}» j={j:.2f}: {sample}')


# --- finish ------------------------------------------------------------------

def stage_finish(args):
    """graph.json, отчёт, метки, манифест, cost.json, graph.html, уборка."""
    from graphify.build import build_from_json
    from graphify.analyze import suggest_questions, graph_diff
    from graphify.report import generate
    from graphify.export import to_json
    from graphify.detect import save_manifest
    from networkx.readwrite import json_graph
    st = read_state()
    if md5(GRAPH) != st['graph_md5'] and not args.force:
        sys.exit('graph.json изменился после detect — его кто-то обновил. Начни заново или --force.')
    extraction = load(EXTRACT)
    analysis = load(ANALYSIS)
    detection = load(DETECT)
    labels = {int(k): v for k, v in load(PROPOSAL).items()}
    labels.update({int(k): v for k, v in load(NEW_LABELS).items() if v.strip()})
    communities = {int(k): v for k, v in analysis['communities'].items()}
    missing = sorted(c for c in communities if c not in labels)
    if missing:
        sys.exit(f'Нет меток у сообществ {missing} — впиши их в {NEW_LABELS}.')

    G = build_from_json(extraction, root='.', directed=st['directed'])
    if not to_json(G, communities, str(GRAPH), force=args.force, community_labels=labels):
        sys.exit('to_json отказался писать: граф меньше прежнего. Если это удаления — повтори с --force.')
    tokens = {'input': extraction.get('input_tokens', 0), 'output': extraction.get('output_tokens', 0)}
    cohesion = {int(k): v for k, v in analysis['cohesion'].items()}
    report = generate(G, communities, cohesion, labels, analysis['gods'], analysis['surprises'],
                      detection, tokens, '.', suggested_questions=suggest_questions(G, communities, labels))
    (GO / 'GRAPH_REPORT.md').write_text(report, encoding='utf-8')
    dump(LABELS, {str(k): v for k, v in labels.items()})
    print(f'graph.json: {G.number_of_nodes()} узлов, {G.number_of_edges()} рёбер, {len(communities)} сообществ')
    print('разница:', graph_diff(json_graph.node_link_graph(load(OLD), edges='links'), G)['summary'])

    # Неизвлечённые файлы в манифест не пишем — следующий detect увидит их новыми.
    failed = set(st.get('ast_failed', [])) | set(st.get('semantic_failed', []))
    files = {cat: [f for f in fs if rel(f) not in failed] for cat, fs in detection['files'].items()}
    save_manifest(files, root=Path('.'))
    bad = [k for k in load(MANIFEST) if '\\' in k or ':' in k]
    print(f'manifest.json сохранён{"; ключи не через «/»: " + str(bad[:3]) if bad else ""}'
          f'{"; пропущены неизвлечённые: " + str(sorted(failed)) if failed else ""}')

    cost_path = GO / 'cost.json'
    cost = load(cost_path) if cost_path.exists() else {'runs': [], 'total_input_tokens': 0, 'total_output_tokens': 0}
    cost['runs'].append({
        'date': datetime.now(timezone.utc).isoformat(),
        'input_tokens': tokens['input'], 'output_tokens': tokens['output'],
        'files': len(st.get('ast_files', [])) + len(set(st['semantic_docs']) - failed),
        'note': 'tools/graphify_update.py',
    })
    cost['total_input_tokens'] += tokens['input']
    cost['total_output_tokens'] += tokens['output']
    dump(cost_path, cost, 2)
    print(f'cost: этот прогон {tokens["input"]:,} / {tokens["output"]:,}')

    html = subprocess.run([sys.executable, '-m', 'graphify', 'export', 'html'], cwd=ROOT)
    print('graph.html обновлён' if html.returncode == 0 else f'!! export html упал: код {html.returncode}')
    clean_temp()
    print('Временные файлы убраны.')


# --- точка входа -------------------------------------------------------------

def stage_prepare(args):
    if stage_detect(args):
        stage_ast(args)
        stage_hints(args)


def main():
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding='utf-8', errors='replace')
    os.chdir(ROOT)
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    sub = parser.add_subparsers(dest='stage', required=True)
    p = sub.add_parser('detect', help='изменённые файлы относительно manifest.json')
    p.add_argument('--dry-run', action='store_true', help='только напечатать, ничего не писать')
    sub.add_parser('ast', help='AST изменённого кода с переписью ID')
    sub.add_parser('hints', help='файл-подсказка и раскладка для субагентов')
    sub.add_parser('prepare', help='detect + ast + hints')
    p = sub.add_parser('semantic', help='слияние чанков субагентов')
    p.add_argument('--tokens', action='append', metavar='NN=IN[:OUT]', help='usage агента для чанка NN')
    p.add_argument('--force', action='store_true', help='принять узлы с source_file вне списка')
    sub.add_parser('merge', help='очистка базы, build_merge, проверка')
    sub.add_parser('check', help='проверка целостности слитого графа')
    p = sub.add_parser('cluster', help='сообщества и перенос меток')
    p.add_argument('--force', action='store_true', help='кластеризовать и при грязной проверке')
    p = sub.add_parser('finish', help='запись graph.json, отчёта, манифеста')
    p.add_argument('--force', action='store_true', help='писать и при усадке графа или чужой правке')
    sub.add_parser('clean', help='убрать временные файлы')
    args = parser.parse_args()
    if args.stage == 'prepare':
        args.dry_run = False
    if args.stage == 'clean':
        clean_temp()
        print('Временные файлы убраны.')
        return
    require_graphify()
    {
        'detect': stage_detect, 'ast': stage_ast, 'hints': stage_hints, 'prepare': stage_prepare,
        'semantic': stage_semantic, 'merge': stage_merge, 'check': stage_check,
        'cluster': stage_cluster, 'finish': stage_finish,
    }[args.stage](args)


if __name__ == '__main__':
    main()
