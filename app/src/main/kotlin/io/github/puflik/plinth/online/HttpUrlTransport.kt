package io.github.puflik.plinth.online

import io.github.puflik.plinth.ffi.NetAnswer
import io.github.puflik.plinth.ffi.NetRequest
import io.github.puflik.plinth.ffi.NetTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.time.Duration

/**
 * Сеть ядра на `HttpURLConnection` (E3b, ADR 0008): системные сертификаты
 * и прокси, один GET без повторов — повторы, User-Agent и здоровье
 * провайдеров решает ядро.
 *
 * [NetRequest.timeout] — срок на запрос целиком (соединение, заголовки и
 * тело): таймаут чтения `HttpURLConnection` сбрасывается с каждым куском, и
 * ответ, сочащийся по байту, держал бы запрос сколько захочет сервер. Срок
 * проверяется между чтениями тела, а таймауты соединения и чтения не больше
 * остатка; один блок чтения может пережить срок, но не дольше самого срока.
 * Вышел срок — [NetAnswer.NoAnswer] с `SocketTimeoutException`, как и при
 * таймауте чтения.
 *
 * Ответ с любым статусом — [NetAnswer.Response]: 404 и 429 ядро понимает
 * само. Тело длиннее предела не дочитывается — [NetAnswer.TooLarge]. Нет
 * ответа — [NetAnswer.NoAnswer] с именем исключения: его текст знает адрес,
 * а в адресе бывает поисковый запрос.
 */
class HttpUrlTransport : NetTransport {
    override fun get(request: NetRequest): NetAnswer {
        val deadline = Deadline(request.timeout)
        val connection =
            try {
                URL(request.url).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return NetAnswer.NoAnswer(e.javaClass.simpleName)
            }
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = deadline.leftMillis().toTimeout()
            connection.readTimeout = deadline.leftMillis().toTimeout()
            request.headers.forEach { (name, value) -> connection.addRequestProperty(name, value) }
            val status = connection.responseCode
            deadline.failIfSpent()
            if (connection.contentLengthLong > request.maxBodyBytes) {
                NetAnswer.TooLarge
            } else {
                // Тело ответа с ошибкой — в errorStream, и его может не быть вовсе.
                val failed = status >= HttpURLConnection.HTTP_BAD_REQUEST
                val stream = if (failed) connection.errorStream else connection.inputStream
                val body =
                    if (stream == null) ByteArray(0) else stream.use { it.readAtMost(request.maxBodyBytes, deadline) }
                body?.let { NetAnswer.Response(status, it) } ?: NetAnswer.TooLarge
            }
        } catch (e: IOException) {
            NetAnswer.NoAnswer(e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Тело целиком, если оно не длиннее [limit]; длиннее — `null`, дальше не читается.
     * Срок [deadline] проверяется перед каждым чтением.
     */
    private fun InputStream.readAtMost(
        limit: Long,
        deadline: Deadline,
    ): ByteArray? {
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            deadline.failIfSpent()
            val read = read(buffer)
            if (read < 0) return body.toByteArray()
            if (body.size() + read > limit) return null
            body.write(buffer, 0, read)
        }
    }

    // Таймаут 0 у HttpURLConnection — «ждать вечно»; срок не должен стать вечным.
    private fun Long.toTimeout(): Int = coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    /** Срок запроса целиком: отсчёт от создания, по монотонным часам. */
    private class Deadline(
        timeout: Duration,
    ) {
        private val started = System.nanoTime()
        private val budgetNanos = timeout.inWholeNanoseconds

        private fun leftNanos() = (budgetNanos - (System.nanoTime() - started)).coerceAtLeast(0L)

        /** Сколько осталось, в миллисекундах вверх: 0 — срок вышел. */
        fun leftMillis(): Long {
            val nanos = leftNanos()
            return nanos / NANOS_PER_MILLI + if (nanos % NANOS_PER_MILLI == 0L) 0 else 1
        }

        /** Срок вышел — [SocketTimeoutException], как у таймаута чтения. */
        fun failIfSpent() {
            if (leftNanos() == 0L) throw SocketTimeoutException()
        }
    }

    private companion object {
        const val BUFFER = 8 * 1024
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
