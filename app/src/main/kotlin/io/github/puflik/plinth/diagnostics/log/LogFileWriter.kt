package io.github.puflik.plinth.diagnostics.log

import java.io.File

/**
 * Лог на диске (G1.1): текущий файл и предыдущий, каждый не больше половины
 * [limitBytes] — вместе не больше лимита. Заполненный текущий становится
 * предыдущим, прежний предыдущий удаляется. Пишет вызывающий поток —
 * в приложении это фоновый поток [BackgroundSink].
 */
class LogFileWriter(
    private val directory: File,
    private val limitBytes: Long,
) : LogSink {
    private val current = File(directory, "plinth.log")
    private val previous = File(directory, "plinth.1.log")

    override fun write(entry: LogEntry) {
        val line = LogFormat.format(entry).toByteArray()
        synchronized(this) {
            directory.mkdirs()
            if (current.length() + line.size > limitBytes / 2) rotate()
            current.appendBytes(line)
        }
    }

    /** Файлы лога от старого к новому — для выгрузки. */
    fun files(): List<File> = synchronized(this) { listOf(previous, current).filter(File::exists) }

    private fun rotate() {
        previous.delete()
        current.renameTo(previous)
    }
}
