package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.generated.CoreLogLevel
import io.github.puflik.plinth.ffi.generated.CoreLogRecord
import io.github.puflik.plinth.ffi.generated.CoreLogger

/**
 * Приёмник логов Rust (A3.4): запись ядра становится записью [AppLog] с
 * модулем-источником вместо тега, дальше — то же вырезание, файл и logcat.
 *
 * Вызывается из потоков ядра. Не бросает: исключение отсюда пришло бы в
 * Rust паникой посреди записи лога.
 */
internal class CoreLogBridge : CoreLogger {
    override fun log(record: CoreLogRecord) {
        when (record.level) {
            CoreLogLevel.ERROR -> AppLog.e(record.target, record.message)
            CoreLogLevel.WARN -> AppLog.w(record.target, record.message)
            CoreLogLevel.INFO -> AppLog.i(record.target, record.message)
            CoreLogLevel.DEBUG, CoreLogLevel.TRACE -> AppLog.d(record.target, record.message)
        }
    }
}

/** Порог лога приложения — он же фильтр в ядре: отброшенное не пересекает границу. */
internal fun LogLevel.toCore(): CoreLogLevel =
    when (this) {
        LogLevel.DEBUG -> CoreLogLevel.DEBUG
        LogLevel.INFO -> CoreLogLevel.INFO
        LogLevel.WARN -> CoreLogLevel.WARN
        LogLevel.ERROR -> CoreLogLevel.ERROR
    }
