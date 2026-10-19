package io.github.puflik.plinth.online.youtube

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Записанные ответы YouTube (`src/test/resources/youtube/`, как они записаны — `README.md` там же) вместо сети.
 * Какой запрос чем отвечать, написано в `routes.tsv`: метод, хост, путь и подстроки, которые должны встретиться в
 * адресе или теле запроса; у маршрута — файлы ответов: 1-й вызов получает первый, следующие — по порядку, дальше —
 * последний. Запрос, которому нет маршрута, — `AssertionError` с описанием запроса: экстрактору записей не хватает.
 *
 * Тест может подменить файлы маршрута ([serve]), заставить его бросить исключение ([fail]) или замедлить каждый
 * запрос ([delayEachRequest]). Что просили — [seen]/[count] по именам маршрутов.
 */
internal class ReplayDownloader : Downloader() {
    private class Route(
        val name: String,
        val method: String,
        val host: String,
        val path: String,
        val contains: List<String>,
        var files: List<String>,
    ) {
        var served = 0
        var error: (() -> Throwable)? = null

        fun matches(request: Request): Boolean {
            val uri = URI.create(request.url())
            val body = request.dataToSend()?.toString(Charsets.UTF_8).orEmpty()
            val haystack = request.url() + "\n" + body
            return request.httpMethod() == method &&
                uri.host == host &&
                uri.path == path &&
                contains.all { it in haystack }
        }
    }

    private val routes: List<Route> =
        text("routes.tsv")
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val column = line.split('\t')
                Route(
                    name = column[0],
                    method = column[1],
                    host = column[2],
                    path = column[3],
                    contains = column[4].split(";;").filter(String::isNotEmpty),
                    files = column[5].split('|'),
                )
            }

    private val asked = CopyOnWriteArrayList<String>()
    private var delayMillis = 0L

    /** Имена маршрутов в том порядке, в каком экстрактор спрашивал. */
    val seen: List<String> get() = asked.toList()

    /** Сколько раз спросили этот маршрут. */
    fun count(route: String): Int = asked.count { it == route }

    /** Маршрут отвечает этими файлами (по порядку вызовов, дальше — последним). */
    fun serve(
        route: String,
        vararg files: String,
    ): ReplayDownloader = apply { route(route).files = files.toList() }

    /** Маршрут не отвечает, а бросает [error]. */
    fun fail(
        route: String,
        error: () -> Throwable,
    ): ReplayDownloader = apply { route(route).error = error }

    /** Каждый запрос занимает столько времени — для срока вызова. */
    fun delayEachRequest(millis: Long): ReplayDownloader = apply { delayMillis = millis }

    override fun execute(request: Request): Response {
        val route =
            routes.firstOrNull { it.matches(request) }
                ?: throw AssertionError(
                    "no recorded answer for ${request.httpMethod()} ${request.url()} " +
                        request
                            .dataToSend()
                            ?.toString(Charsets.UTF_8)
                            .orEmpty()
                            .take(BODY_SHOWN),
                )
        asked += route.name
        if (delayMillis > 0) Thread.sleep(delayMillis)
        route.error?.let { throw it() }
        val file = route.files[minOf(route.served++, route.files.size - 1)]
        return Response(200, "OK", emptyMap(), text(file), request.url())
    }

    private fun route(name: String): Route = routes.first { it.name == name }

    companion object {
        private const val BODY_SHOWN = 300

        /** Файл записи как текст UTF-8. */
        fun text(file: String): String =
            checkNotNull(
                ReplayDownloader::class.java.getResourceAsStream("/youtube/$file"),
            ) { "no recorded file $file" }
                .use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

/**
 * Общая таблица ядра в тестах: те же ответы, что у `check_http_status` на том, что тесты спрашивают (2xx — [Fine],
 * 404/410 — недоступно, остальное — отказ, у 429 и 5xx со сроком «retry after S s»); что спросили — [calls]. Настоящая
 * таблица — в ядре (её стерегут тесты Ю2 и инструментальный `CoreHttpStatusTableTest`), здесь только её двойник.
 */
internal class FakeStatusTable : HttpStatusTable {
    val calls = CopyOnWriteArrayList<Pair<Int, String?>>()

    override fun verdict(
        status: Int,
        retryAfter: String?,
    ): HttpVerdict {
        calls += status to retryAfter
        val seconds = retryAfter?.trim()?.toLongOrNull()
        return when {
            status in 200..299 -> HttpVerdict.Fine
            status == 404 || status == 410 -> HttpVerdict.Unavailable("http $status")
            seconds != null && (status == 429 || status in 500..599) ->
                HttpVerdict.Network(
                    "http $status, retry after $seconds s",
                )
            else -> HttpVerdict.Network("http $status")
        }
    }
}

/** Что записано в `src/test/resources/youtube/`: видео и альбомы, у которых есть полный набор ответов. */
internal object Recorded {
    /** «Get Lucky»: латиница, у видео есть и Opus 251, и AAC 140. */
    const val LUCKY = "4D7u5KF7SP8"

    /** «Группа крови» Кино: кириллица. */
    const val KROVI = "xtxjm7ciwmc"

    /** Альбом «Random Access Memories», 13 треков. */
    const val RAM_ALBUM = "OLAK5uy_kNhM2yaBTOVwrcZJepB1C9P3-n5_Sfy5c"

    /** Альбом «Группа крови», 11 треков. */
    const val KINO_ALBUM = "OLAK5uy_nRndsUhiMCq2wRW3JCnDgDwD33CUalJZU"
}
