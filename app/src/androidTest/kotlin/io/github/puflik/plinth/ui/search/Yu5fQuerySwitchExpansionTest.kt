package io.github.puflik.plinth.ui.search

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.online.FakeOnlineReadiness
import io.github.puflik.plinth.online.FakeOnlineSettings
import io.github.puflik.plinth.online.OnlineProviderIds
import io.github.puflik.plinth.online.OnlineRepository
import io.github.puflik.plinth.online.TestYoutubeOnline
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** R1: real screen and VM, with only the VM's debounce running on explicitly advanced virtual time. */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class Yu5fQuerySwitchExpansionTest {
    @get:Rule
    val compose = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val actionsScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val online = QueryOnline()

    @Test
    fun old_answer_expansion_during_debounce_does_not_expand_the_next_query() {
        val viewModel = createViewModel()
        try {
            compose.setContent { SearchScreen(onOpenPlayer = {}, onOpenAlbum = {}, viewModel = viewModel) }
            compose.onNode(hasSetTextAction()).performTextInput(QUERY_A)
            settleQuery()
            assertAnswer(viewModel, QUERY_A)
            val callsForA = expectedCalls(QUERY_A)
            assertThat(online.calls).containsExactlyElementsIn(callsForA)
            list().performScrollToNode(hasTestTag(SHOW_ALL))

            compose.onNode(hasSetTextAction()).performTextReplacement(QUERY_B)
            compose.runOnIdle { scheduler.runCurrent() }
            compose.waitForIdle()
            compose.onNode(hasSetTextAction()).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(QUERY_B)),
            )
            assertAnswer(viewModel, QUERY_A)
            val beforeClick = scheduler.currentTime
            list().performScrollToNode(hasTestTag(SHOW_ALL))
            compose.onNodeWithTag(SHOW_ALL).assertIsDisplayed().performClick()
            assertThat(scheduler.currentTime).isEqualTo(beforeClick)
            assertThat(online.calls).containsExactlyElementsIn(callsForA)
            assertAnswer(viewModel, QUERY_A)

            settleQuery()
            assertAnswer(viewModel, QUERY_B)
            val allCalls = callsForA + expectedCalls(QUERY_B)
            assertThat(online.calls).containsExactlyElementsIn(allCalls)
            list().performScrollToNode(hasText(title(QUERY_B, OnlineProviderIds.ARCHIVE, 5)))
            assertThrows("B must stay collapsed after clicking the old A answer", AssertionError::class.java) {
                list().performScrollToNode(hasText(title(QUERY_B, OnlineProviderIds.ARCHIVE, 6)))
            }
            list().performScrollToNode(hasTestTag(SHOW_ALL))
            compose.onNodeWithTag(SHOW_ALL).performClick()
            list().performScrollToNode(hasText(title(QUERY_B, OnlineProviderIds.ARCHIVE, 6)))
            compose.onNodeWithText(title(QUERY_B, OnlineProviderIds.ARCHIVE, 6)).assertIsDisplayed()
            assertThrows(AssertionError::class.java) {
                list().performScrollToNode(hasText(title(QUERY_B, OnlineProviderIds.YOUTUBE, 6)))
            }
            assertThat(online.calls).containsExactlyElementsIn(allCalls)
        } finally {
            compose.runOnIdle { viewModel.viewModelScope.cancel() }
            actionsScope.cancel()
            scheduler.runCurrent()
        }
    }

    private fun settleQuery() {
        compose.runOnIdle {
            scheduler.runCurrent()
            scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
            scheduler.runCurrent()
        }
        compose.waitForIdle()
    }

    private fun assertAnswer(
        viewModel: SearchViewModel,
        query: String,
    ) {
        val found = viewModel.uiState.value.online as OnlineResults.Found
        assertThat(found.loading).isEmpty()
        assertThat(found.sections.map { it.provider }).containsExactlyElementsIn(OnlineProviderIds.ALL)
        for (section in found.sections) {
            assertThat(section.results.map { it.title })
                .containsExactlyElementsIn(
                    (1..ROW_COUNT).map { title(query, section.provider, it) },
                ).inOrder()
        }
    }

    private fun createViewModel(): SearchViewModel {
        val actions =
            TrackActions(
                PlaybackController(FakeAudioEngine(), actionsScope),
                FakeUserDataRepository(),
                FakePlaylistRepository(),
                actionsScope,
            )
        // viewModelScope captures Main.immediate during construction. Restore Android Main before composing.
        Dispatchers.setMain(VirtualMain(dispatcher))
        return try {
            SearchViewModel(
                FakeLibraryRepository(),
                online,
                FakeOnlineSettings(),
                actions,
                readiness = FakeOnlineReadiness(),
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())

    private fun expectedCalls(query: String) = OnlineProviderIds.ALL.map { it to query }

    /** Captured by viewModelScope, including Delay; Compose and Android's UI thread retain real time. */
    private class VirtualMain(
        private val dispatcher: TestDispatcher,
    ) : MainCoroutineDispatcher(),
        Delay by dispatcher {
        override val immediate: MainCoroutineDispatcher get() = this

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = dispatcher.dispatch(context, block)
    }

    private class QueryOnline : OnlineRepository by TestYoutubeOnline() {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun search(query: String): List<OnlineSection> =
            error("The screen must search each provider separately")

        override suspend fun searchIn(
            provider: String,
            query: String,
        ): OnlineSection {
            calls += provider to query
            return OnlineSection(
                provider,
                (1..ROW_COUNT).map { number ->
                    val title = title(query, provider, number)
                    OnlineResult(title, OnlineKind.ALBUM, title, null, null)
                },
            )
        }
    }

    private companion object {
        const val QUERY_A = "alpha"
        const val QUERY_B = "beta"
        const val ROW_COUNT = 6
        const val SHOW_ALL = "search-show-all-${OnlineProviderIds.ARCHIVE}"

        fun title(
            query: String,
            provider: String,
            number: Int,
        ) = "$query/$provider/$number"
    }
}
