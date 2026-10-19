package io.github.puflik.plinth.ui.search

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.online.FakeOnlineRepository
import io.github.puflik.plinth.online.FakeOnlineSettings
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Р1.11г (`docs/work/r1-11d.md`, находки R1 №14 и №15): в онлайн-секции поиска есть «Повторить», а возврат на
 * экран через пять секунд (когда `WhileSubscribed` уже остановил поток) не запускает сетевой поиск заново и
 * не проходит через пустой кадр — иначе список прыгает в начало, а найденное может смениться на «Нет сети».
 *
 * `SearchViewModel.onRetryOnline()` — новый API (сигнатура в файле задачи). Отмена идущего поиска при уходе
 * с экрана (Р1.11а) остаётся: её стережёт `SearchViewModelCancelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelRetryTest {
    private val dispatcher = StandardTestDispatcher()
    private val library = FakeLibraryRepository()
    private val online = FakeOnlineRepository()
    private val onlineSettings = FakeOnlineSettings()
    private val actions =
        TrackActions(
            PlaybackController(FakeAudioEngine(), CoroutineScope(Dispatchers.Unconfined)),
            FakeUserDataRepository(),
            FakePlaylistRepository(),
            CoroutineScope(Dispatchers.Unconfined),
        )
    private val viewModel by lazy { SearchViewModel(library, online, onlineSettings, actions) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `retry asks the network the same question again`() =
        runTest(dispatcher) {
            online.setEnabled(true)
            online.networkUp = false
            screen()
            search("concert")
            val failed = onlineSections().single().problem

            online.networkUp = true
            viewModel.onRetryOnline()
            runCurrent()

            assertThat(failed).isEqualTo(OnlineProblem.NO_NETWORK)
            val answer = onlineSections().single()
            assertThat(answer.problem).isNull()
            assertThat(answer.results).isNotEmpty()
            assertThat(online.searches).containsExactly("concert", "concert").inOrder()
        }

    @Test
    fun `retry shows the search in progress and then the answer`() =
        runTest(dispatcher) {
            online.setEnabled(true)
            online.networkUp = false
            screen()
            search("concert")
            online.networkUp = true
            val gate = CompletableDeferred<Unit>()
            online.gate = gate

            viewModel.onRetryOnline()
            runCurrent()
            val waiting = viewModel.uiState.value.online
            gate.complete(Unit)
            runCurrent()

            assertThat(waiting).isEqualTo(OnlineResults.Searching)
            assertThat(onlineSections().single().problem).isNull()
        }

    @Test
    fun `retry with no text or with the sources off asks nobody`() =
        runTest(dispatcher) {
            online.setEnabled(true)
            screen()

            viewModel.onRetryOnline()
            runCurrent()
            val withoutText = viewModel.uiState.value.online
            onlineSettings.setEnabled(false)
            search("concert")
            viewModel.onRetryOnline()
            runCurrent()

            assertThat(withoutText).isEqualTo(OnlineResults.None)
            assertThat(viewModel.uiState.value.online).isEqualTo(OnlineResults.None)
            assertThat(online.searches).isEmpty()
        }

    @Test
    fun `coming back after the grace period shows the same screen at once and asks nobody`() =
        runTest(dispatcher) {
            online.setEnabled(true)
            library.upsert(listOf(track(1, "Concert Song", "Plinth Band")))
            val first = screen()
            search("concert")
            val before = viewModel.uiState.value
            first.cancel()
            advanceTimeBy(GRACE_MS)
            runCurrent()

            val seen = everyFrameOfTheNextScreen()
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertThat(before.results).isNotEmpty()
            assertThat(before.online).isInstanceOf(OnlineResults.Found::class.java)
            assertThat(seen).isNotEmpty()
            assertThat(seen.distinct()).containsExactly(before)
            assertThat(online.searches).containsExactly("concert")
        }

    @Test
    fun `an answer with a problem is kept too and only the retry asks again`() =
        runTest(dispatcher) {
            online.setEnabled(true)
            online.networkUp = false
            val first = screen()
            search("concert")
            val failed = viewModel.uiState.value
            first.cancel()
            advanceTimeBy(GRACE_MS)
            runCurrent()
            online.networkUp = true

            val seen = everyFrameOfTheNextScreen()
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            val framesOnReturn = seen.distinct()
            viewModel.onRetryOnline()
            runCurrent()

            assertThat(framesOnReturn).containsExactly(failed)
            assertThat(onlineSections().single().problem).isNull()
            assertThat(online.searches).containsExactly("concert", "concert").inOrder()
        }

    @Test
    fun `another text is asked afresh after coming back`() =
        runTest(dispatcher) {
            online.setEnabled(true)
            val first = screen()
            search("concert")
            first.cancel()
            advanceTimeBy(GRACE_MS)
            runCurrent()
            screen()

            search("grateful")

            assertThat(online.searches).containsExactly("concert", "grateful").inOrder()
            assertThat(onlineSections().single().results).isEmpty()
        }

    /** Экран подписался на состояние; `cancel()` — ушёл. */
    private fun TestScope.screen(): Job = backgroundScope.launch { viewModel.uiState.collect {} }

    /** Каждый кадр, который увидит экран, вернувшийся после ухода: сборщик без задержки, ничего не теряется. */
    private fun TestScope.everyFrameOfTheNextScreen(): List<SearchUiState> {
        val seen = mutableListOf<SearchUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { seen += it } }
        return seen
    }

    private fun TestScope.search(text: String) {
        viewModel.onQuery(text)
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
        runCurrent()
    }

    private fun onlineSections() = (viewModel.uiState.value.online as OnlineResults.Found).sections

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

    private companion object {
        // Пять секунд льготы `WhileSubscribed` и запас: поток остановлен, экран вернётся «с нуля».
        const val GRACE_MS = 6_000L
    }
}
