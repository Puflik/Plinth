"""Планировщик Ю3б: урезает записи YouTube до килобайт и убирает личное. Не в репозитории.

python trim.py <rec-dir> <out-dir>

Записи (rec/) делает Recorder.java: настоящий экстрактор v0.26.5 на настольной JVM, каждый обмен —
файл NNN.json. Здесь из них собираются наборы для тестов: ровно то, что читает экстрактор.
"""
import json
import os
import re
import sys

REC = sys.argv[1]
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)

PRIVATE_IP = re.compile(r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b")
DOC_IP = "203.0.113.7"
VISITOR = "TESTVISITORDATA"
EXPIRE = 1999999999

DROP_KEYS = {
    "clickTrackingParams", "trackingParams", "accessibility", "accessibilityData", "loggingContext",
    "menu", "thumbnailCrop", "thumbnailScale", "background", "serviceTrackingParams", "playerParams",
    "actions", "buttons", "shareEndpoint", "shareData",
}

SONGS = "Eg-KAQwIARAAGAAgACgAMABqChAEEAUQAxAKEAk%3D"
ALBUMS = "Eg-KAQwIABAAGAEgACgAMABqChAEEAUQAxAKEAk%3D"


def rec(name, n):
    with open(os.path.join(REC, name, "%03d.json" % n), encoding="utf-8") as f:
        return json.load(f)


def response_json(name, n):
    return json.loads(rec(name, n)["responseBody"])


def scrub_text(s):
    """Личное: адрес клиента в ссылках потока."""
    return PRIVATE_IP.sub(lambda m: m.group(0) if m.group(0) == "0.0.0.0" else DOC_IP, s)


def write_text(rel, text):
    text = scrub_text(text)
    path = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("%-40s %7d bytes" % (rel, len(text.encode("utf-8"))))


def write_json(rel, obj):
    write_text(rel, json.dumps(obj, ensure_ascii=False, separators=(",", ":")))


def prune(o):
    """Убирает отслеживание и украшения, оставляя то, что читает экстрактор."""
    if isinstance(o, dict):
        out = {}
        for k, v in o.items():
            if k in DROP_KEYS:
                continue
            if k == "thumbnails" and isinstance(v, list):
                out[k] = v[:1]
                continue
            out[k] = prune(v)
        return out
    if isinstance(o, list):
        return [prune(x) for x in o]
    return o


# ---------- разведка ----------
VISITOR_FIELD = re.compile(r'("(?:visitorData|visitor_data|value)":")(Cg[A-Za-z0-9%_-]{20,})(")')


def bootstrap(rec_name, n, out):
    """Ответ-«разведка» (visitor_id, подсказки): как есть, но без посетительских данных."""
    text = rec(rec_name, n)["responseBody"]
    text = VISITOR_FIELD.sub(lambda m: m.group(1) + VISITOR + m.group(3), text)
    text = re.sub(r'"rolloutToken":"[^"]*",?', "", text)
    write_json(out, json.loads(text))


def sw_fixture(rec_name, n, out):
    text = rec(rec_name, n)["responseBody"]
    ver = re.search(r'"INNERTUBE_CLIENT_VERSION":"([0-9.]+)"', text).group(1)
    write_text(out, 'self.document = self; ytcfg.set({"INNERTUBE_CLIENT_VERSION":"%s","INNERTUBE_CONTEXT_CLIENT_VERSION":"%s"});' % (ver, ver))


# ---------- поиск ----------
def search_fixture(rec_name, n_items, out):
    j = response_json(rec_name, 2)
    shelf = j["contents"]["tabbedSearchResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"][0]["musicShelfRenderer"]
    items = [prune(it) for it in shelf["contents"][:n_items]]
    write_json(out, {"contents": {"tabbedSearchResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [{"musicShelfRenderer": {"contents": items}}]}}}}]}}})


def search_empty(out):
    write_json(out, {"contents": {"tabbedSearchResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": []}}}}]}}})


# ---------- видео ----------
def synth_url(itag, mime, clen, dur, sig, client="VISIONOS"):
    """Ссылка потока той же формы, что у YouTube, но без адреса, страны, сервера и сеансовых меток."""
    return ("https://rr1---sn-test.googlevideo.com/videoplayback?expire=%d&ip=%s&id=o-TEST&itag=%d&source=youtube&requiressl=yes"
            "&mime=%s&clen=%s&dur=%s&c=%s&sig=%s" % (EXPIRE, DOC_IP, itag, mime.replace("/", "%2F"), clen, dur, client, sig))


def url_param(url, name):
    m = re.search(r"[?&]%s=([^&]*)" % name, url)
    return m.group(1) if m else ""


def player_fixture(rec_name, n, out, keep=(139, 140, 249, 250, 251), sig="SIGA", rename=None, client="VISIONOS"):
    j = response_json(rec_name, n)
    vd = j["videoDetails"]
    vd2 = {k: vd[k] for k in ("videoId", "title", "lengthSeconds", "author", "channelId", "isLive", "isLiveContent") if k in vd}
    vd2["shortDescription"] = vd.get("shortDescription", "")[:120]
    vd2["viewCount"] = vd.get("viewCount", "0")
    sd = j["streamingData"]
    fmts = []
    for f in sd["adaptiveFormats"]:
        if f.get("itag") not in keep:
            continue
        g = {k: v for k, v in f.items() if k not in ("url", "signatureCipher", "lastModified", "trackAbsoluteLoudnessLkfs", "highReplication")}
        itag = f["itag"]
        new_itag = rename.get(itag, itag) if rename else itag
        g["itag"] = new_itag
        g["url"] = synth_url(new_itag, f["mimeType"].split(";")[0], url_param(f["url"], "clen"), url_param(f["url"], "dur"), sig, client)
        fmts.append(g)
    write_json(out, {
        "playabilityStatus": {"status": "OK", "playableInEmbed": True},
        "streamingData": {"expiresInSeconds": sd.get("expiresInSeconds", "21540"), "adaptiveFormats": fmts},
        "videoDetails": vd2,
    })


def web_player_fixture(rec_name, n, out):
    j = response_json(rec_name, n)
    mf = j["microformat"]["playerMicroformatRenderer"]
    keep = ("title", "lengthSeconds", "ownerChannelName", "category", "uploadDate", "publishDate", "viewCount", "externalChannelId", "externalVideoId", "isUnlisted", "isFamilySafe")
    new_mf = {k: mf[k] for k in keep if k in mf}
    new_mf["description"] = {"simpleText": mf.get("description", {}).get("simpleText", "")[:120]}
    write_json(out, {"videoDetails": {"videoId": j["videoDetails"]["videoId"]}, "microformat": {"playerMicroformatRenderer": new_mf}})


def reel_fixture(rec_name, n, out, status="OK", reason=None, html=False):
    if html:
        write_text(out, "<!doctype html><html><head><title>Oops</title></head><body>" + "x" * 80 + "</body></html>")
        return
    j = response_json(rec_name, n)["playerResponse"]
    vd = j["videoDetails"]
    ps = {"status": status}
    if status == "OK":
        ps["playableInEmbed"] = True
    if reason:
        ps["reason"] = reason
    write_json(out, {"playerResponse": {
        "playabilityStatus": ps,
        "videoDetails": {k: vd[k] for k in ("videoId", "title", "lengthSeconds", "author", "channelId") if k in vd},
        "streamingData": {"expiresInSeconds": "21540", "adaptiveFormats": []},
    }})


def next_fixture(out):
    write_json(out, {"contents": {"twoColumnWatchNextResults": {"results": {"results": {"contents": []}}}}})


# ---------- альбомы ----------
def prune_lockup(item):
    lk = item["lockupViewModel"]
    tv = lk["contentImage"]["thumbnailViewModel"]
    badge = None
    for ov in tv.get("overlays", []):
        b = ov.get("thumbnailBottomOverlayViewModel", {}).get("badges")
        if b:
            badge = {"thumbnailBottomOverlayViewModel": {"badges": [{"thumbnailBadgeViewModel": {"text": b[0]["thumbnailBadgeViewModel"]["text"]}}]}}
    meta = lk["metadata"]["lockupMetadataViewModel"]
    rows = []
    for row in meta["metadata"]["contentMetadataViewModel"]["metadataRows"]:
        parts = []
        for part in row.get("metadataParts", []):
            t = part["text"]
            tp = {"content": t["content"]}
            runs = t.get("commandRuns")
            if runs:
                tp["commandRuns"] = [{"startIndex": r["startIndex"], "length": r["length"], "onTap": {"innertubeCommand": {"browseEndpoint": r["onTap"]["innertubeCommand"]["browseEndpoint"]}}}
                                     for r in runs if "browseEndpoint" in r.get("onTap", {}).get("innertubeCommand", {})]
            parts.append({"text": tp})
        rows.append({"metadataParts": parts})
    return {"lockupViewModel": {
        "contentImage": {"thumbnailViewModel": {"image": {"sources": tv["image"]["sources"][:1]}, "overlays": [badge] if badge else []}},
        "metadata": {"lockupMetadataViewModel": {"title": meta["title"], "metadata": {"contentMetadataViewModel": {"metadataRows": rows, "delimiter": " • "}}}},
        "contentId": lk["contentId"], "contentType": lk["contentType"]}}


def album_fixtures(rec_name, tag):
    full = response_json(rec_name, 2)
    head = full["header"]["playlistHeaderRenderer"]
    prim = full["sidebar"]["playlistSidebarRenderer"]["items"][0]["playlistSidebarPrimaryInfoRenderer"]
    write_json("album/header-%s.json" % tag, {
        "header": {"playlistHeaderRenderer": {"playlistId": head["playlistId"], "title": head["title"], "numVideosText": head["numVideosText"], "subtitle": head.get("subtitle", {})}},
        "sidebar": {"playlistSidebarRenderer": {"items": [{"playlistSidebarPrimaryInfoRenderer": {
            "title": {"runs": [{"text": prim["title"]["runs"][0]["text"]}]}, "stats": prim["stats"]}}]}},
    })
    items = response_json(rec_name, 3)["onResponseReceivedActions"][0]["appendContinuationItemsAction"]["continuationItems"]
    write_json("album/items-%s.json" % tag, {"onResponseReceivedActions": [{"appendContinuationItemsAction": {"continuationItems": [prune_lockup(i) for i in items if "lockupViewModel" in i]}}]})


# ---------- маршруты ----------
ROUTES = []


def route(name, method, host, path, contains, files):
    ROUTES.append("\t".join([name, method, host, path, ";;".join(contains), "|".join(files)]))


def main():
    # разведка
    bootstrap("search_DaftxPunkxRandomxAccessxMemories_songs", 1, "bootstrap/suggest.json")
    s = "stream_4D7u5KF7SP8_2"
    bootstrap(s, 1, "bootstrap/visitor-android.json")
    bootstrap(s, 3, "bootstrap/visitor-visionos.json")
    bootstrap(s, 6, "bootstrap/visitor-web.json")
    sw_fixture(s, 5, "bootstrap/sw.js")
    route("suggest", "POST", "music.youtube.com", "/youtubei/v1/music/get_search_suggestions", [], ["bootstrap/suggest.json"])
    route("visitor.android", "POST", "youtubei.googleapis.com", "/youtubei/v1/visitor_id", ['"clientName":"ANDROID"'], ["bootstrap/visitor-android.json"])
    route("visitor.visionos", "POST", "www.youtube.com", "/youtubei/v1/visitor_id", ['"clientName":"VISIONOS"'], ["bootstrap/visitor-visionos.json"])
    route("visitor.web", "POST", "www.youtube.com", "/youtubei/v1/visitor_id", ['"clientName":"WEB"'], ["bootstrap/visitor-web.json"])
    route("sw", "GET", "www.youtube.com", "/sw.js", [], ["bootstrap/sw.js"])

    # поиск
    search_fixture("search_DaftxPunkxRandomxAccessxMemories_songs", 8, "search/songs-ram.json")
    search_fixture("search_DaftxPunkxRandomxAccessxMemories_albums", 6, "search/albums-ram.json")
    search_fixture("search_q1_songs", 6, "search/songs-kino.json")
    search_fixture("search_q1_albums", 4, "search/albums-kino.json")
    search_empty("search/empty.json")
    for kind, params in (("songs", SONGS), ("albums", ALBUMS)):
        route("search.%s.ram" % kind, "POST", "music.youtube.com", "/youtubei/v1/search", ['"query":"Daft Punk Random Access Memories"', params], ["search/%s-ram.json" % kind])
        route("search.%s.kino" % kind, "POST", "music.youtube.com", "/youtubei/v1/search", ['"query":"Кино Группа крови"', params], ["search/%s-kino.json" % kind])
        route("search.%s.empty" % kind, "POST", "music.youtube.com", "/youtubei/v1/search", ['"query":"zzz nothing here"', params], ["search/empty.json"])

    # видео: lucky — латиница, у видео есть и Opus, и AAC; krovi — кириллица
    for tag, vid, rec_name in (("lucky", "4D7u5KF7SP8", "stream_4D7u5KF7SP8_2"), ("krovi", "xtxjm7ciwmc", "stream_xtxjm7ciwmc_1")):
        reel_fixture(rec_name, 2, "stream/reel-%s.json" % tag)
        player_fixture(rec_name, 4, "stream/player-%s.json" % tag)
        web_player_fixture(rec_name, 7, "stream/player-web-%s.json" % tag)
        next_fixture("stream/next-%s.json" % tag)
        files = ["stream/player-%s.json" % tag]
        if tag == "lucky":
            player_fixture(rec_name, 4, "stream/player-lucky-b.json", sig="SIGB")
            files.append("stream/player-lucky-b.json")
        route("reel." + tag, "POST", "youtubei.googleapis.com", "/youtubei/v1/reel/reel_item_watch", ["id=" + vid], ["stream/reel-%s.json" % tag])
        route("player." + tag, "POST", "youtubei.googleapis.com", "/youtubei/v1/player", ['"videoId":"%s"' % vid], files)
        route("player.web." + tag, "POST", "www.youtube.com", "/youtubei/v1/player", ['"videoId":"%s"' % vid], ["stream/player-web-%s.json" % tag])
        route("next." + tag, "POST", "www.youtube.com", "/youtubei/v1/next", ['"videoId":"%s"' % vid], ["stream/next-%s.json" % tag])
    # варианты ответа VisionOS для lucky: чего-то нет
    s = "stream_4D7u5KF7SP8_2"
    player_fixture(s, 4, "stream/player-lucky-no-opus.json", keep=(139, 140))
    player_fixture(s, 4, "stream/player-lucky-no-aac.json", keep=(249, 250, 251))
    player_fixture(s, 4, "stream/player-lucky-141.json", keep=(139, 140, 249, 250), rename={140: 141})  # AAC только как 141, Opus 251 нет
    player_fixture(s, 4, "stream/player-lucky-no-audio.json", keep=())
    player_fixture(s, 4, "stream/player-lucky-other-client.json", client="ANDROID_VR")  # ссылки выдал не VisionOS
    # ошибки: что отвечает Android-клиент про видео
    reel_fixture(s, 2, "errors/reel-unavailable.json", "ERROR", "This video is unavailable")
    reel_fixture(s, 2, "errors/reel-bot.json", "LOGIN_REQUIRED", "Sign in to confirm you’re not a bot")
    reel_fixture(s, 2, "errors/reel-private.json", "LOGIN_REQUIRED", "This video is private")
    reel_fixture(s, 2, "errors/reel-html.json", html=True)

    # альбомы
    album_fixtures("playlist_OLAK5uy_kNhM2yaBTOVwrcZJepB1C9P3-n5_Sfy5c", "ram")
    album_fixtures("playlist_OLAK5uy_nRndsUhiMCq2wRW3JCnDgDwD33CUalJZU", "kino")
    write_json("album/items-empty.json", {"onResponseReceivedActions": [{"appendContinuationItemsAction": {"continuationItems": []}}]})
    for tag, pid in (("ram", "OLAK5uy_kNhM2yaBTOVwrcZJepB1C9P3-n5_Sfy5c"), ("kino", "OLAK5uy_nRndsUhiMCq2wRW3JCnDgDwD33CUalJZU")):
        route("album.header." + tag, "POST", "www.youtube.com", "/youtubei/v1/browse", ['"browseId":"VL%s"' % pid], ["album/header-%s.json" % tag])
        # продолжение — токен, который экстрактор строит сам; кусок с идентификатором списка берём из записи
        token = json.loads(rec("playlist_" + pid, 3)["requestBody"])["continuation"]
        route("album.items." + tag, "POST", "www.youtube.com", "/youtubei/v1/browse", ['"continuation":"%s' % token[:44]], ["album/items-%s.json" % tag])

    header = ("# Маршруты проигрывателя записей. Столбцы через табуляцию:\n"
              "# имя  метод  хост  путь  что должно встретиться в запросе (через ';;')  файлы ответа (через '|': 1-й, 2-й и т. д. вызов, дальше — последний)\n")
    with open(os.path.join(OUT, "routes.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write(header + "\n".join(ROUTES) + "\n")


if __name__ == "__main__":
    main()
