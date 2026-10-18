package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.core.AppError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Р9е (`docs/work/r9f.md`, ревью R1 №7): что сделало открытие ядра, узнают по отчёту того
 * открытия, которое удалось, — первого или любого повтора. [CoreErrors.opened] превращает
 * отчёт в весть; фасад зовёт его там, где ядро открылось, а не только в `CoreInitializer`.
 */
class R9fOpeningReportTest {
    private val errors = CoreErrors()

    private fun opened(
        databaseRecovered: Boolean = false,
        restoredFromJournal: Boolean = false,
        journalStartedOver: Boolean = false,
    ) = errors.opened(StartupReport(databaseRecovered, restoredFromJournal, journalStartedOver))

    @Test
    fun `a journal started over from the library is announced`() =
        runTest {
            opened(restoredFromJournal = true, journalStartedOver = true)

            assertThat(withTimeout(1.seconds) { errors.events.first() })
                .isEqualTo(AppError.JournalStartedOver(carried = true))
        }

    @Test
    fun `a journal started over with nothing carried says the copy is the only way back`() =
        runTest {
            opened(databaseRecovered = true, journalStartedOver = true)

            assertThat(withTimeout(1.seconds) { errors.events.first() })
                .isEqualTo(AppError.JournalStartedOver(carried = false))
        }

    @Test
    fun `a rebuilt database is announced as a restored library`() =
        runTest {
            opened(databaseRecovered = true)

            assertThat(withTimeout(1.seconds) { errors.events.first() }).isEqualTo(AppError.LibraryRestored)
        }

    @Test
    fun `a library rebuilt from the journal is announced as restored`() =
        runTest {
            opened(restoredFromJournal = true)

            assertThat(withTimeout(1.seconds) { errors.events.first() }).isEqualTo(AppError.LibraryRestored)
        }

    @Test
    fun `an ordinary opening says nothing`() =
        runTest {
            opened()

            assertThat(withTimeoutOrNull(1.seconds) { errors.events.first() }).isNull()
        }
}
