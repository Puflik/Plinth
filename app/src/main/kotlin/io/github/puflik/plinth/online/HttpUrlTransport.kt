package io.github.puflik.plinth.online

import io.github.puflik.plinth.ffi.NetAnswer
import io.github.puflik.plinth.ffi.NetRequest
import io.github.puflik.plinth.ffi.NetTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Сеть ядра на `HttpURLConnection` (E3b, ADR 0008): системные сертификаты
 * и прокси, один GET без повторов — повторы, User-Agent и здоровье
 * провайдеров решает ядро.
 *
 * Ответ с любым статусом — [NetAnswer.Response]: 404 и 429 ядро понимает
 * само. Тело длиннее предела не дочитывается — [NetAnswer.TooLarge]. Нет
 * ответа — [NetAnswer.NoAnswer] с именем исключения: его текст знает адрес,
 * а в адресе бывает поисковый запрос.
 */
class HttpUrlTransport : NetTransport {
    override fun get(request: NetRequest): NetAnswer {
        val connection =
            try {
                URL(request.url).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return NetAnswer.NoAnswer(e.javaClass.simpleName)
            }
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = request.timeout.inWholeMilliseconds.toTimeout()
            connection.readTimeout = request.timeout.inWholeMilliseconds.toTimeout()
            request.headers.forEach { (name, value) -> connection.addRequestProperty(name, value) }
            val status = connection.responseCode
            if (connection.contentLengthLong > request.maxBodyBytes) {
                NetAnswer.TooLarge
            } else {
                // Тело ответа с ошибкой — в errorStream, и его может не быть вовсе.
                val failed = status >= HttpURLConnection.HTTP_BAD_REQUEST
                val stream = if (failed) connection.errorStream else connection.inputStream
                val body = if (stream == null) ByteArray(0) else stream.use { it.readAtMost(request.maxBodyBytes) }
                body?.let { NetAnswer.Response(status, it) } ?: NetAnswer.TooLarge
            }
        } catch (e: IOException) {
            NetAnswer.NoAnswer(e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /** Тело целиком, если оно не длиннее [limit]; длиннее — `null`, дальше не читается. */
    private fun InputStream.readAtMost(limit: Long): ByteArray? {
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = read(buffer)
            if (read < 0) return body.toByteArray()
            if (body.size() + read > limit) return null
            body.write(buffer, 0, read)
        }
    }

    // Таймаут 0 у HttpURLConnection — «ждать вечно»; ядро такого не просит.
    private fun Long.toTimeout(): Int = coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val BUFFER = 8 * 1024
    }
}
