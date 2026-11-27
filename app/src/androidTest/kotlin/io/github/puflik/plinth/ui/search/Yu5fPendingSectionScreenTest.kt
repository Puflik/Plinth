package io.github.puflik.plinth.ui.search

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test

/** A pending earlier provider keeps its own accessible section while the faster provider is already visible. */
class Yu5fPendingSectionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pending_archive_stays_before_fast_youtube_until_its_result_arrives() {
        val archiveRelease = CompletableDeferred<Unit>()
        val online = PendingOnline(archiveRelease)
        val viewModel = createViewModel(online)
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        val archiveHeading = hasText("Internet Archive") and heading
        val youtubeHeading = hasText("YouTube") and heading
        val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        try {
            compose.setContent {
                SearchScreen(onOpenPlayer = {}, onOpenAlbum = {}, viewModel = viewModel)
            }
            compose.onNode(hasSetTextAction()).performTextInput("concert")
            // Observe the real VM's partial answer, never a delay or an all-providers completion barrier.
            compose.waitUntil(WAIT_MS) {
                val state = viewModel.uiState.value.online as? OnlineResults.Found
                val youtube = state?.sections?.singleOrNull()
                val result = youtube?.results?.singleOrNull()
                state?.loading == setOf(OnlineProviderIds.ARCHIVE) &&
                    youtube?.provider == OnlineProviderIds.YOUTUBE &&
                    result?.title == FAST_TITLE
            }
            compose.waitForIdle()
            assertThat(archiveRelease.isCompleted).isFalse()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(FAST_TITLE))
            compose.onNodeWithText(FAST_TITLE).assertIsDisplayed()
            // Scroll-to-node searches the LazyColumn, so a missing header cannot be mistaken for virtualization.
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(archiveHeading)
            compose.onNode(archiveHeading).assertIsDisplayed()
            compose.onNode(youtubeHeading).assertIsDisplayed()
            compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(1)
            compose.onNode(progress, useUnmergedTree = true).assertIsDisplayed()
            val archiveBounds = compose.onNode(archiveHeading).fetchSemanticsNode().boundsInRoot
            val progressBounds = compose.onNode(progress, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val youtubeBounds = compose.onNode(youtubeHeading).fetchSemanticsNode().boundsInRoot
            assertThat(progressBounds.top).isAtLeast(archiveBounds.bottom)
            assertThat(youtubeBounds.top).isAtLeast(progressBounds.bottom)
            compose.onNodeWithText(FAST_TITLE).assertIsDisplayed()
            compose.onNodeWithText(SLOW_TITLE).assertDoesNotExist()

            archiveRelease.complete(Unit)
            compose.waitUntil(WAIT_MS) {
                val state = viewModel.uiState.value.online as? OnlineResults.Found
                state?.loading?.isEmpty() == true &&
                    state.sections.map { it.provider } == OnlineProviderIds.ALL
            }
            compose.waitForIdle()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(SLOW_TITLE))
            compose.onNodeWithText(SLOW_TITLE).assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(FAST_TITLE))
            compose.onNodeWithText(FAST_TITLE).assertIsDisplayed()
            compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(0)
        } finally {
            archiveRelease.complete(Unit)
        }
    }

    private fun createViewModel(online: OnlineRepository): SearchViewModel {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val actions =
            TrackActions(
                PlaybackController(FakeAudioEngine(), scope),
                FakeUserDataRepository(),
                FakePlaylistRepository(),
                scope,
            )
        return SearchViewModel(
            FakeLibraryRepository(),
            online,
            FakeOnlineSettings(),
            actions,
            readiness = FakeOnlineReadiness(),
        )
    }

    private class PendingOnline(
        private val archiveRelease: CompletableDeferred<Unit>,
    ) : OnlineRepository by TestYoutubeOnline() {
        override suspend fun search(query: String): List<OnlineSection> =
            error("SearchScreen must search providers separately")

        override suspend fun searchIn(
            provider: String,
            query: String,
        ): OnlineSection {
            check(query == "concert")
            if (provider == OnlineProviderIds.ARCHIVE) archiveRelease.await()
            val title = if (provider == OnlineProviderIds.ARCHIVE) SLOW_TITLE else FAST_TITLE
            return OnlineSection(provider, listOf(OnlineResult(provider, OnlineKind.ALBUM, title, null, null)))
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val FAST_TITLE = "Fast YouTube album"
        const val SLOW_TITLE = "Delayed Archive album"
    }
}
