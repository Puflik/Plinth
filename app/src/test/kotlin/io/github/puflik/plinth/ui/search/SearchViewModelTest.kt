package io.github.puflik.plinth.ui.search

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.LibraryRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/** Мгновенный поиск (C4.4): запрос уходит в библиотеку, когда набор замер. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = FakeLibraryRepository()
    private val library = CountingRepository(fake)
    private val engine = FakeAudioEngine()
    private val playback = PlaybackController(engine, CoroutineScope(Dispatchers.Unconfined))
    private val userData = FakeUserDataRepository()
    private val actions = TrackActions(playback, userData, CoroutineScope(Dispatchers.Unconfined))
    private val viewModel by lazy { SearchViewModel(library, actions) }

    private val yesterday = track(1, "Yesterday", "The Beatles")
    private val bohemian = track(2, "Bohemian Rhapsody", "Queen")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `typed text shows at once, results only after a pause`() =
        runTest(dispatcher) {
            fake.upsert(listOf(yesterday, bohemian))
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onQuery("b")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS / 2)
            viewModel.onQuery("be")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS / 2)
            viewModel.onQuery("beat")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS - 1)
            runCurrent()

            assertThat(viewModel.uiState.value.query).isEqualTo("beat")
            assertThat(viewModel.uiState.value.results).isEmpty()
            assertThat(library.searches).isEmpty()

            advanceTimeBy(2)
            runCurrent()

            assertThat(viewModel.uiState.value.results).containsExactly(yesterday)
            assertThat(library.searches).containsExactly("beat")
        }

    @Test
    fun `cleared query shows nothing without asking the library`() =
        runTest(dispatcher) {
            fake.upsert(listOf(yesterday))
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onQuery("yes")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()

            viewModel.onQuery("  ")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()

            assertThat(viewModel.uiState.value.results).isEmpty()
            assertThat(viewModel.uiState.value.searched).isFalse()
            assertThat(library.searches).containsExactly("yes")
        }

    @Test
    fun `query without matches is marked as searched`() =
        runTest(dispatcher) {
            fake.upsert(listOf(yesterday))
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onQuery("zzz")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()

            assertThat(viewModel.uiState.value.results).isEmpty()
            assertThat(viewModel.uiState.value.searched).isTrue()
        }

    @Test
    fun `tapped result plays the results from it`() =
        runTest(dispatcher) {
            fake.upsert(listOf(yesterday, bohemian))
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onQuery("e")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()

            viewModel.onTrack(bohemian, TrackAction.PLAY)

            assertThat(engine.preparedSources).containsExactly(AudioSource.LocalFile(bohemian.uri))
            assertThat(playback.queue.value.context).isEqualTo(QueueContext.Search("e"))
            assertThat(
                playback.queue.value.upcoming
                    .map { it.title },
            ).containsExactly("Yesterday")
        }

    /** Запоминает, с чем библиотеку спрашивали. */
    private class CountingRepository(
        private val delegate: LibraryRepository,
    ) : LibraryRepository by delegate {
        val searches = mutableListOf<String>()

        override fun search(query: String): Flow<List<LibraryTrack>> {
            searches += query
            return delegate.search(query)
        }
    }

    private fun track(
        id: Long,
        title: String,
        artist: String,
    ) = LibraryTrack(
        id = TrackId("track-$id"),
        uri = "/storage/emulated/0/Music/track-$id.mp3",
        title = title,
        artist = artist,
        duration = 3.minutes,
        folder = "Music/",
    )
}
