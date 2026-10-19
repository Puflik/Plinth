package io.github.puflik.plinth.online.youtube

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * HTTP-сервер для тестов загрузчика: петля, GET, POST и HEAD, тело запроса по `Content-Length`; отвечает [reply]
 * на каждый запрос и запоминает, что пришло. Один запрос — одно соединение (`Connection: close`).
 */
internal class LoopbackServer(
    private val reply: (Request) -> Reply,
) : AutoCloseable {
    /** Имена заголовков — как прислал клиент, а поиск по [header] не различает регистр. */
    class Request(
        val method: String,
        val path: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    /** [silent] — не отвечать вовсе (для срока); [headers] — заголовки ответа по порядку. */
    class Reply(
        val status: Int = 200,
        val body: ByteArray = ByteArray(0),
        val headers: List<Pair<String, String>> = emptyList(),
        val silent: Boolean = false,
    )

    private val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    val requests = CopyOnWriteArrayList<Request>()
    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "loopback-accept") {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    thread(isDaemon = true, name = "loopback-serve") { client.use(::serve) }
                } catch (expected: SocketException) {
                    // Сервер закрыт — поток кончается.
                }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            val input = client.getInputStream()
            val request = readRequest(input) ?: return
            requests += request
            val answer = reply(request)
            if (answer.silent) {
                Thread.sleep(SILENCE_MS)
                return
            }
            val head =
                buildString {
                    append("HTTP/1.1 ${answer.status} Status\r\n")
                    append("Content-Length: ${answer.body.size}\r\n")
                    answer.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                    append("Connection: close\r\n\r\n")
                }
            client.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                if (request.method != "HEAD") write(answer.body)
                flush()
            }
        } catch (expected: IOException) {
            // Клиент ушёл раньше ответа (срок, слишком большое тело) — серверу это не беда.
        } catch (expected: InterruptedException) {
            // Тест кончился.
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input)?.takeIf(String::isNotEmpty) ?: return null
        val (method, path) = line.split(' ').let { it[0] to it.getOrElse(1) { "" } }
        val headers =
            generateSequence { readLine(input)?.takeIf(String::isNotEmpty) }
                .map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
                .toList()
        val length =
            headers.firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }?.second?.toIntOrNull() ?: 0
        return Request(method, path, headers, input.readNBytes(length))
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1)
            if (next == '\n'.code) return bytes.toString(Charsets.ISO_8859_1).trimEnd('\r')
            bytes.write(next)
        }
    }

    override fun close() = socket.close()

    private companion object {
        const val BACKLOG = 8
        const val SILENCE_MS = 5_000L
    }
}
