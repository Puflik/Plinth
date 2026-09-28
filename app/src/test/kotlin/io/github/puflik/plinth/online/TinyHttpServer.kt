package io.github.puflik.plinth.online

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * HTTP-сервер на один приём для тестов транспорта: отвечает [reply] на
 * каждый запрос и запоминает, что пришло. Только петля, только GET.
 */
class TinyHttpServer(
    private val reply: (Request) -> Reply,
) : AutoCloseable {
    data class Request(
        val path: String,
        val headers: Map<String, String>,
    )

    /**
     * @property length слать ли `Content-Length`; нет — тело до закрытия соединения.
     * @property silent не отвечать вовсе — для таймаута.
     */
    class Reply(
        val status: Int = 200,
        val body: ByteArray = ByteArray(0),
        val length: Boolean = true,
        val silent: Boolean = false,
    )

    private val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    val requests = CopyOnWriteArrayList<Request>()
    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "tiny-http") {
            while (!socket.isClosed) {
                try {
                    socket.accept().use(::serve)
                } catch (expected: SocketException) {
                    // Сервер закрыт — поток кончается.
                }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val path =
            input
                .readLine()
                .orEmpty()
                .split(' ')
                .getOrElse(1) { "" }
        val headers =
            generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }
                .associate { line -> line.substringBefore(':').trim() to line.substringAfter(':').trim() }
        val request = Request(path, headers)
        requests += request
        val answer = reply(request)
        if (answer.silent) {
            Thread.sleep(SILENCE_MS)
            return
        }
        val head =
            buildString {
                append("HTTP/1.1 ${answer.status} Status\r\n")
                if (answer.length) append("Content-Length: ${answer.body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(answer.body)
            flush()
        }
    }

    override fun close() = socket.close()

    private companion object {
        const val BACKLOG = 8
        const val SILENCE_MS = 5_000L
    }
}
