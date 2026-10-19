package io.github.puflik.plinth.online.youtube

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Сеть экстрактора на настоящих сокетах (петля): GET и POST, gzip, заголовки, отказ по таблице статусов, срок вызова.
 * Таблица здесь — двойник ([calls] показывает, что у неё спросили): настоящая живёт в ядре.
 */
class YoutubeDownloaderTest {
    private val calls = CopyOnWriteArrayList<Pair<Int, String?>>()
    private val verdicts = mutableMapOf<Int, HttpVerdict>()
    private val table =
        HttpStatusTable { status, retryAfter ->
            calls += status to retryAfter
            verdicts[status] ?: if (status in 200..299) HttpVerdict.Fine else HttpVerdict.Network("http $status")
        }
    private val downloader = YoutubeDownloader(table)
    private val servers = mutableListOf<LoopbackServer>()

    @After
    fun closeServers() = servers.forEach(LoopbackServer::close)

    private fun server(reply: (LoopbackServer.Request) -> LoopbackServer.Reply): LoopbackServer =
        LoopbackServer(reply).also(servers::add)

    private fun text(
        body: String,
        vararg headers: Pair<String, String>,
    ) = LoopbackServer.Reply(body = body.toByteArray(), headers = headers.toList())

    @Test
    fun `get sends the headers of the extractor and a fallback agent, asks for gzip and reads UTF-8`() {
        val server = server { text("Группа крови", "Content-Type" to "text/plain; charset=utf-8") }

        val response =
            downloader.get(
                "${server.url}/watch?v=x",
                mapOf(
                    "X-Client" to listOf("one", "two"),
                    "Accept-Encoding" to listOf("br"),
                ),
            )

        val seen = server.requests.single()
        assertThat(seen.method).isEqualTo("GET")
        assertThat(seen.path).isEqualTo("/watch?v=x")
        assertThat(seen.header("User-Agent")).startsWith("Mozilla/5.0")
        assertThat(seen.header("Accept-Encoding")).isEqualTo("gzip")
        assertThat(
            seen.headers.filter { it.first == "X-Client" }.map { it.second },
        ).containsExactly("one", "two").inOrder()
        assertThat(response.responseCode()).isEqualTo(200)
        assertThat(response.responseBody()).isEqualTo("Группа крови")
        assertThat(response.getHeader("content-type")).isEqualTo("text/plain; charset=utf-8")
        assertThat(response.latestUrl()).isEqualTo("${server.url}/watch?v=x")
    }

    @Test
    fun `the agent of the extractor wins over the fallback`() {
        val server = server { text("ok") }

        downloader.get(server.url, mapOf("User-Agent" to listOf("com.google.visionos.youtube/1.02")))

        assertThat(server.requests.single().header("User-Agent")).isEqualTo("com.google.visionos.youtube/1.02")
    }

    @Test
    fun `post delivers the body as it is, in UTF-8, with its content type`() {
        val server = server { text("{}") }
        val json = """{"query":"Кино Группа крови","limit":20}""".toByteArray()

        downloader.postWithContentTypeJson(
            "${server.url}/youtubei/v1/search?prettyPrint=false",
            mapOf(
                "X-Goog-Visitor-Id" to listOf("v"),
            ),
            json,
        )

        val seen = server.requests.single()
        assertThat(seen.method).isEqualTo("POST")
        assertThat(seen.path).isEqualTo("/youtubei/v1/search?prettyPrint=false")
        assertThat(seen.header("Content-Type")).startsWith("application/json")
        assertThat(seen.header("Content-Length")).isEqualTo(json.size.toString())
        assertThat(seen.body.toString(Charsets.UTF_8)).isEqualTo(json.toString(Charsets.UTF_8))
        assertThat(seen.header("X-Goog-Visitor-Id")).isEqualTo("v")
    }

    @Test
    fun `a gzip answer is unpacked`() {
        val packed =
            ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write("Группа крови".toByteArray()) }
            }
        val server =
            server {
                LoopbackServer.Reply(
                    body = packed.toByteArray(),
                    headers =
                        listOf(
                            "Content-Encoding" to "gzip",
                        ),
                )
            }

        assertThat(downloader.get(server.url).responseBody()).isEqualTo("Группа крови")
    }

    @Test
    fun `head has no body`() {
        val server = server { text("body that a head does not carry") }

        val response = downloader.head(server.url)

        assertThat(server.requests.single().method).isEqualTo("HEAD")
        assertThat(response.responseBody()).isEmpty()
    }

    @Test
    fun `a redirect is followed and the last address is told`() {
        val server =
            server { request ->
                if (request.path ==
                    "/old"
                ) {
                    LoopbackServer.Reply(302, headers = listOf("Location" to "/new"))
                } else {
                    text("moved")
                }
            }

        val response = downloader.get("${server.url}/old")

        assertThat(response.responseBody()).isEqualTo("moved")
        assertThat(response.latestUrl()).isEqualTo("${server.url}/new")
    }

    @Test
    fun `every answer goes through the status table with Retry-After as it came`() {
        val server =
            server { request ->
                when (request.path) {
                    "/slow" ->
                        LoopbackServer.Reply(
                            503,
                            "<html>maintenance</html>".toByteArray(),
                            listOf(
                                "Retry-After" to "7",
                            ),
                        )
                    "/date" ->
                        LoopbackServer.Reply(
                            429,
                            headers =
                                listOf(
                                    "Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT",
                                ),
                        )
                    "/gone" -> LoopbackServer.Reply(404)
                    else -> text("fine")
                }
            }
        verdicts[503] = HttpVerdict.Network("http 503, retry after 7 s")
        verdicts[404] = HttpVerdict.Unavailable("http 404")

        assertThat(downloader.get("${server.url}/fine").responseBody()).isEqualTo("fine")
        val unavailable = assertThrows(HttpRefused::class.java) { downloader.get("${server.url}/gone") }
        val network = assertThrows(HttpRefused::class.java) { downloader.get("${server.url}/slow") }
        val dated = assertThrows(HttpRefused::class.java) { downloader.get("${server.url}/date") }

        assertThat(calls)
            .containsExactly(
                200 to null,
                404 to null,
                503 to "7",
                429 to "Wed, 21 Oct 2026 07:28:00 GMT",
            ).inOrder()
        assertThat(unavailable.verdict).isEqualTo(HttpVerdict.Unavailable("http 404"))
        assertThat(network.verdict).isEqualTo(HttpVerdict.Network("http 503, retry after 7 s"))
        assertThat(dated.verdict).isEqualTo(HttpVerdict.Network("http 429"))
        // В тексте отказа — только решение таблицы: ни адреса, ни тела страницы.
        assertThat(network.message).isEqualTo("http 503, retry after 7 s")
        assertThat(network).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `a body longer than the limit is a failure, not a truncated answer`() {
        val server = server { LoopbackServer.Reply(body = ByteArray(9 * 1024 * 1024)) }

        val failure = assertThrows(IOException::class.java) { downloader.get(server.url) }

        assertThat(failure).isNotInstanceOf(HttpRefused::class.java)
    }

    @Test
    fun `a silent server is waited for no longer than the call budget`() {
        val server = server { LoopbackServer.Reply(silent = true) }
        val started = System.nanoTime()

        assertThrows(
            SocketTimeoutException::class.java,
        ) { CallBudget.within(300.milliseconds) { downloader.get(server.url) } }

        assertThat((System.nanoTime() - started) / NANOS_PER_MILLI).isLessThan(3_000L)
    }

    @Test
    fun `a spent budget stops the call before it connects, and the budget does not outlive its call`() {
        val server = server { text("ok") }

        assertThrows(
            SocketTimeoutException::class.java,
        ) { CallBudget.within(Duration.ZERO) { downloader.get(server.url) } }

        assertThat(server.requests).isEmpty()
        assertThat(CallBudget.leftMillis()).isNull()
        assertThat(downloader.get(server.url).responseBody()).isEqualTo("ok")
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
