package io.github.puflik.plinth.online.youtube

import kotlin.time.Duration

/**
 * Источник YouTube (Ю3б): три вопроса — что найдётся, какие в этом треки, откуда играть. Вызовы блокирующие и со
 * сроком; любая беда приходит как [YoutubeException], ничего другого наружу не выходит.
 *
 * Идентификаторы: видео — 11 знаков `[A-Za-z0-9_-]`, альбом — плейлист `OLAK5uy_…`. Вариант трека — формат
 * `<видео>/<itag>`: Opus (251) или AAC (140). Ядро берёт первый вариант, что ответил; у трека их не больше двух,
 * Opus раньше AAC, так что «Opus, иначе AAC» и повтор в другом формате после отказа ссылки — его обычный порядок.
 */
interface YoutubeSource {
    /**
     * Песни, затем альбомы, всего не больше [limit] (приводится к 1..100); альбомам — четверть мест, пока их хватает.
     * Пустой запрос — пустой список, сети нет.
     */
    fun search(
        query: String,
        limit: Int,
    ): List<YoutubeResult>

    /**
     * Треки по [id] из результата поиска: у видео — один, у альбома — первая страница плейлиста. Чужой или
     * испорченный [id] — [YoutubeException.Unavailable] без сети.
     */
    fun resolve(id: String): List<YoutubeTrack>

    /**
     * Адрес и заголовки для [variant] (`<видео>/<itag>`, только 251 и 140; иначе [YoutubeException.Unavailable] без
     * сети). Каждый вызов даёт свежую ссылку: ссылку, на которую плеер получил отказ, источник второй раз не отдаёт.
     */
    fun stream(variant: String): YoutubeStream
}

/** Что нашёл поиск: песня (видео) или альбом (плейлист). */
enum class YoutubeKind { TRACK, ALBUM }

/**
 * Строка поиска; [id] принимает [YoutubeSource.resolve]. У альбома в поиске нет длительности: [duration] — `null`.
 */
data class YoutubeResult(
    val id: String,
    val kind: YoutubeKind,
    val title: String,
    val artist: String? = null,
    val duration: Duration? = null,
)

/** Формат звука варианта. */
enum class YoutubeFormat { OPUS, AAC }

/** Вариант трека: [id] — `<видео>/<itag>`, его принимает [YoutubeSource.stream]. */
data class YoutubeVariant(
    val id: String,
    val format: YoutubeFormat,
    val bitrateKbps: Int,
)

/**
 * Трек. [id] — видео (11 знаков). [album] и [number] есть только у треков альбома. [variants] — Opus раньше AAC: у
 * видео — те, что у него есть, у трека альбома — оба номинальных (формат узнаётся только при игре).
 */
data class YoutubeTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val number: Int? = null,
    val duration: Duration? = null,
    val variants: List<YoutubeVariant>,
)

/**
 * Откуда играть: [url] и [headers], которые плееру положено прислать вместе с ним — `User-Agent` клиента, выдавшего
 * ссылку (если он известен), и `Range: bytes=0-`: без него первый запрос идёт со скоростью 10 КиБ/с.
 */
data class YoutubeStream(
    val url: String,
    val headers: List<Pair<String, String>>,
    val format: YoutubeFormat,
    val bitrateKbps: Int,
)

/**
 * Беда источника — одна из трёх. [text] — всё, что о ней известно снаружи: имя класса сбоя или решение общей таблицы
 * статусов («http 503, retry after 7 s»). В нём никогда нет сообщения исключения, адреса, запроса и названий: он
 * уходит в сообщение ошибки ядра и в журнал. Исходное исключение — [cause], в журнал оно не идёт.
 */
sealed class YoutubeException(
    val text: String,
    cause: Throwable? = null,
) : Exception(text, cause) {
    /** Нет ответа или сервер просит подождать: попробовать позже можно. */
    class Network(
        text: String,
        cause: Throwable? = null,
    ) : YoutubeException(text, cause)

    /** Ответ пришёл, но формат не тот, что разбирается: сломался разбор, а не сеть. */
    class Parse(
        text: String,
        cause: Throwable? = null,
    ) : YoutubeException(text, cause)

    /** Такого нет или оно закрыто: повтор не поможет. */
    class Unavailable(
        text: String,
        cause: Throwable? = null,
    ) : YoutubeException(text, cause)
}
