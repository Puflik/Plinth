package io.github.puflik.plinth.ui.start

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.settings.FakeStartSettings
import io.github.puflik.plinth.settings.StartScreen
import io.github.puflik.plinth.startup.DecisionExplanation
import io.github.puflik.plinth.startup.PlayHistory
import io.github.puflik.plinth.startup.StartDecision
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

/** Стартовый экран на запуске (F3): решение один раз, по данным хранилищ. */
@OptIn(ExperimentalCoroutinesApi::class)
class StartViewModelTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val repository = FakeLibraryRepository()
    private val log = StartLog()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `recent play opens the player, once, and the decision is logged`() =
        runTest(UnconfinedTestDispatcher()) {
            repository.upsert(listOf(track()))
            val viewModel = StartViewModel(FakeStartSettings(), repository, played(now - 10.minutes), clock, log)

            val expected = StartDecision(StartDestination.PLAYER, DecisionExplanation.RecentlyPlayed(10.minutes))
            assertThat(viewModel.decision.value).isEqualTo(expected)
            assertThat(viewModel.take()).isEqualTo(expected)
            assertThat(viewModel.take()).isNull()
            assertThat(log.last.value).isEqualTo(expected)
        }

    @Test
    fun `empty library is read from the library`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = StartViewModel(FakeStartSettings(), repository, played(now - 10.minutes), clock, log)

            assertThat(viewModel.decision.value?.explanation).isEqualTo(DecisionExplanation.EmptyLibrary)
        }

    @Test
    fun `chosen start screen is followed`() =
        runTest(UnconfinedTestDispatcher()) {
            repository.upsert(listOf(track()))
            val settings = FakeStartSettings(StartScreen.LIBRARY)
            val viewModel = StartViewModel(settings, repository, played(now - 10.minutes), clock, log)

            assertThat(viewModel.decision.value?.destination).isEqualTo(StartDestination.LIBRARY)
        }

    @Test
    fun `nothing is decided until the play history is read`() =
        runTest(UnconfinedTestDispatcher()) {
            val unread =
                object : PlayHistory {
                    override val lastPlayed: Flow<Instant?> = MutableSharedFlow()
                }
            val viewModel = StartViewModel(FakeStartSettings(), repository, unread, clock, log)

            assertThat(viewModel.decision.value).isNull()
            assertThat(viewModel.take()).isNull()
        }

    private fun played(at: Instant?) =
        object : PlayHistory {
            override val lastPlayed: Flow<Instant?> = flowOf(at)
        }

    private fun track() =
        LibraryTrack(
            id = TrackId("track-1"),
            uri = "/storage/emulated/0/Music/track-1.mp3",
            title = "Yesterday",
            artist = "The Beatles",
            album = "Help!",
            duration = 2.minutes,
            folder = "Music/",
        )
}
