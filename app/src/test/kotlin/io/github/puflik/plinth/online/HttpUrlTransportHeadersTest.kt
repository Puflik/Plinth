package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.NetAnswer
import io.github.puflik.plinth.ffi.NetRequest
import org.junit.After
import org.junit.Test
import java.net.ServerSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Р1.11г (`docs/work/r1-11d.md`, находки R1 №13 и v0.2 №30): из ответа сети ядро должно
 * узнать то, по чему отличает «сервер просит подождать» и «сервер не отвечает» от
 * «нет соединения», — заголовки ответа (`Retry-After`) и таймаут отдельным ответом.
 *
 * `NetAnswer.Response.headers` и `NetAnswer.TimedOut` — новый API (сигнатуры в файле задачи).
 */
class HttpUrlTransportHeadersTest {
    private var server: TinyHttpServer? = null
    private val transport = HttpUrlTransport()

    @After
    fun stop() {
        server?.close()
    }

    @Test
    fun `an error answer brings the headers the core reads Retry-After from`() {
        val server =
            serve {
                TinyHttpServer.Reply(
                    status = 429,
                    body = "slow down".toByteArray(),
                    headers = listOf("Retry-After" to "30", "X-Test" to "1"),
                )
            }

        val answer = transport.get(request("${server.url}/search")) as NetAnswer.Response

        assertThat(answer.status).isEqualTo(429)
        assertThat(answer.body.decodeToString()).isEqualTo("slow down")
        assertThat(answer.headers).containsAtLeast("Retry-After" to "30", "X-Test" to "1")
    }

    @Test
    fun `a good answer brings its headers too`() {
        val server = serve { TinyHttpServer.Reply(200, "{}".toByteArray(), headers = listOf("X-Test" to "1")) }

        val answer = transport.get(request("${server.url}/metadata/x")) as NetAnswer.Response

        assertThat(answer.status).isEqualTo(200)
        assertThat(answer.headers).contains("X-Test" to "1")
    }

    @Test
    fun `a header that came twice comes as two pairs`() {
        val server = serve { TinyHttpServer.Reply(headers = listOf("X-Multi" to "a", "X-Multi" to "b")) }

        val answer = transport.get(request("${server.url}/x")) as NetAnswer.Response

        assertThat(answer.headers).containsAtLeast("X-Multi" to "a", "X-Multi" to "b")
    }

    @Test
    fun `the status line is not a header`() {
        val server = serve { TinyHttpServer.Reply(headers = listOf("X-Test" to "1")) }

        val answer = transport.get(request("${server.url}/x")) as NetAnswer.Response

        assertThat(answer.headers).isNotEmpty()
        assertThat(answer.headers.map { it.first }).containsNoneOf("", "null")
        assertThat(answer.headers.none { it.first.startsWith("HTTP/") || it.second.startsWith("HTTP/") }).isTrue()
    }

    @Test
    fun `a server that does not answer in time is a timeout and not a refused connection`() {
        val server = serve { TinyHttpServer.Reply(silent = true) }

        val answer = transport.get(request("${server.url}/slow", timeout = 300.milliseconds))

        assertThat(answer).isEqualTo(NetAnswer.TimedOut)
    }

    @Test
    fun `a refused connection is no answer and not a timeout`() {
        val port = ServerSocket(0).use { it.localPort }

        val answer = transport.get(request("http://127.0.0.1:$port/search?q=my%20secret%20song"))

        assertThat(answer).isInstanceOf(NetAnswer.NoAnswer::class.java)
        assertThat((answer as NetAnswer.NoAnswer).reason).isNotEmpty()
    }

    private fun serve(reply: (TinyHttpServer.Request) -> TinyHttpServer.Reply) =
        TinyHttpServer(reply).also { server = it }

    private fun request(
        url: String,
        timeout: Duration = 5.seconds,
    ) = NetRequest(url, emptyList(), timeout, LIMIT)

    private companion object {
        const val LIMIT = 1_024L
    }
}
