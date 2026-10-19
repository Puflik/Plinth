package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.NetAnswer
import io.github.puflik.plinth.ffi.NetRequest
import org.junit.After
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Р1.11а (`docs/work/r1-11a.md`, находка 10 ревью v0.2): срок запроса в
 * [NetRequest.timeout] — на запрос целиком, а не на каждый кусок. Ядро
 * укладывает поиск в общий срок и просит у сети ровно то, что осталось;
 * `HttpURLConnection` сбрасывает таймаут чтения с каждым полученным куском, и
 * ответ, который сочится по байту, держал бы запрос (и поток IO) сколько
 * захочет сервер.
 */
class HttpUrlTransportDeadlineTest {
    private var server: Trickle? = null
    private val transport = HttpUrlTransport()

    @After
    fun stop() {
        server?.close()
    }

    @Test
    fun `a body that trickles in is cut off at the timeout`() {
        val server = trickle(headDelay = Duration.ZERO, bytes = 40, gap = 100.milliseconds)
        val started = System.nanoTime()

        val answer = transport.get(request(server.url, timeout = 600.milliseconds))

        assertThat(answer).isEqualTo(NetAnswer.TimedOut)
        assertThat(since(started)).isLessThan(2.seconds)
    }

    @Test
    fun `the timeout is for the whole request and not for each phase`() {
        // Заголовки приходят через 400 мс, тело — ещё ~900 мс: каждая стадия короче 600 мс, вместе — нет.
        val server = trickle(headDelay = 400.milliseconds, bytes = 9, gap = 100.milliseconds)
        val started = System.nanoTime()

        val answer = transport.get(request(server.url, timeout = 600.milliseconds))

        assertThat(answer).isEqualTo(NetAnswer.TimedOut)
        assertThat(since(started)).isLessThan(2.seconds)
    }

    /** Страж: ответ, который уложился в срок, приходит целиком. */
    @Test
    fun `a body that arrives within the timeout is returned whole`() {
        val server = trickle(headDelay = Duration.ZERO, bytes = 5, gap = 50.milliseconds)

        val answer = transport.get(request(server.url, timeout = 3.seconds))

        assertThat(answer).isInstanceOf(NetAnswer.Response::class.java)
        assertThat((answer as NetAnswer.Response).body.decodeToString()).isEqualTo("xxxxx")
    }

    private fun trickle(
        headDelay: Duration,
        bytes: Int,
        gap: Duration,
    ) = Trickle(headDelay, bytes, gap).also { server = it }

    private fun request(
        url: String,
        timeout: Duration,
    ) = NetRequest(url, emptyList(), timeout, LIMIT)

    private fun since(startedNanos: Long) = (System.nanoTime() - startedNanos).nanoseconds

    /** Сервер на один приём: ответ идёт по байту с паузой [gap]. Только петля, только GET. */
    private class Trickle(
        private val headDelay: Duration,
        private val bytes: Int,
        private val gap: Duration,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
        val url: String get() = "http://127.0.0.1:${socket.localPort}/slow"

        init {
            thread(isDaemon = true, name = "trickle-http") {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use(::serve)
                    } catch (expected: IOException) {
                        // Сервер закрыт или клиент ушёл — поток кончается или ждёт следующего.
                    }
                }
            }
        }

        private fun serve(client: Socket) {
            val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.count()
            Thread.sleep(headDelay.inWholeMilliseconds)
            val output = client.getOutputStream()
            output.write("HTTP/1.1 200 OK\r\nContent-Length: $bytes\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            repeat(bytes) {
                output.write('x'.code)
                output.flush()
                Thread.sleep(gap.inWholeMilliseconds)
            }
        }

        override fun close() = socket.close()

        private companion object {
            const val BACKLOG = 8
        }
    }

    private companion object {
        const val LIMIT = 64L
    }
}
