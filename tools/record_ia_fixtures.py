# -*- coding: utf-8 -*-
"""Записывает ответы Internet Archive для тестов провайдера (E2, E1.4).

Сеть в тестах ядра запрещена: провайдер ходит в `FixtureTransport`, а тот
отвечает записанным. Этот скрипт записывает ответы настоящего API и урезает
их до нужного тестам:

- поиск FINDS с лимитом 10 (первые SEARCH_DOCS элементов) и с лимитом 1;
- поиск NOTHING — пустой ответ;
- метаданные каждого найденного элемента: первые GROUPS треков (исходник и
  его производные) и OTHER_FILES не-аудиофайлов — чтобы было что пропускать;
- метаданные несуществующего элемента GONE — archive.org отвечает `{}`.

Адреса строятся по встроенному конфигу теми же правилами, что и в ядре
(core/providers/src/config): подстановки кодируются по RFC 3986, `{+name}`
— по сегментам, параметры по алфавиту. Разойдутся правила — контракт на
фикстурах упадёт на запросе без записи.

Запуск из корня репозитория (прокси — если archive.org напрямую не открыть):

    python tools/record_ia_fixtures.py --proxy http://127.0.0.1:2080

Пишет core/providers/tests/fixtures/ia/*.json и index.json.
"""
import argparse
import json
import pathlib
import re
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG = ROOT / "core/providers/src/internet_archive/config.json"
OUT = ROOT / "core/providers/tests/fixtures/ia"

FINDS = "piano"
NOTHING = "zzqx plinth nothing"
GONE = "plinth-no-such-item-zz"
SEARCH_DOCS = 5
GROUPS = 2
OTHER_FILES = 2
# Поля файла, которые ядро не читает, — долой: фикстура меньше в разы.
DROPPED_FILE_KEYS = {"md5", "crc32", "sha1", "mtime", "height", "width", "size"}
ITEM_KEYS = ("identifier", "title", "creator", "date", "year", "mediatype")


def search_text(text):
    """Как `internet_archive::search::search_text`: строчные буквы, цифры и апостроф."""
    kept = "".join(c.lower() if c.isalnum() or c == "'" else " " for c in text)
    return " ".join(kept.split())


def encode(text):
    return urllib.parse.quote(text, safe="-._~")


def render(template, values, encoded):
    def substitute(match):
        path, name = match.group(1) == "+", match.group(2)
        value = values[name]
        if not encoded:
            return value
        return "/".join(encode(part) for part in value.split("/")) if path else encode(value)

    return re.sub(r"\{(\+?)([a-z_]+)\}", substitute, template)


def endpoint_url(config, name, values):
    endpoint = config["endpoints"][name]
    url = render(endpoint["url"], values, encoded=True)
    query = sorted(endpoint.get("query", {}).items())
    if query:
        url += "?" + "&".join(f"{encode(k)}={encode(render(v, values, encoded=False))}" for k, v in query)
    return url


def fetch(url, proxy):
    handlers = [urllib.request.ProxyHandler({"https": proxy, "http": proxy})] if proxy else []
    opener = urllib.request.build_opener(*handlers)
    request = urllib.request.Request(
        url, headers={"User-Agent": "Plinth fixtures (+https://github.com/Puflik/Plinth)", "Accept": "application/json"}
    )
    with opener.open(request, timeout=60) as response:
        return response.status, json.loads(response.read().decode("utf-8"))


def trim_search(doc, docs):
    response = doc.get("response", {})
    return {"response": {"numFound": response.get("numFound"), "start": 0, "docs": response.get("docs", [])[:docs]}}


def trim_metadata(doc, formats):
    """Первые GROUPS треков (как их собирает ядро) и OTHER_FILES прочих файлов."""
    if not doc:
        return doc
    files = doc.get("files", [])
    by_name = {f.get("name"): f for f in files}

    def anchor(f):
        current = f
        for _ in range(8):
            original = current.get("original")
            if original in by_name and original != current.get("name"):
                current = by_name[original]
            else:
                break
        return current.get("name")

    audio_anchors = sorted({anchor(f) for f in files if f.get("format") in formats})
    chosen = set(audio_anchors[:GROUPS])
    kept_names = {f.get("name") for f in files if anchor(f) in chosen}
    others = [f.get("name") for f in files if f.get("name") not in kept_names and anchor(f) not in audio_anchors]
    kept_names.update(others[:OTHER_FILES])
    kept = [{k: v for k, v in f.items() if k not in DROPPED_FILE_KEYS} for f in files if f.get("name") in kept_names]
    item = doc.get("metadata", {})
    return {"metadata": {k: item[k] for k in ITEM_KEYS if k in item}, "files": kept}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--proxy", help="http://host:port")
    args = parser.parse_args()
    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.json"):
        old.unlink()
    index = []

    def record(url, name, body):
        status, doc = fetch(url, args.proxy)
        (OUT / name).write_text(json.dumps(body(doc), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        index.append({"url": url, "status": status, "body": name})
        print(f"{status} {name}")
        return doc

    def search(text, limit, name, docs):
        url = endpoint_url(config, "search", {"query": search_text(text), "limit": str(limit)})
        return record(url, name, lambda doc: trim_search(doc, docs))

    found = search(FINDS, 10, f"search-{FINDS}-10.json", SEARCH_DOCS)
    search(FINDS, 1, f"search-{FINDS}-1.json", 1)
    search(NOTHING, 10, "search-nothing-10.json", 0)
    formats = set(config["formats"])
    for doc in found["response"]["docs"][:SEARCH_DOCS]:
        identifier = doc["identifier"]
        url = endpoint_url(config, "metadata", {"id": identifier})
        record(url, f"metadata-{identifier}.json", lambda d: trim_metadata(d, formats))
    record(endpoint_url(config, "metadata", {"id": GONE}), "metadata-gone.json", lambda d: d)
    (OUT / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(index)} answers in {OUT}")


if __name__ == "__main__":
    main()
