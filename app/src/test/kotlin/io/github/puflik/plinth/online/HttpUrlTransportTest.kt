package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.NetAnswer
import io.github.puflik.plinth.ffi.NetRequest
import org.junit.After
import org.junit.Test
import java.net.ServerSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Сеть приложения для ядра (E3b) на `HttpURLConnection`: один GET, ответ с
 * любым статусом, тело не длиннее предела, отказ — «нет ответа» без адреса.
 */
class HttpUrlTransportTest {
    private var server: TinyHttpServer? = null
    private val transport = HttpUrlTransport()

    @After
    fun stop() {
        server?.close()
    }

    @Test
    fun `the answer comes with its status and body and the request with its headers`() {
        val server = serve { TinyHttpServer.Reply(200, "{\"ok\":true}".toByteArray()) }

        val answer =
            transport.get(request("${server.url}/metadata/x?q=a%20b", "User-Agent" to "Plinth/0.2", "X-Test" to "1"))

        assertThat(answer).isInstanceOf(NetAnswer.Response::class.java)
        val response = answer as NetAnswer.Response
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body.decodeToString()).isEqualTo("{\"ok\":true}")
        val seen = server.requests.single()
        assertThat(seen.path).isEqualTo("/metadata/x?q=a%20b")
        assertThat(seen.headers).containsEntry("User-Agent", "Plinth/0.2")
        assertThat(seen.headers).containsEntry("X-Test", "1")
    }

    @Test
    fun `an error status is an answer too`() {
        val server = serve { TinyHttpServer.Reply(404, "gone".toByteArray()) }

        val answer = transport.get(request("${server.url}/download/x")) as NetAnswer.Response

        assertThat(answer.status).isEqualTo(404)
        assertThat(answer.body.decodeToString()).isEqualTo("gone")
    }

    @Test
    fun `a body over the limit is too large with or without its length`() {
        val big = ByteArray(LIMIT.toInt() + 1) { 'x'.code.toByte() }
        val exact = ByteArray(LIMIT.toInt()) { 'x'.code.toByte() }
        val server =
            serve { request ->
                when (request.path) {
                    "/exact" -> TinyHttpServer.Reply(body = exact, length = false)
                    "/told" -> TinyHttpServer.Reply(body = big)
                    else -> TinyHttpServer.Reply(body = big, length = false)
                }
            }

        assertThat(transport.get(request("${server.url}/told"))).isEqualTo(NetAnswer.TooLarge)
        assertThat(transport.get(request("${server.url}/untold"))).isEqualTo(NetAnswer.TooLarge)
        assertThat((transport.get(request("${server.url}/exact")) as NetAnswer.Response).body).hasLength(exact.size)
    }

    @Test
    fun `nobody listening is no answer and the reason keeps the address to itself`() {
        val port = ServerSocket(0).use { it.localPort }

        val answer = transport.get(request("http://127.0.0.1:$port/search?q=my%20secret%20song"))

        assertThat(answer).isInstanceOf(NetAnswer.NoAnswer::class.java)
        val reason = (answer as NetAnswer.NoAnswer).reason
        assertThat(reason).isNotEmpty()
        assertThat(reason).doesNotContain("secret")
        assertThat(reason).doesNotContain("127.0.0.1")
    }

    @Test
    fun `a silent server is no answer after the timeout`() {
        val server = serve { TinyHttpServer.Reply(silent = true) }
        val started = System.nanoTime()

        val answer = transport.get(request("${server.url}/slow", timeout = 300.milliseconds))

        assertThat(answer).isEqualTo(NetAnswer.NoAnswer("SocketTimeoutException"))
        assertThat((System.nanoTime() - started).nanoseconds).isLessThan(3.seconds)
    }

    private fun serve(reply: (TinyHttpServer.Request) -> TinyHttpServer.Reply) =
        TinyHttpServer(reply).also { server = it }

    private fun request(
        url: String,
        vararg headers: Pair<String, String>,
        timeout: Duration = 5.seconds,
    ) = NetRequest(url, headers.toList(), timeout, LIMIT)

    private companion object {
        const val LIMIT = 64L
    }
}
