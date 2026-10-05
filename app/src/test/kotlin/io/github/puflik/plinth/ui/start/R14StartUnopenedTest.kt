package io.github.puflik.plinth.ui.start

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.CoreOpening
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.LibraryRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.sort.TrackSort
import io.github.puflik.plinth.settings.FakeStartSettings
import io.github.puflik.plinth.startup.PlayHistory
import io.github.puflik.plinth.startup.StartDestination
import io.github.puflik.plinth.startup.StartLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Р1.4 (`docs/work/r1-4.md`): ядро не открылось — треков не будет, и стартовое
 * решение их не ждёт. Иначе приложение так и стоит на фоне темы, а
 * объяснение — на экране библиотеки, до которого не дойти.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R14StartUnopenedTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val base = FakeLibraryRepository()

    /** Как ядро, которое не открылось: треков нет и не будет. */
    private val unopened =
        object : LibraryRepository by base {
            override fun tracks(sort: TrackSort): Flow<List<LibraryTrack>> = MutableSharedFlow()
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a library that did not open does not hold the app on a blank screen`() =
        runTest(UnconfinedTestDispatcher()) {
            base.opening.value = CoreOpening.FAILED

            val viewModel = StartViewModel(FakeStartSettings(), unopened, played(now - 10.minutes), clock, StartLog())

            assertThat(viewModel.decision.value?.destination).isEqualTo(StartDestination.LIBRARY)
        }

    /** Ядро ещё открывается — решение ждёт треков, как и до задачи. */
    @Test
    fun `while the library is opening the decision waits for it`() =
        runTest(UnconfinedTestDispatcher()) {
            base.opening.value = CoreOpening.PENDING

            val viewModel = StartViewModel(FakeStartSettings(), unopened, played(now - 10.minutes), clock, StartLog())

            assertThat(viewModel.decision.value).isNull()
        }

    private fun played(at: Instant?) =
        object : PlayHistory {
            override val lastPlayed: Flow<Instant?> = flowOf(at)
        }
}
