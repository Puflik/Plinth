package io.github.puflik.plinth.audio.media3

import io.github.puflik.plinth.online.TinyHttpServer.Reply
import io.github.puflik.plinth.online.TinyHttpServer.Request

/**
 * Ответ сервера на петле, как у googlevideo: без `Range` — всё тело (200), с `Range: bytes=N-` — хвост с N-го байта
 * и `Content-Range` (206). Для тестов чтения потока (Ю3в).
 */
internal fun rangedReply(
    body: ByteArray,
    request: Request,
): Reply {
    val from =
        request.headers["Range"]
            ?.removePrefix("bytes=")
            ?.removeSuffix("-")
            ?.toIntOrNull()
    return if (from == null) {
        Reply(status = OK, body = body)
    } else {
        Reply(
            status = PARTIAL,
            body = body.copyOfRange(from, body.size),
            headers = listOf("Content-Range" to "bytes $from-${body.size - 1}/${body.size}"),
        )
    }
}

private const val OK = 200
private const val PARTIAL = 206
