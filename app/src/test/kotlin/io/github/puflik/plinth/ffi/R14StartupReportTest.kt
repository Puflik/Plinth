package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.core.AppError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import io.github.puflik.plinth.ffi.generated.StartupReport as RustStartupReport

/**
 * Р1.4 (`docs/work/r1-4.md`): отчёт ядра о запуске говорит, что журнал начат
 * заново, а весть об этом ждёт первого слушателя — ядро открывается раньше
 * экранов.
 */
class R14StartupReportTest {
    @Test
    fun `the report keeps the journal started over`() {
        val report = RustStartupReport(databaseRecovered = false, restoredFromJournal = true, journalStartedOver = true)

        assertThat(report.toApp())
            .isEqualTo(StartupReport(databaseRecovered = false, restoredFromJournal = true, journalStartedOver = true))
    }

    @Test
    fun `a journal started over waits for the first listener`() =
        runTest {
            val errors = CoreErrors()

            errors.journalStartedOver(carried = false)

            assertThat(withTimeout(1.seconds) { errors.events.first() })
                .isEqualTo(AppError.JournalStartedOver(carried = false))
        }
}
