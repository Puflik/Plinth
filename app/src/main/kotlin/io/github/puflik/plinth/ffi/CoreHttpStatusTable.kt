package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.ffi.generated.CoreException
import io.github.puflik.plinth.online.youtube.HttpStatusTable
import io.github.puflik.plinth.online.youtube.HttpVerdict
import io.github.puflik.plinth.ffi.generated.checkHttpStatus as coreCheckHttpStatus

/**
 * Общая таблица «статус HTTP → ошибка» ядра (`check_http_status`, Ю2) для Kotlin-загрузчика YouTube: второй таблицы
 * нет. Тексты — грамматика `Network` ядра («http 503», «http 429, retry after 30 s»); Kotlin их не строит.
 * Пакет `online/youtube` ядра не знает, поэтому реализует его интерфейс `ffi`, а не наоборот.
 */
object CoreHttpStatusTable : HttpStatusTable {
    override fun verdict(
        status: Int,
        retryAfter: String?,
    ): HttpVerdict =
        try {
            coreCheckHttpStatus(status.toUShort(), retryAfter)
            HttpVerdict.Fine
        } catch (refusal: CoreException) {
            verdictOf(refusal)
        }
}

/**
 * Отказ ядра → решение таблицы. Ядро показывает ошибку с категорией («network: http 503»), а нужен текст таблицы
 * без неё: он уйдёт обратно в `CoreError` как есть, и ядро прочтёт в нём статус и срок. В тексте таблицы двоеточия
 * нет, так что снимается всё до первого «: », если оно есть. `Unavailable` ядра — «такого нет»; любой другой
 * отказ — [HttpVerdict.Network]: ответ, который таблица не разрешила, наружу не отдаётся.
 */
internal fun verdictOf(error: CoreException): HttpVerdict {
    val shown = error.message.orEmpty()
    val text = shown.substringAfter(": ", shown)
    return when (error) {
        is CoreException.Unavailable -> HttpVerdict.Unavailable(text)
        else -> HttpVerdict.Network(text)
    }
}
