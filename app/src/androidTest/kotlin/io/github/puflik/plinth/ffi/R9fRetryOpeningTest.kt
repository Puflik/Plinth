package io.github.puflik.plinth.ffi

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.diagnostics.log.LogLevel
import kotlinx.coroutines.flow.first
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
 * Р9е (`docs/work/r9f.md`, ревью R1 №7) на настоящем ядре: открытие, которое не удалось с
 * первой попытки, а удалось по повтору («Повторить» на экране или любой вызов API), говорит о
 * том, что сделало, как первое. Раньше отчёт читал только `CoreInitializer` из своей
 * единственной попытки, и весть «журнал начат заново» терялась.
 */
class R9fRetryOpeningTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "core-r9f-" + UUID.randomUUID())

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /**
     * Снимок испорчен, базы нет — переносить в новый журнал нечего. Первое открытие падает на
     * записи имени установки (на месте временного файла каталог): отказ, вести нет. Каталог
     * убрали, повтор открылся — весть «журнал начат заново, верните из копии» приходит, и
     * ядро помнит, что данные не вернулись.
     */
    @Test
    fun a_journal_started_over_on_the_retry_is_told_and_leaves_a_reminder() =
        runBlocking {
            liked()
            val snapshot = File(dir, "journal/snapshot")
            val bytes = snapshot.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0xFF).toByte()
            snapshot.writeBytes(bytes)
            listOf("library.db", "library.db-wal", "library.db-shm").forEach { File(dir, it).delete() }
            val blocker = File(dir, "device.tmp").also { it.mkdir() }
            val errors = CoreErrors()
            val core = PlinthCore(LogLevel.INFO, dir, errors)
            try {
                assertThrows(CoreFailure::class.java) { core.open() }
                assertThat(core.opening.value).isEqualTo(CoreOpening.FAILED)
                assertThat(withTimeoutOrNull(1.seconds) { errors.events.first() }).isNull()

                blocker.delete()
                core.open()

                assertThat(core.opening.value).isEqualTo(CoreOpening.OPEN)
                assertThat(withTimeout(5.seconds) { errors.events.first() })
                    .isEqualTo(AppError.JournalStartedOver(carried = false))
                assertThat(core.mirror.restorePending()).isTrue()
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
