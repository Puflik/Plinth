package io.github.puflik.plinth.online.youtube

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Сеть экстрактора YouTube на `HttpURLConnection` (Ю3б): GET, POST и HEAD, gzip, `User-Agent` запасной, а если
 * экстрактор прислал свой — его. Редиректы идут сами, итоговый адрес — в ответе.
 *
 * Статус каждого ответа решает [statusTable] — общая таблица ядра, второй таблицы здесь нет: 2xx — тело читается,
 * иначе [HttpRefused] с её решением. Тело отказа экстрактору не показывается: страница ошибки (HTML) прошла бы у него
 * за «сломанный формат». Срок вызова ([CallBudget]) держит и соединение, и чтение: таймауты не больше остатка, а
 * между чтениями тела срок проверяется. Журнала нет — адресам и телам в нём не место.
 */
internal class YoutubeDownloader(
    private val statusTable: HttpStatusTable,
) : Downloader() {
    override fun execute(request: Request): Response {
        CallBudget.failIfSpent()
        val connection = connectionTo(request.url())
        return try {
            connection.send(request)
            connection.answer(request.httpMethod())
        } finally {
            connection.disconnect()
        }
    }

    private fun connectionTo(url: String): HttpURLConnection =
        URL(url).openConnection() as? HttpURLConnection ?: throw IOException("not an http address")

    private fun HttpURLConnection.send(request: Request) {
        requestMethod = request.httpMethod()
        instanceFollowRedirects = true
        connectTimeout = timeoutMillis(CONNECT_TIMEOUT_MS)
        readTimeout = timeoutMillis(READ_TIMEOUT_MS)
        setRequestProperty("User-Agent", FALLBACK_AGENT)
        for ((name, values) in request.headers()) {
            values.forEachIndexed { index, value ->
                if (index == 0) setRequestProperty(name, value) else addRequestProperty(name, value)
            }
        }
        // Только gzip: br и deflate разбирать нечем, а экстрактор мог попросить и их.
        setRequestProperty("Accept-Encoding", "gzip")
        request.dataToSend()?.let { body ->
            doOutput = true
            outputStream.use { it.write(body) }
        }
    }

    private fun HttpURLConnection.answer(method: String): Response {
        val status = responseCode.also { if (it < 0) throw IOException("no HTTP status") }
        val verdict = statusTable.verdict(status, getHeaderField(RETRY_AFTER))
        if (verdict != HttpVerdict.Fine) throw HttpRefused(verdict)
        val body = if (method == HEAD) "" else readBody()
        return Response(status, responseMessage.orEmpty(), responseHeaders(), body, url.toString())
    }

    private fun HttpURLConnection.readBody(): String {
        val packed = getHeaderField("Content-Encoding").equals("gzip", ignoreCase = true)
        val stream = if (packed) GZIPInputStream(inputStream) else inputStream
        return stream.use { it.readAtMost(MAX_BODY_BYTES) }.toString(Charsets.UTF_8)
    }

    /** Заголовки ответа; ключ `null` — строка статуса, она не заголовок. */
    private fun HttpURLConnection.responseHeaders(): Map<String, List<String>> =
        headerFields.entries.mapNotNull { (name, values) -> name?.let { it to values } }.toMap()

    /**
     * Тело целиком, если оно не длиннее [limit]; длиннее — [IOException]: обрезанный ответ экстрактор принял бы
     * за целый. Срок вызова проверяется перед каждым чтением.
     */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            CallBudget.failIfSpent()
            val read = read(buffer)
            if (read < 0) return body.toByteArray()
            if (body.size() + read > limit) throw IOException("body is longer than $limit bytes")
            body.write(buffer, 0, read)
        }
    }

    // Таймаут 0 у HttpURLConnection — «ждать вечно»; срок не должен стать вечным.
    private fun timeoutMillis(cap: Int): Int = CallBudget.leftMillis()?.coerceIn(1L, cap.toLong())?.toInt() ?: cap

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER = 16 * 1024
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        const val HEAD = "HEAD"
        const val RETRY_AFTER = "Retry-After"
        const val FALLBACK_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}

/**
 * Таблица статусов отказала ответу: [verdict] — [HttpVerdict.Network] или [HttpVerdict.Unavailable]. Сообщение —
 * ровно текст её решения («http 503, retry after 7 s»): ни адреса, ни тела страницы в нём нет, и дальше он едет
 * как есть.
 */
internal class HttpRefused(
    val verdict: HttpVerdict,
) : IOException(textOf(verdict))

private fun textOf(verdict: HttpVerdict): String =
    when (verdict) {
        is HttpVerdict.Unavailable -> verdict.text
        is HttpVerdict.Network -> verdict.text
        HttpVerdict.Fine -> error("a successful answer is not a refusal")
    }
