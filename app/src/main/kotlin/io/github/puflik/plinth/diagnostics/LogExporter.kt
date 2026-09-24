package io.github.puflik.plinth.diagnostics

import java.io.File
import java.io.OutputStream
import kotlin.time.Clock

/**
 * Отчёт одним текстовым файлом (G1.3): шапка со сборкой и устройством,
 * последний сбой, если был, и лог от старого файла к новому. Лог уже
 * очищен при записи — здесь его только склеивают.
 */
class LogExporter(
    private val info: AppInfo,
    private val clock: Clock,
) {
    fun export(
        crash: String?,
        logs: List<File>,
        out: OutputStream,
    ) {
        out.bufferedWriter().use { writer ->
            writer.append("Plinth report, ${clock.now()}\n").append(info.line).append("\n\n")
            crash?.let { writer.append("== Crash ==\n").append(it.trimEnd()).append("\n\n") }
            writer.append("== Log ==\n")
            logs.forEach { file -> file.bufferedReader().use { it.copyTo(writer) } }
        }
    }
}
