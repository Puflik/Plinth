package io.github.puflik.plinth.ffi

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.diagnostics.log.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Р1.4 (`docs/work/r1-4.md`) на настоящем ядре: не открылось — фасад говорит
 * об этом в [PlinthCore.opening] и открывается по повтору; испорченный снимок
 * журнала ядро не запирает, а экраны узнают, что журнал начат заново.
 */
class R14CoreOpeningTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "core-r14-" + UUID.randomUUID())
    private val snapshot = File(dir, "journal/snapshot")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /**
     * Журнал более новой версии — ядро не открывается; вернули прежний —
     * открывается повтором, а списки перечитываются.
     */
    @Test
    fun a_core_that_did_not_open_says_so_and_opens_on_retry() {
        liked()
        val bytes = snapshot.readBytes()
        snapshot.writeBytes(bytes.copyOf().also { it[4]++ })
        val core = PlinthCore(LogLevel.INFO, dir, CoreErrors())
        try {
            assertThat(core.opening.value).isEqualTo(CoreOpening.PENDING)
            assertThrows(CoreFailure::class.java) { core.open() }
            assertThat(core.opening.value).isEqualTo(CoreOpening.FAILED)

            snapshot.writeBytes(bytes)
            val catalog = core.catalogChanges.value
            core.open()

            assertThat(core.opening.value).isEqualTo(CoreOpening.OPEN)
            assertThat(core.catalogChanges.value).isGreaterThan(catalog)
        } finally {
            core.close()
        }
    }

    /**
     * Испорченный снимок: ядро открылось, лайк перенесён из базы — одна весть,
     * «журнал начат заново», а не «библиотека восстановлена».
     */
    @Test
    fun a_damaged_journal_is_told_as_started_over() =
        runBlocking {
            liked()
            val bytes = snapshot.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0xFF).toByte()
            snapshot.writeBytes(bytes)
            val errors = CoreErrors()
            val core = PlinthCore(LogLevel.INFO, dir, errors)
            try {
                CoreInitializer(core, this, Dispatchers.IO, errors).start()

                assertThat(withTimeout(5.seconds) { errors.events.first() })
                    .isEqualTo(AppError.JournalStartedOver(carried = true))
                assertThat(withTimeoutOrNull(1.seconds) { errors.events.take(2).toList() }).isNull()
                assertThat(core.opening.value).isEqualTo(CoreOpening.OPEN)
            } finally {
                core.close()
            }
        }

    /** Ядро с одним лайком; закрыто — его файлы свободны. */
    private fun liked() {
        val core = PlinthCore(LogLevel.INFO, dir, CoreErrors())
        core.journal.like(TrackId(UUID.randomUUID().toString()))
        core.close()
    }
}
