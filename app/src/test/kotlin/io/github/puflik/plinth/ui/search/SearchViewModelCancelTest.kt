package io.github.puflik.plinth.ui.search

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.online.FakeOnlineRepository
import io.github.puflik.plinth.online.FakeOnlineSettings
import io.github.puflik.plinth.online.OnlineRepository
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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

/**
 * Р1.11а (`docs/work/r1-11a.md`, находка 10 ревью v0.2): запрос к сети
 * отменяется, когда запрос сменили или ушли с экрана. Отмена доходит до
 * [OnlineRepository.search] как отмена корутины (`flatMapLatest`,
 * `WhileSubscribed`); что `CoreOnlineRepository` в ответ на неё возвращается
 * сразу и останавливает ядро, проверяет `CoreOnlineRepositoryCancelTest`.
 * Экран не меняется — эти тесты стерегут его договор с репозиторием.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelCancelTest {
    private val dispatcher = StandardTestDispatcher()
    private val playback = PlaybackController(FakeAudioEngine(), CoroutineScope(Dispatchers.Unconfined))
    private val actions =
        TrackActions(
            playback,
            FakeUserDataRepository(),
            FakePlaylistRepository(),
            CoroutineScope(Dispatchers.Unconfined),
        )
    private val online = SilentOnline()
    private val viewModel by lazy {
        SearchViewModel(FakeLibraryRepository(), online, FakeOnlineSettings(), actions)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a new query cancels the search in flight`() =
        runTest(dispatcher) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onQuery("grateful")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertThat(online.started).containsExactly("grateful")

            viewModel.onQuery("grateful dead")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()

            assertThat(online.cancelled).containsExactly("grateful")
            assertThat(online.started).containsExactly("grateful", "grateful dead").inOrder()
        }

    @Test
    fun `leaving the screen cancels the search once the grace period is over`() =
        runTest(dispatcher) {
            val screen = backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onQuery("grateful")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()

            screen.cancel()
            advanceTimeBy(GRACE_BEFORE_MS)
            runCurrent()
            val whileGrace = online.cancelled.toList()
            advanceTimeBy(GRACE_AFTER_MS)
            runCurrent()

            assertThat(whileGrace).isEmpty()
            assertThat(online.cancelled).containsExactly("grateful")
        }

    /** Страж: поворот экрана — это «ушли и вернулись» в пределах льготных секунд; поиск не теряется. */
    @Test
    fun `coming back within the grace period keeps the search`() =
        runTest(dispatcher) {
            val screen = backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onQuery("grateful")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            screen.cancel()
            advanceTimeBy(GRACE_BEFORE_MS)
            runCurrent()

            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceTimeBy(GRACE_BEFORE_MS * 2)
            runCurrent()

            assertThat(online.started).containsExactly("grateful")
            assertThat(online.cancelled).isEmpty()
        }

    /** Сеть, которая не отвечает: поиск ждёт отмены и записывает, чей запрос отменили. */
    private class SilentOnline(
        private val inner: FakeOnlineRepository = FakeOnlineRepository(),
    ) : OnlineRepository by inner {
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override suspend fun search(query: String): List<OnlineSection> {
            started += query
            try {
                awaitCancellation()
            } catch (cancellation: CancellationException) {
                cancelled += query
                throw cancellation
            }
        }
    }

    private companion object {
        // `WhileSubscribed(5_000)` у экрана: поворот не должен терять поиск.
        const val GRACE_BEFORE_MS = 4_000L
        const val GRACE_AFTER_MS = 1_500L
    }
}
