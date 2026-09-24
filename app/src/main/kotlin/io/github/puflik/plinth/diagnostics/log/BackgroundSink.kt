package io.github.puflik.plinth.diagnostics.log

import java.util.concurrent.Executor

/** Отдаёт записи [sink] в фоновом [executor]: запись в файл не тормозит главный поток. */
class BackgroundSink(
    private val sink: LogSink,
    private val executor: Executor,
) : LogSink {
    override fun write(entry: LogEntry) {
        executor.execute { sink.write(entry) }
    }
}
