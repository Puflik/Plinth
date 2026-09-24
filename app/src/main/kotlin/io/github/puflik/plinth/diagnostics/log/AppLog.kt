package io.github.puflik.plinth.diagnostics.log

/**
 * Лог из любого места кода (G1.1): `PlinthApplication` ставит сюда
 * [Logger] при старте, до этого — и в JVM-тестах — записи никуда не идут.
 * Названия треков, поисковые запросы и прочее содержимое библиотеки сюда
 * не передаются: вырезание страхует пути и адреса, но не текст.
 */
object AppLog {
    @Volatile
    private var logger: Logger? = null

    fun install(logger: Logger?) {
        this.logger = logger
    }

    fun d(
        tag: String,
        message: String,
    ) = logger?.log(LogLevel.DEBUG, tag, message)

    fun i(
        tag: String,
        message: String,
    ) = logger?.log(LogLevel.INFO, tag, message)

    fun w(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) = logger?.log(LogLevel.WARN, tag, message, error)

    fun e(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) = logger?.log(LogLevel.ERROR, tag, message, error)
}
