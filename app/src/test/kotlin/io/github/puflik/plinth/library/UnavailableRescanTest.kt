package io.github.puflik.plinth.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.core.FailedTrack
import io.github.puflik.plinth.core.TrackProblem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Недоступный трек библиотеки — один скан за запуск; решение автора (G3). */
@OptIn(ExperimentalCoroutinesApi::class)
class UnavailableRescanTest {
    private val errors = MutableSharedFlow<AppError>(extraBufferCapacity = 8)
    private val scan = CountingScan()
    private val gone = FailedTrack("Yesterday", "content://media/external/audio/media/7", TrackProblem.UNAVAILABLE)

    @Test
    fun `unavailable library track asks for one rescan per launch`() =
        runTest(UnconfinedTestDispatcher()) {
            rescan().start()

            errors.emit(AppError.TracksSkipped(listOf(gone)))
            errors.emit(AppError.PlaybackStopped(gone))

            assertThat(scan.starts).isEqualTo(1)
        }

    @Test
    fun `stop on an unavailable library track counts too`() =
        runTest(UnconfinedTestDispatcher()) {
            rescan().start()

            errors.emit(AppError.PlaybackStopped(gone.copy(problem = TrackProblem.NETWORK), skipped = listOf(gone)))

            assertThat(scan.starts).isEqualTo(1)
        }

    /** Файл из «Открыть файл» сканер не видит, а битый файл на месте — сканировать нечего. */
    @Test
    fun `picked files and damaged files do not rescan`() =
        runTest(UnconfinedTestDispatcher()) {
            rescan().start()
            val picked = gone.copy(file = "content://com.android.providers.downloads.documents/document/42")
            val damaged = gone.copy(problem = TrackProblem.UNPLAYABLE)

            errors.emit(AppError.TracksSkipped(listOf(picked, damaged)))

            assertThat(scan.starts).isEqualTo(0)
        }

    private fun TestScope.rescan() = UnavailableRescan(errors, scan, backgroundScope)

    private class CountingScan : LibraryScan {
        override val progress = MutableStateFlow<ScanProgress>(ScanProgress.Idle)
        var starts = 0

        override fun start() {
            starts++
        }

        override fun cancel() = Unit
    }
}
