package io.github.puflik.plinth.ui.search

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.online.FakeOnlineRepository
import io.github.puflik.plinth.online.FakeOnlineSettings
import io.github.puflik.plinth.online.OnlineProviderIds
import io.github.puflik.plinth.online.OnlineRepository
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** yu5f: observes the existing UI API; gates stand for independent provider replies, never live network. */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu5fProgressiveSearchTest {
    private val dispatcher = StandardTestDispatcher()
    private val online = ControlledOnline()
    private val settings = FakeOnlineSettings()
    private val viewModel by lazy {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val actions =
            TrackActions(
                PlaybackController(FakeAudioEngine(), scope),
                FakeUserDataRepository(),
                FakePlaylistRepository(),
                scope,
            )
        SearchViewModel(FakeLibraryRepository(), online, settings, actions)
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
    fun `both providers start before either has replied`() =
        runTest(dispatcher) {
            screen()
            search()

            assertThat(online.started).containsExactly(call(ARCHIVE), call(YOUTUBE)).inOrder()
        }

    @Test
    fun `youtube is visible while archive is still waiting`() =
        runTest(dispatcher) {
            screen()
            search()
            online.reply(YOUTUBE)
            runCurrent()

            assertThat(sections().map { it.provider }).contains(YOUTUBE)
            assertThat(sections().first { it.provider == YOUTUBE }.results.single().title).isEqualTo("youtube-concert")
        }

    @Test
    fun `archive is visible while youtube is still waiting`() =
        runTest(dispatcher) {
            screen()
            search()
            online.reply(ARCHIVE)
            runCurrent()

            assertThat(sections().map { it.provider }).contains(ARCHIVE)
            assertThat(sections().first { it.provider == ARCHIVE }.results.single().title).isEqualTo("archive.org-concert")
        }

    @Test
    fun `reverse completion keeps archive before youtube`() =
        runTest(dispatcher) {
            screen()
            search()
            online.reply(YOUTUBE)
            runCurrent()
            online.reply(ARCHIVE)
            runCurrent()

            assertThat(sections().map { it.provider }).containsExactly(ARCHIVE, YOUTUBE).inOrder()
            assertThat(sections().flatMap { it.results }).hasSize(2)
        }

    @Test
    fun `one provider problem is visible without waiting for its sibling`() =
        runTest(dispatcher) {
            screen()
            search()
            online.reply(YOUTUBE, problem = OnlineProblem.RATE_LIMITED)
            runCurrent()

            assertThat(sections().map { it.provider }).contains(YOUTUBE)
            assertThat(sections().first { it.provider == YOUTUBE }.problem).isEqualTo(OnlineProblem.RATE_LIMITED)
        }

    @Test
    fun `a null section does not erase the other provider`() =
        runTest(dispatcher) {
            screen()
            search()
            online.reply(YOUTUBE)
            online.reply(ARCHIVE, absent = true)
            runCurrent()

            assertThat(sections().map { it.provider }).containsExactly(YOUTUBE)
        }

    @Test
    fun `new query cancels every pending provider and never shows the old answer`() =
        runTest(dispatcher) {
            screen()
            search()
            search("new")

            assertThat(online.cancelled).containsExactly(call(ARCHIVE), call(YOUTUBE))
            online.reply(ARCHIVE, query = "new")
            online.reply(YOUTUBE, query = "new")
            online.reply(ARCHIVE)
            online.reply(YOUTUBE)
            runCurrent()

            assertThat(sections().flatMap { it.results }.map { it.title })
                .containsExactly("archive.org-new", "youtube-new").inOrder()
        }

    @Test
    fun `clearing text cancels both providers immediately`() =
        runTest(dispatcher) {
            screen()
            search()
            viewModel.onQuery("  ")
            runCurrent()

            assertThat(online.cancelled).containsExactly(call(ARCHIVE), call(YOUTUBE))
            assertThat(viewModel.uiState.value.online).isEqualTo(OnlineResults.None)
        }

    @Test
    fun `sources off cancels both providers and clears the screen`() =
        runTest(dispatcher) {
            screen()
            search()
            settings.setEnabled(false)
            runCurrent()

            assertThat(online.cancelled).containsExactly(call(ARCHIVE), call(YOUTUBE))
            assertThat(viewModel.uiState.value.online).isEqualTo(OnlineResults.None)
        }

    @Test
    fun `leaving beyond grace cancels every pending provider`() =
        runTest(dispatcher) {
            val screen = screen()
            search()
            screen.cancel()
            advanceTimeBy(5_001)
            runCurrent()

            assertThat(online.cancelled).containsExactly(call(ARCHIVE), call(YOUTUBE))
        }

    @Test
    fun `return keeps a completed sibling and restarts only the cancelled provider`() =
        runTest(dispatcher) {
            val screen = screen()
            search()
            online.reply(YOUTUBE)
            runCurrent()
            screen.cancel()
            advanceTimeBy(5_001)
            runCurrent()
            screen()
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()

            assertThat(sections().map { it.provider }).contains(YOUTUBE)
            assertThat(online.started.count { it == call(YOUTUBE) }).isEqualTo(1)
            assertThat(online.started.count { it == call(ARCHIVE) }).isEqualTo(2)
        }

    @Test
    fun `return shows the completed answer from the first frame without asking again`() =
        runTest(dispatcher) {
            val screen = screen()
            search()
            online.reply(ARCHIVE)
            online.reply(YOUTUBE)
            runCurrent()
            val before = viewModel.uiState.value
            screen.cancel()
            advanceTimeBy(5_001)
            runCurrent()
            val seen = mutableListOf<SearchUiState>()
            backgroundScope.launch { viewModel.uiState.collect { seen += it } }
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()

            assertThat(seen.distinct()).containsExactly(before)
            assertThat(online.started).containsExactly(call(ARCHIVE), call(YOUTUBE)).inOrder()
        }

    private fun TestScope.screen(): Job = backgroundScope.launch { viewModel.uiState.collect {} }

    private fun TestScope.search(text: String = QUERY) {
        viewModel.onQuery(text)
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
        runCurrent()
    }

    private fun sections(): List<OnlineSection> = (viewModel.uiState.value.online as? OnlineResults.Found)?.sections.orEmpty()

    private data class Call(
        val provider: String,
        val query: String,
    )

    /** The bulk path mirrors the current registry's sequential aggregation; the new path uses the same gates. */
    private class ControlledOnline : OnlineRepository by FakeOnlineRepository() {
        val started = mutableListOf<Call>()
        val cancelled = mutableListOf<Call>()
        private val answers = mutableMapOf<Call, CompletableDeferred<OnlineSection?>>()

        override suspend fun search(query: String): List<OnlineSection> =
            OnlineProviderIds.ALL.mapNotNull { provider -> searchIn(provider, query) }

        override suspend fun searchIn(
            provider: String,
            query: String,
        ): OnlineSection? {
            val call = Call(provider, query)
            started += call
            return try {
                answers.getOrPut(call) { CompletableDeferred() }.await()
            } catch (cancelled: CancellationException) {
                this.cancelled += call
                throw cancelled
            }
        }

        fun reply(
            provider: String,
            query: String = QUERY,
            problem: OnlineProblem? = null,
            absent: Boolean = false,
        ) {
            val results =
                if (problem == null) {
                    listOf(OnlineResult("$provider-$query", OnlineKind.ALBUM, "$provider-$query", null, null))
                } else {
                    emptyList()
                }
            val section = if (absent) null else OnlineSection(provider, results, problem)
            answers.getOrPut(Call(provider, query)) { CompletableDeferred() }.complete(section)
        }
    }

    private companion object {
        const val ARCHIVE = OnlineProviderIds.ARCHIVE
        const val YOUTUBE = OnlineProviderIds.YOUTUBE
        const val QUERY = "concert"

        fun call(provider: String) = Call(provider, QUERY)
    }
}
