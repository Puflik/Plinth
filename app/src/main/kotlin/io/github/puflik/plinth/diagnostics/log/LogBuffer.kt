package io.github.puflik.plinth.diagnostics.log

/** Последние [capacity] записей в памяти (G1.1): их видно без чтения файла. */
class LogBuffer(
    private val capacity: Int,
) : LogSink {
    private val entries = ArrayDeque<LogEntry>(capacity)

    override fun write(entry: LogEntry) {
        synchronized(entries) {
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast(entry)
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(entries) { entries.toList() }
}
