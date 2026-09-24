package io.github.puflik.plinth.diagnostics

import io.github.puflik.plinth.diagnostics.log.LogRedactor
import kotlin.time.Clock

/**
 * Необработанное исключение (G1.3): отчёт — время, поток, сборка и очищенный
 * стектрейс — сохраняется в [store], дальше сбой идёт системному
 * обработчику [previous], как шёл бы без нас. Лог пишется в фоне и при
 * падении может не успеть, поэтому отчёт — отдельным файлом и синхронно.
 * Не удалось сохранить — сбой всё равно уходит дальше.
 */
class CrashHandler(
    private val store: CrashStore,
    private val redactor: LogRedactor,
    private val clock: Clock,
    private val info: AppInfo,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(
        thread: Thread,
        error: Throwable,
    ) {
        runCatching { store.save(report(thread, error)) }
        previous?.uncaughtException(thread, error)
    }

    private fun report(
        thread: Thread,
        error: Throwable,
    ): String =
        "Crash at ${clock.now()} on thread ${thread.name}\n${info.line}\n" +
            redactor.redact(error.stackTraceToString())

    companion object {
        /** Встать перед системным обработчиком процесса. */
        fun install(
            store: CrashStore,
            redactor: LogRedactor,
            clock: Clock,
            info: AppInfo,
        ) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(store, redactor, clock, info, previous))
        }
    }
}
