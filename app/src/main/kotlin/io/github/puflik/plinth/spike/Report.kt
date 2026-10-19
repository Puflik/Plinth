package io.github.puflik.plinth.spike

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Спайк Ю3а (одноразовый код, в `main` не идёт): журнал прогона. Каждая строка
 * идёт в logcat (тег `Yu3a`) и в файл, чтобы release-сборку (без `run-as`)
 * можно было прочитать `adb pull`. Время — от старта процесса.
 */
class Report(
    private val file: File?,
) {
    private val started = Process.getStartElapsedRealtime()
    private val tail = ArrayDeque<String>()

    @Volatile
    var onChange: ((String) -> Unit)? = null

    @Synchronized
    fun line(text: String) {
        val stamp = "[+%7.3fs] ".format((SystemClock.elapsedRealtime() - started) / 1000.0)
        val full = stamp + text
        Log.i(TAG, full)
        runCatching { file?.appendText(full + "\n") }
        tail.addLast(full)
        while (tail.size > TAIL) tail.removeFirst()
        onChange?.invoke(tail.joinToString("\n"))
    }

    fun error(
        where: String,
        t: Throwable,
    ) {
        line("ERROR $where: ${t.javaClass.name}: ${t.message}")
        Log.e(TAG, where, t)
        var cause = t.cause
        var depth = 0
        while (cause != null && depth < 4) {
            line("  caused by ${cause.javaClass.name}: ${cause.message}")
            cause = cause.cause
            depth++
        }
        val frame = t.stackTrace.take(3).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        line("  at $frame")
    }

    companion object {
        const val TAG = "Yu3a"
        private const val TAIL = 40
    }
}
