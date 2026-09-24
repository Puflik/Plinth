package io.github.puflik.plinth.diagnostics.log

import kotlin.time.Instant

/** Уровень записи (G1.1); ниже порога логгера записи отбрасываются. */
enum class LogLevel(
    val letter: Char,
) {
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
}

/**
 * Запись лога, уже очищенная [LogRedactor]: приёмники получают только её.
 *
 * @property error стектрейс текстом, тоже очищенный; `null` — без ошибки.
 */
data class LogEntry(
    val time: Instant,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val error: String? = null,
)

/** Куда уходят записи: память, файл, logcat. */
fun interface LogSink {
    fun write(entry: LogEntry)
}

/** Запись строкой: время, уровень буквой, тег, сообщение; стектрейс — следом. */
object LogFormat {
    fun format(entry: LogEntry): String =
        buildString {
            append(entry.time).append(' ').append(entry.level.letter).append(' ')
            append(entry.tag).append(": ").append(entry.message).append('\n')
            entry.error?.let { append(it.trimEnd()).append('\n') }
        }
}
