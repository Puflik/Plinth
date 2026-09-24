package io.github.puflik.plinth.diagnostics.log

import kotlin.time.Clock

/**
 * Фасад логирования (G1.1): отбрасывает записи ниже [minLevel], вырезает
 * лишнее ([LogRedactor]) и отдаёт запись приёмникам. До приёмников сырой
 * текст не доходит — ни в память, ни в файл, ни в logcat.
 */
class Logger(
    private val sinks: List<LogSink>,
    private val redactor: LogRedactor,
    private val clock: Clock,
    private val minLevel: LogLevel,
) {
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        error: Throwable? = null,
    ) {
        if (level < minLevel) return
        val entry =
            LogEntry(
                time = clock.now(),
                level = level,
                tag = tag,
                message = redactor.redact(message),
                error = error?.let { redactor.redact(it.stackTraceToString()) },
            )
        sinks.forEach { it.write(entry) }
    }
}
