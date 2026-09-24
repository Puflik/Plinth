package io.github.puflik.plinth.diagnostics.log

import android.util.Log

/** Записи в logcat — только в отладочной сборке; текст уже очищен. */
class LogcatSink : LogSink {
    override fun write(entry: LogEntry) {
        val tag = "Plinth/${entry.tag}"
        val text = entry.error?.let { "${entry.message}\n$it" } ?: entry.message
        when (entry.level) {
            LogLevel.DEBUG -> Log.d(tag, text)
            LogLevel.INFO -> Log.i(tag, text)
            LogLevel.WARN -> Log.w(tag, text)
            LogLevel.ERROR -> Log.e(tag, text)
        }
    }
}
