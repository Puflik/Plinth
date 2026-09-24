package io.github.puflik.plinth.diagnostics.log

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Instant

/** Лог на диске (G1.1): два файла с ротацией, вместе не больше лимита. */
class LogFileWriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val time = Instant.parse("2026-09-24T10:00:00Z")

    @Test
    fun `entries are appended to the log file`() {
        val writer = LogFileWriter(temp.root.resolve("logs"), limitBytes = 10_000)

        writer.write(LogEntry(time, LogLevel.INFO, "App", "started"))
        writer.write(LogEntry(time, LogLevel.WARN, "Scan", "slow"))

        assertThat(writer.files().single().readText())
            .isEqualTo("2026-09-24T10:00:00Z I App: started\n2026-09-24T10:00:00Z W Scan: slow\n")
    }

    @Test
    fun `full file rotates and the total stays under the limit`() {
        val writer = LogFileWriter(temp.root.resolve("logs"), limitBytes = 1_000)

        repeat(100) { writer.write(LogEntry(time, LogLevel.INFO, "Test", "entry number $it")) }

        val files = writer.files()
        assertThat(files).hasSize(2)
        assertThat(files.sumOf { it.length() }).isAtMost(1_000L)
        assertThat(files.last().readText()).contains("entry number 99")
        assertThat(files.first().readText()).doesNotContain("entry number 99")
    }

    @Test
    fun `nothing written means no files`() {
        assertThat(LogFileWriter(temp.root.resolve("logs"), limitBytes = 1_000).files()).isEmpty()
    }
}
