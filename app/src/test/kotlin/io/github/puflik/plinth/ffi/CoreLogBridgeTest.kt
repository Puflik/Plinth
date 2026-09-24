package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.diagnostics.log.LogBuffer
import io.github.puflik.plinth.diagnostics.log.LogEntry
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.diagnostics.log.LogRedactor
import io.github.puflik.plinth.diagnostics.log.Logger
import io.github.puflik.plinth.ffi.generated.CoreLogLevel
import io.github.puflik.plinth.ffi.generated.CoreLogRecord
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Instant

/** Логи Rust → `AppLog` (A3.4): уровень, тег-модуль, то же вырезание, что у Kotlin. */
class CoreLogBridgeTest {
    private val now = Instant.parse("2026-09-24T21:40:00Z")
    private val buffer = LogBuffer(capacity = 10)
    private val bridge = CoreLogBridge()

    @Before
    fun setUp() {
        val clock =
            object : Clock {
                override fun now() = now
            }
        AppLog.install(Logger(listOf(buffer), LogRedactor(), clock, minLevel = LogLevel.DEBUG))
    }

    @After
    fun tearDown() {
        AppLog.install(null)
    }

    @Test
    fun `rust record lands in the app log under its module`() {
        bridge.log(CoreLogRecord(CoreLogLevel.INFO, "plinth_ffi", "Plinth core 0.2.0 ready"))

        assertThat(buffer.snapshot())
            .containsExactly(LogEntry(now, LogLevel.INFO, "plinth_ffi", "Plinth core 0.2.0 ready"))
    }

    @Test
    fun `every rust level has its app level, trace is written as debug`() {
        val levels =
            CoreLogLevel.entries.map { level ->
                bridge.log(CoreLogRecord(level, "plinth_ffi", level.name))
                buffer.snapshot().last().level
            }

        assertThat(levels)
            .containsExactly(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO, LogLevel.DEBUG, LogLevel.DEBUG)
            .inOrder()
    }

    @Test
    fun `rust text is redacted like any other`() {
        bridge.log(CoreLogRecord(CoreLogLevel.WARN, "plinth_library", "cannot open /storage/emulated/0/Music/a.flac"))

        assertThat(buffer.snapshot().single().message).isEqualTo("cannot open <path>.flac")
    }

    @Test
    fun `app log level becomes the core filter`() {
        val filters = LogLevel.entries.associateWith { it.toCore() }

        assertThat(filters)
            .containsExactly(
                LogLevel.DEBUG,
                CoreLogLevel.DEBUG,
                LogLevel.INFO,
                CoreLogLevel.INFO,
                LogLevel.WARN,
                CoreLogLevel.WARN,
                LogLevel.ERROR,
                CoreLogLevel.ERROR,
            )
    }
}
