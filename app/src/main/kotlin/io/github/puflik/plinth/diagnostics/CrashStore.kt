package io.github.puflik.plinth.diagnostics

import java.io.File

/**
 * Отчёт о последнем сбое (G1.3), один файл в [directory]: ждёт, пока при
 * следующем запуске его сохранят или откажутся. Пишется синхронно — в
 * обработчике сбоя больше ничего не успеет.
 */
class CrashStore(
    private val directory: File,
) {
    private val file = File(directory, "last.txt")

    fun save(report: String) {
        directory.mkdirs()
        file.writeText(report)
    }

    fun pending(): String? = file.takeIf(File::isFile)?.readText()

    fun clear() {
        file.delete()
    }
}
