package io.github.puflik.plinth.online.youtube

/**
 * Таблица «статус HTTP → решение» (Ю2): одна на ядро и на Kotlin, второй нет. Настоящая — `ffi/CoreHttpStatusTable`
 * над `check_http_status` ядра; здесь только интерфейс, чтобы пакет не зависел от `ffi`. [retryAfter] — значение
 * заголовка `Retry-After`, как прислал сервер: что с ним делать (секунды, дата, мусор), решает таблица.
 */
fun interface HttpStatusTable {
    fun verdict(
        status: Int,
        retryAfter: String?,
    ): HttpVerdict
}

/**
 * Решение таблицы. Тексты — грамматика `Network` ядра: «http 503», «http 429, retry after 30 s»; Kotlin её не
 * строит и не разбирает, текст едет дальше как есть.
 */
sealed interface HttpVerdict {
    /** 2xx: ответ годится, тело читается. */
    data object Fine : HttpVerdict

    /** 404, 410: такого нет. */
    data class Unavailable(
        val text: String,
    ) : HttpVerdict

    /** Всё остальное: сейчас не вышло. */
    data class Network(
        val text: String,
    ) : HttpVerdict
}
