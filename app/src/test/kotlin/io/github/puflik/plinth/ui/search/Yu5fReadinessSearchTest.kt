package io.github.puflik.plinth.ui.search

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.online.FakeOnlineRepository
import io.github.puflik.plinth.online.FakeOnlineSettings
import io.github.puflik.plinth.online.OnlineConfiguration
import io.github.puflik.plinth.online.OnlineProviderIds
import io.github.puflik.plinth.online.OnlineReadiness
import io.github.puflik.plinth.online.OnlineReady
import io.github.puflik.plinth.online.OnlineRepository
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Real VM guards: each wait and each provider call has its own manually released outcome. */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu5fReadinessSearchTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = FakeOnlineSettings()
    private val readiness = ControlledReadiness()
    private val online = RecordingOnline()
    private val library = FakeLibraryRepository()
    private val viewModel by lazy {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val actions =
            TrackActions(
                PlaybackController(FakeAudioEngine(), scope),
                FakeUserDataRepository(),
                FakePlaylistRepository(),
                scope,
            )
        SearchViewModel(library, online, settings, actions, readiness = readiness)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        try {
            assertThat(online.bulkCalls).isEqualTo(0)
            assertThat(online.settingsCalls).isEqualTo(0)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `library and loading appear before acknowledgement and each Applied starts only one provider`() =
        runTest(dispatcher) {
            val local = LibraryTrack(TrackId("local"), "file:///concert", QUERY, duration = null, folder = "")
            library.upsert(listOf(local))
            screen()
            search()

            assertThat(viewModel.uiState.value.results).containsExactly(local)
            assertThat(viewModel.uiState.value.searched).isTrue()
            loading(ARCHIVE, YOUTUBE)
            assertThat(readiness.calls.map { it.configuration }).containsExactly(ALL, ALL)
            assertThat(online.calls).isEmpty()

            readiness.calls

                .first()

                .outcome

                .complete(OnlineReady.Applied(1L))
            runCurrent()
            val first = online.calls.single()
            assertThat(OnlineProviderIds.ALL).contains(first.provider)
            assertThat(first.query).isEqualTo(QUERY)
            first.outcome.complete(section(first.provider))
            runCurrent()
            assertThat(sections()).containsExactly(section(first.provider))
            loading(other(first.provider))
            assertThat(online.calls).hasSize(1)

            readiness.calls

                .last()

                .outcome

                .complete(OnlineReady.Applied(1L))
            runCurrent()
            assertThat(online.calls.map { it.provider }).containsExactly(ARCHIVE, YOUTUBE)
            online.calls
                .last()
                .outcome
                .complete(section(online.calls.last().provider))
            runCurrent()
            loading()
            assertThat(sections().map { it.provider }).containsExactly(ARCHIVE, YOUTUBE).inOrder()
        }

    @Test
    fun `Failed completes only its section and retry requires a new Applied`() =
        runTest(dispatcher) {
            screen()
            search()
            readiness.calls
                .first()
                .outcome
                .complete(OnlineReady.Failed(OnlineProblem.NO_NETWORK))
            runCurrent()
            val failed = sections().single().provider
            assertThat(sections()).containsExactly(section(failed, OnlineProblem.NO_NETWORK))
            loading(other(failed))
            assertThat(online.calls).isEmpty()

            retry(failed)
            runCurrent()
            assertThat(readiness.calls).hasSize(3)
            loading(ARCHIVE, YOUTUBE)
            assertThat(online.calls).isEmpty()
            readiness.calls
                .last()
                .outcome
                .complete(OnlineReady.Applied(2L))
            runCurrent()
            assertThat(online.calls.map { it.provider }).containsExactly(failed)
            loading(ARCHIVE, YOUTUBE)
            assertThat(readiness.calls[1].cancelled).isFalse()
        }

    @Test
    fun `TimedOut becomes provider down and stays cached across resubscription`() =
        runTest(dispatcher) {
            settings.setProviderEnabled(YOUTUBE, false)
            val visible = screen()
            search()
            readiness.calls
                .single()
                .outcome
                .complete(OnlineReady.TimedOut)
            runCurrent()
            assertThat(sections()).containsExactly(section(ARCHIVE, OnlineProblem.PROVIDER_DOWN))
            loading()
            assertThat(online.calls).isEmpty()
            visible.cancel()
            advanceTimeBy(GRACE_MS + 1)
            runCurrent()
            screen()
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertThat(readiness.calls).hasSize(1)
            assertThat(sections()).containsExactly(section(ARCHIVE, OnlineProblem.PROVIDER_DOWN))
            retry(ARCHIVE)
            runCurrent()
            assertThat(readiness.calls).hasSize(2)
            assertThat(online.calls).isEmpty()
            readiness.calls
                .last()
                .outcome
                .complete(OnlineReady.Applied(2L))
            runCurrent()
            assertThat(online.calls.map { it.provider }).containsExactly(ARCHIVE)
        }

    @Test
    fun `removing a provider cancels both old waits and retargets pending archive immediately`() =
        runTest(dispatcher) {
            screen()
            search()
            val old = readiness.calls.toList()
            old.forEach { it.deliverAfterCancellation = true }
            settings.setProviderEnabled(YOUTUBE, false)
            runCurrent()
            assertThat(old.map { it.cancelled }).containsExactly(true, true)
            assertThat(readiness.calls).hasSize(3)
            assertThat(readiness.calls.last().configuration).isEqualTo(ARCHIVE_ONLY)
            loading(ARCHIVE)
            old.forEach { it.outcome.complete(OnlineReady.Applied(1L)) }
            runCurrent()
            assertThat(online.calls).isEmpty()
            loading(ARCHIVE)
            readiness.calls
                .last()
                .outcome
                .complete(OnlineReady.Applied(2L))
            runCurrent()
            assertThat(online.calls.map { it.provider }).containsExactly(ARCHIVE)
        }

    @Test
    fun `adding a provider retargets pending archive and old acknowledgement releases neither`() =
        runTest(dispatcher) {
            settings.setProviderEnabled(YOUTUBE, false)
            screen()
            search()
            val old = readiness.calls.single()
            old.deliverAfterCancellation = true
            settings.setProviderEnabled(YOUTUBE, true)
            runCurrent()
            assertThat(old.cancelled).isTrue()
            assertThat(readiness.calls.drop(1).map { it.configuration }).containsExactly(ALL, ALL)
            old.outcome.complete(OnlineReady.Applied(1L))
            runCurrent()
            assertThat(online.calls).isEmpty()
            loading(ARCHIVE, YOUTUBE)
            readiness.calls.drop(1).forEach { it.outcome.complete(OnlineReady.Applied(2L)) }
            runCurrent()
            assertThat(online.calls.map { it.provider }).containsExactly(ARCHIVE, YOUTUBE)
        }

    @Test
    fun `Superseded with unchanged settings creates one fresh wait without polling`() =
        runTest(dispatcher) {
            settings.setProviderEnabled(YOUTUBE, false)
            screen()
            search()
            val first = readiness.calls.single()
            first.outcome.complete(OnlineReady.Superseded)
            runCurrent()
            assertThat(readiness.calls).hasSize(2)
            assertThat(readiness.calls.last().configuration).isEqualTo(first.configuration)
            repeat(3) { runCurrent() }
            assertThat(readiness.calls).hasSize(2)
            assertThat(online.calls).isEmpty()
            loading(ARCHIVE)
            readiness.calls
                .last()
                .outcome
                .complete(OnlineReady.Applied(2L))
            runCurrent()
            assertThat(online.calls.map { it.provider }).containsExactly(ARCHIVE)
            assertThat(readiness.calls).hasSize(2)
        }

    @Test
    fun `ABA ignores late Applied and Superseded by wait identity`() =
        runTest(dispatcher) {
            screen()
            search()
            val firstA = readiness.calls.toList()
            firstA.forEach { it.deliverAfterCancellation = true }
            settings.setProviderEnabled(YOUTUBE, false)
            runCurrent()
            val middleB = readiness.calls.last()
            middleB.deliverAfterCancellation = true
            assertThat(middleB.configuration).isEqualTo(ARCHIVE_ONLY)
            settings.setProviderEnabled(YOUTUBE, true)
            runCurrent()
            assertThat(readiness.calls).hasSize(5)
            val lastA = readiness.calls.takeLast(2)
            assertThat(lastA.map { it.configuration }).containsExactly(ALL, ALL)
            assertThat(firstA.map { it.cancelled }).containsExactly(true, true)
            assertThat(middleB.cancelled).isTrue()
            firstA.first().outcome.complete(OnlineReady.Applied(1L))
            firstA.last().outcome.complete(OnlineReady.Superseded)
            middleB.outcome.complete(OnlineReady.Failed(OnlineProblem.NO_NETWORK))
            runCurrent()
            assertThat(readiness.calls).hasSize(5)
            assertThat(lastA.map { it.cancelled }).containsExactly(false, false)
            assertThat(online.calls).isEmpty()
            assertThat(sections()).isEmpty()
            loading(ARCHIVE, YOUTUBE)
            lastA.forEach { it.outcome.complete(OnlineReady.Applied(3L)) }
            runCurrent()
            assertThat(online.calls.map { it.provider }).containsExactly(ARCHIVE, YOUTUBE)
        }

    @Test
    fun `master off cancels pending immediately and late Applied cannot release reenabling`() =
        runTest(dispatcher) {
            screen()
            search()
            val old = readiness.calls.toList()
            old.forEach { it.deliverAfterCancellation = true }
            settings.setEnabled(false)
            runCurrent()
            assertThat(old.map { it.cancelled }).containsExactly(true, true)
            assertThat(viewModel.uiState.value.online).isEqualTo(OnlineResults.None)
            assertThat(readiness.calls).hasSize(2)
            settings.setEnabled(true)
            runCurrent()
            assertThat(readiness.calls).hasSize(4)
            old.forEach { it.outcome.complete(OnlineReady.Applied(1L)) }
            runCurrent()
            assertThat(online.calls).isEmpty()
            loading(ARCHIVE, YOUTUBE)
            readiness.calls.takeLast(2).forEach { it.outcome.complete(OnlineReady.Applied(2L)) }
            runCurrent()
            assertThat(online.calls.map { it.provider }).containsExactly(ARCHIVE, YOUTUBE)
        }

    @Test
    fun `master off cancels running calls immediately and ignores their late responses`() =
        runTest(dispatcher) {
            screen()
            search()
            readiness.calls.forEach { it.outcome.complete(OnlineReady.Applied(1L)) }
            runCurrent()
            val old = online.calls.toList()
            old.forEach { it.deliverAfterCancellation = true }
            settings.setEnabled(false)
            runCurrent()
            assertThat(old.map { it.cancelled }).containsExactly(true, true)
            assertThat(viewModel.uiState.value.online).isEqualTo(OnlineResults.None)
            old.forEach { it.outcome.complete(section(it.provider)) }
            runCurrent()
            assertThat(viewModel.uiState.value.online).isEqualTo(OnlineResults.None)
            assertThat(online.calls).hasSize(2)
        }

    @Test
    fun `removing a running provider cancels it and reenabling needs a fresh acknowledgement`() =
        runTest(dispatcher) {
            screen()
            search()
            readiness.calls.forEach { it.outcome.complete(OnlineReady.Applied(1L)) }
            runCurrent()
            val archive = online.calls.single { it.provider == ARCHIVE }
            val youtube = online.calls.single { it.provider == YOUTUBE }
            youtube.deliverAfterCancellation = true
            settings.setProviderEnabled(YOUTUBE, false)
            runCurrent()
            assertThat(youtube.cancelled).isTrue()
            assertThat(archive.cancelled).isFalse()
            loading(ARCHIVE)
            settings.setProviderEnabled(YOUTUBE, true)
            runCurrent()
            assertThat(readiness.calls).hasSize(3)
            youtube.outcome.complete(section(YOUTUBE))
            runCurrent()
            assertThat(sections()).isEmpty()
            loading(ARCHIVE, YOUTUBE)
            assertThat(online.calls).hasSize(2)
            readiness.calls
                .last()
                .outcome
                .complete(OnlineReady.Applied(2L))
            runCurrent()
            assertThat(online.calls.map { it.provider }).containsExactly(ARCHIVE, YOUTUBE, YOUTUBE)
            assertThat(archive.cancelled).isFalse()
        }

    @Test
    fun `finished archive survives adding and removing youtube without returning to loading`() =
        runTest(dispatcher) {
            settings.setProviderEnabled(YOUTUBE, false)
            screen()
            search()
            readiness.calls
                .single()
                .outcome
                .complete(OnlineReady.Applied(1L))
            runCurrent()
            online.calls
                .single()
                .outcome
                .complete(section(ARCHIVE))
            runCurrent()
            settings.setProviderEnabled(YOUTUBE, true)
            runCurrent()
            loading(YOUTUBE)
            assertThat(sections()).containsExactly(section(ARCHIVE))
            assertThat(readiness.calls).hasSize(2)
            settings.setProviderEnabled(YOUTUBE, false)
            runCurrent()
            loading()
            assertThat(readiness.calls.last().cancelled).isTrue()
            assertThat(online.calls.map { it.provider }).containsExactly(ARCHIVE)
            assertThat(sections()).containsExactly(section(ARCHIVE))
        }

    @Test
    fun `running archive survives neighbour configuration changes and its later answer is accepted`() =
        runTest(dispatcher) {
            settings.setProviderEnabled(YOUTUBE, false)
            screen()
            search()
            readiness.calls
                .single()
                .outcome
                .complete(OnlineReady.Applied(1L))
            runCurrent()
            val archive = online.calls.single()
            settings.setProviderEnabled(YOUTUBE, true)
            runCurrent()
            readiness.calls
                .last()
                .outcome
                .complete(OnlineReady.Applied(2L))
            runCurrent()
            val youtube = online.calls.single { it.provider == YOUTUBE }
            settings.setProviderEnabled(YOUTUBE, false)
            runCurrent()
            assertThat(youtube.cancelled).isTrue()
            assertThat(archive.cancelled).isFalse()
            assertThat(readiness.calls).hasSize(2)
            assertThat(online.calls).hasSize(2)
            archive.outcome.complete(section(ARCHIVE))
            runCurrent()
            assertThat(sections()).containsExactly(section(ARCHIVE))
            loading()
        }

    @Test
    fun `retained failed acknowledgement stays cached when its neighbour changes and on return`() =
        runTest(dispatcher) {
            settings.setProviderEnabled(YOUTUBE, false)
            val visible = screen()
            search()
            readiness.calls
                .single()
                .outcome
                .complete(OnlineReady.Failed(OnlineProblem.NO_NETWORK))
            runCurrent()
            settings.setProviderEnabled(YOUTUBE, true)
            runCurrent()
            loading(YOUTUBE)
            assertThat(sections()).containsExactly(section(ARCHIVE, OnlineProblem.NO_NETWORK))
            settings.setProviderEnabled(YOUTUBE, false)
            runCurrent()
            visible.cancel()
            advanceTimeBy(GRACE_MS + 1)
            runCurrent()
            screen()
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            loading()
            assertThat(readiness.calls).hasSize(2)
            assertThat(online.calls).isEmpty()
            assertThat(sections()).containsExactly(section(ARCHIVE, OnlineProblem.NO_NETWORK))
        }

    @Test
    fun `retained null search result stays terminal across settings and lifecycle changes`() =
        runTest(dispatcher) {
            settings.setProviderEnabled(YOUTUBE, false)
            val visible = screen()
            search()
            readiness.calls
                .single()
                .outcome
                .complete(OnlineReady.Applied(1L))
            runCurrent()
            online.calls
                .single()
                .outcome
                .complete(null)
            runCurrent()
            settings.setProviderEnabled(YOUTUBE, true)
            runCurrent()
            loading(YOUTUBE)
            settings.setProviderEnabled(YOUTUBE, false)
            runCurrent()
            visible.cancel()
            advanceTimeBy(GRACE_MS + 1)
            runCurrent()
            screen()
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            loading()
            assertThat(sections()).isEmpty()
            assertThat(readiness.calls).hasSize(2)
            assertThat(online.calls).hasSize(1)
        }

    @Test
    fun `section retry waits again without restarting its in flight sibling`() =
        runTest(dispatcher) {
            screen()
            search()
            readiness.calls.forEach { it.outcome.complete(OnlineReady.Applied(1L)) }
            runCurrent()
            val archive = online.calls.single { it.provider == ARCHIVE }
            val youtube = online.calls.single { it.provider == YOUTUBE }
            youtube.outcome.complete(section(YOUTUBE, OnlineProblem.NO_NETWORK))
            runCurrent()
            retry(YOUTUBE)
            retry(YOUTUBE)
            runCurrent()
            assertThat(readiness.calls).hasSize(3)
            assertThat(archive.cancelled).isFalse()
            assertThat(online.calls).hasSize(2)
            loading(ARCHIVE, YOUTUBE)
            readiness.calls
                .last()
                .outcome
                .complete(OnlineReady.Applied(2L))
            runCurrent()
            assertThat(online.calls.map { it.provider }).containsExactly(ARCHIVE, YOUTUBE, YOUTUBE)
            archive.outcome.complete(section(ARCHIVE))
            runCurrent()
            assertThat(sections()).containsExactly(section(ARCHIVE))
            loading(YOUTUBE)
        }

    @Test
    fun `unknown disabled ids do not create waits calls or cancellation`() =
        runTest(dispatcher) {
            screen()
            search()
            val pending = readiness.calls.toList()
            settings.setProviderEnabled("unknown-provider", false)
            runCurrent()
            assertThat(readiness.calls).hasSize(2)
            assertThat(pending.map { it.cancelled }).containsExactly(false, false)
            pending.forEach { it.outcome.complete(OnlineReady.Applied(1L)) }
            runCurrent()
            settings.setProviderEnabled("another-unknown", false)
            runCurrent()
            assertThat(readiness.calls).hasSize(2)
            assertThat(online.calls.map { it.provider }).containsExactly(ARCHIVE, YOUTUBE)
            assertThat(online.calls.map { it.cancelled }).containsExactly(false, false)
        }

    @Test
    fun `return within grace keeps the same pending waits`() =
        runTest(dispatcher) {
            val visible = screen()
            search()
            visible.cancel()
            advanceTimeBy(GRACE_MS - 1)
            runCurrent()
            screen()
            runCurrent()
            assertThat(readiness.calls).hasSize(2)
            assertThat(readiness.calls.map { it.cancelled }).containsExactly(false, false)
            loading(ARCHIVE, YOUTUBE)
            assertThat(online.calls).isEmpty()
        }

    @Test
    fun `beyond grace restarts pending only against the latest configuration`() =
        runTest(dispatcher) {
            val visible = screen()
            search()
            val old = readiness.calls.toList()
            old.forEach { it.deliverAfterCancellation = true }
            visible.cancel()
            advanceTimeBy(GRACE_MS + 1)
            runCurrent()
            assertThat(old.map { it.cancelled }).containsExactly(true, true)
            settings.setProviderEnabled(YOUTUBE, false)
            runCurrent()
            assertThat(readiness.calls).hasSize(2)
            screen()
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertThat(readiness.calls).hasSize(3)
            assertThat(readiness.calls.last().configuration).isEqualTo(ARCHIVE_ONLY)
            loading(ARCHIVE)
            old.forEach { it.outcome.complete(OnlineReady.Applied(1L)) }
            runCurrent()
            assertThat(online.calls).isEmpty()
            loading(ARCHIVE)
            readiness.calls
                .last()
                .outcome
                .complete(OnlineReady.Applied(2L))
            runCurrent()
            assertThat(online.calls.map { it.provider }).containsExactly(ARCHIVE)
        }

    @Test
    fun `beyond grace keeps a finished sibling and old search finally cannot clear new loading`() =
        runTest(dispatcher) {
            val visible = screen()
            search()
            readiness.calls.forEach { it.outcome.complete(OnlineReady.Applied(1L)) }
            runCurrent()
            val archive = online.calls.single { it.provider == ARCHIVE }
            val oldYoutube = online.calls.single { it.provider == YOUTUBE }
            archive.outcome.complete(section(ARCHIVE))
            oldYoutube.deliverAfterCancellation = true
            runCurrent()
            visible.cancel()
            advanceTimeBy(GRACE_MS + 1)
            runCurrent()
            assertThat(oldYoutube.cancelled).isTrue()
            screen()
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertThat(readiness.calls).hasSize(3)
            assertThat(sections()).containsExactly(section(ARCHIVE))
            loading(YOUTUBE)
            readiness.calls
                .last()
                .outcome
                .complete(OnlineReady.Applied(2L))
            runCurrent()
            oldYoutube.outcome.complete(null)
            runCurrent()
            loading(YOUTUBE)
            assertThat(sections()).containsExactly(section(ARCHIVE))
            assertThat(online.calls.map { it.provider }).containsExactly(ARCHIVE, YOUTUBE, YOUTUBE)
            online.calls
                .last()
                .outcome
                .complete(section(YOUTUBE))
            runCurrent()
            loading()
            assertThat(sections().map { it.provider }).containsExactly(ARCHIVE, YOUTUBE).inOrder()
        }

    @Test
    fun `clearing query cancels pending immediately and late timeout stays invisible`() =
        runTest(dispatcher) {
            screen()
            search()
            val old = readiness.calls.toList()
            old.forEach { it.deliverAfterCancellation = true }
            viewModel.onQuery("  ")
            runCurrent()
            assertThat(old.map { it.cancelled }).containsExactly(true, true)
            assertThat(viewModel.uiState.value.online).isEqualTo(OnlineResults.None)
            old.forEach { it.outcome.complete(OnlineReady.TimedOut) }
            runCurrent()
            assertThat(viewModel.uiState.value.online).isEqualTo(OnlineResults.None)
            assertThat(online.calls).isEmpty()
        }

    @Test
    fun `new settled query ignores old acknowledgement failure and cannot start old searches`() =
        runTest(dispatcher) {
            screen()
            search()
            val old = readiness.calls.toList()
            old.forEach { it.deliverAfterCancellation = true }
            search("new query")
            assertThat(old.map { it.cancelled }).containsExactly(true, true)
            assertThat(readiness.calls).hasSize(4)
            old.first().outcome.complete(OnlineReady.Applied(1L))
            old.last().outcome.complete(OnlineReady.Failed(OnlineProblem.NO_NETWORK))
            runCurrent()
            assertThat(online.calls).isEmpty()
            assertThat(sections()).isEmpty()
            loading(ARCHIVE, YOUTUBE)
            readiness.calls.takeLast(2).forEach { it.outcome.complete(OnlineReady.Applied(2L)) }
            runCurrent()
            assertThat(online.calls.map { it.query }).containsExactly("new query", "new query")
        }

    @Test
    fun `disabling all known providers clears pending without waiting for any acknowledgement`() =
        runTest(dispatcher) {
            screen()
            search()
            settings.disabledProviders.value = OnlineProviderIds.ALL.toSet()
            runCurrent()
            assertThat(readiness.calls.map { it.cancelled }).containsExactly(true, true)
            assertThat(readiness.calls).hasSize(2)
            assertThat(online.calls).isEmpty()
            assertThat(viewModel.uiState.value.online).isEqualTo(OnlineResults.None)
        }

    private fun TestScope.screen(): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

    private fun TestScope.search(text: String = QUERY) {
        viewModel.onQuery(text)
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
        runCurrent()
    }

    private fun loading(vararg providers: String) = assertYu5fLoading(viewModel.uiState.value.online, *providers)

    private fun sections(): List<OnlineSection> = (viewModel.uiState.value.online as OnlineResults.Found).sections

    private fun retry(provider: String) {
        val method =
            SearchViewModel::class.java.methods.singleOrNull {
                it.name == "onRetryOnline" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
        assertThat(method).isNotNull()
        checkNotNull(method).invoke(viewModel, provider)
    }

    private class WaitCall(
        val configuration: OnlineConfiguration,
    ) {
        val outcome = CompletableDeferred<OnlineReady>()
        var cancelled = false
        var deliverAfterCancellation = false

        suspend fun await(): OnlineReady =
            try {
                outcome.await()
            } catch (cancelled: CancellationException) {
                this.cancelled = true
                if (!deliverAfterCancellation) throw cancelled
                withContext(NonCancellable) { outcome.await() }
            }
    }

    private class ControlledReadiness : OnlineReadiness {
        val calls = mutableListOf<WaitCall>()

        override suspend fun awaitConfiguration(configuration: OnlineConfiguration): OnlineReady {
            val call = WaitCall(configuration)
            calls += call
            return call.await()
        }
    }

    private class SearchCall(
        val provider: String,
        val query: String,
    ) {
        val outcome = CompletableDeferred<OnlineSection?>()
        var cancelled = false
        var deliverAfterCancellation = false

        suspend fun await(): OnlineSection? =
            try {
                outcome.await()
            } catch (cancelled: CancellationException) {
                this.cancelled = true
                if (!deliverAfterCancellation) throw cancelled
                withContext(NonCancellable) { outcome.await() }
            }
    }

    private class RecordingOnline : OnlineRepository by FakeOnlineRepository() {
        val calls = mutableListOf<SearchCall>()
        var bulkCalls = 0
            private set
        var settingsCalls = 0
            private set

        override suspend fun setEnabled(
            enabled: Boolean,
            disabled: Set<String>,
        ) {
            settingsCalls++
            error("SearchViewModel must not apply settings")
        }

        override suspend fun search(query: String): List<OnlineSection> {
            bulkCalls++
            error("SearchViewModel must use independent provider searches")
        }

        override suspend fun searchIn(
            provider: String,
            query: String,
        ): OnlineSection? {
            val call = SearchCall(provider, query)
            calls += call
            return call.await()
        }
    }

    private companion object {
        const val ARCHIVE = OnlineProviderIds.ARCHIVE
        const val YOUTUBE = OnlineProviderIds.YOUTUBE
        const val QUERY = "concert"
        const val GRACE_MS = 5_000L
        val ALL = OnlineConfiguration(true, emptySet())
        val ARCHIVE_ONLY = OnlineConfiguration(true, setOf(YOUTUBE))

        fun other(provider: String): String = if (provider == ARCHIVE) YOUTUBE else ARCHIVE

        fun section(
            provider: String,
            problem: OnlineProblem? = null,
        ): OnlineSection {
            val results =
                if (problem == null) {
                    listOf(OnlineResult("$provider-$QUERY", OnlineKind.ALBUM, "$provider-$QUERY", null, null))
                } else {
                    emptyList()
                }
            return OnlineSection(provider, results, problem)
        }
    }
}
