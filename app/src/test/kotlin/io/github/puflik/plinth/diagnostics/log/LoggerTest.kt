package io.github.puflik.plinth.diagnostics.log

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Instant

/** Фасад логирования (G1.1): уровни, вырезание до приёмников, буфер в памяти. */
class LoggerTest {
    private val now = Instant.parse("2026-09-24T10:12:03.123Z")
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val buffer = LogBuffer(capacity = 3)

    @After
    fun tearDown() {
        AppLog.install(null)
    }

    @Test
    fun `entries below the minimum level are dropped`() {
        val logger = Logger(listOf(buffer), LogRedactor(), clock, minLevel = LogLevel.INFO)

        logger.log(LogLevel.DEBUG, "Scan", "chatty")
        logger.log(LogLevel.WARN, "Scan", "slow")

        assertThat(buffer.snapshot()).containsExactly(LogEntry(now, LogLevel.WARN, "Scan", "slow"))
    }

    @Test
    fun `sinks get redacted text, error traces included`() {
        val logger = Logger(listOf(buffer), LogRedactor(), clock, minLevel = LogLevel.DEBUG)
        val error = IllegalStateException("cannot open /storage/emulated/0/Music/secret.mp3")

        logger.log(LogLevel.ERROR, "Playback", "failed on content://media/external/audio/media/42", error)

        val entry = buffer.snapshot().single()
        assertThat(entry.message).isEqualTo("failed on content://<redacted>")
        assertThat(entry.error).contains("IllegalStateException: cannot open <path>.mp3")
        assertThat(entry.error).contains("at io.github.puflik.plinth")
        assertThat(entry.error).doesNotContain("secret")
    }

    @Test
    fun `buffer keeps the last entries only`() {
        val logger = Logger(listOf(buffer), LogRedactor(), clock, minLevel = LogLevel.DEBUG)

        (1..5).forEach { logger.log(LogLevel.INFO, "Test", "entry $it") }

        assertThat(buffer.snapshot().map { it.message }).containsExactly("entry 3", "entry 4", "entry 5").inOrder()
    }

    @Test
    fun `entry is one readable line, the trace follows it`() {
        val entry = LogEntry(now, LogLevel.WARN, "Scan", "slow", error = "java.lang.Exception: x\n\tat a.B.c(B.kt:1)")

        assertThat(LogFormat.format(entry))
            .isEqualTo("2026-09-24T10:12:03.123Z W Scan: slow\njava.lang.Exception: x\n\tat a.B.c(B.kt:1)\n")
    }

    @Test
    fun `global facade is silent until a logger is installed`() {
        AppLog.i("Test", "nobody listens")

        AppLog.install(Logger(listOf(buffer), LogRedactor(), clock, minLevel = LogLevel.DEBUG))
        AppLog.i("Test", "heard")

        assertThat(buffer.snapshot().map { it.message }).containsExactly("heard")
    }
}
