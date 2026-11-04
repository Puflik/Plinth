package io.github.puflik.plinth.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
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
import io.github.puflik.plinth.online.FakeOnlineSettings
import io.github.puflik.plinth.online.OnlineProviderIds
import io.github.puflik.plinth.online.OnlineRepository
import io.github.puflik.plinth.online.TestSong
import io.github.puflik.plinth.online.TestYoutubeOnline
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** yu5f: actual SearchScreen; no live provider or player service. Requires an explicitly selected emulator. */
class Yu5fSearchSectionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val online = SectionOnline()
    private var inPlayer by mutableStateOf(false)
    private var hasMiniPlayer by mutableStateOf(false)
    private val viewModel by lazy {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val actions =
            TrackActions(
                PlaybackController(FakeAudioEngine(), scope),
                FakeUserDataRepository(),
                FakePlaylistRepository(),
                scope,
            )
        SearchViewModel(FakeLibraryRepository(), online, FakeOnlineSettings(), actions)
    }

    private fun show() {
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            Box(Modifier.fillMaxSize()) {
                if (!inPlayer) {
                    holder.SaveableStateProvider("search") {
                        SearchScreen(
                            onOpenPlayer = { inPlayer = true },
                            onOpenAlbum = {},
                            modifier = Modifier.padding(bottom = if (hasMiniPlayer) 64.dp else 0.dp),
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("concert")
        compose.waitUntil(WAIT_MS) { viewModel.uiState.value.online is OnlineResults.Found }
        compose.waitForIdle()
    }

    @Test
    fun overview_contains_five_rows_per_section_and_no_sixth_row() {
        show()

        for (provider in OnlineProviderIds.ALL) {
            list().performScrollToNode(hasText("$provider-5"))
            assertThrows(AssertionError::class.java) { list().performScrollToNode(hasText("$provider-6")) }
        }
    }

    @Test
    fun show_all_opens_the_remaining_rows_without_asking_the_network() {
        show()
        val before = online.calls.toList()
        list().performScrollToNode(hasTestTag("search-show-all-${OnlineProviderIds.ARCHIVE}"))
        compose.onNodeWithTag("search-show-all-${OnlineProviderIds.ARCHIVE}").performClick()

        list().performScrollToNode(hasText("${OnlineProviderIds.ARCHIVE}-8"))
        assertThrows(AssertionError::class.java) {
            list().performScrollToNode(hasText("${OnlineProviderIds.YOUTUBE}-6"))
        }
        assertThat(online.calls.toList()).containsExactlyElementsIn(before).inOrder()
    }

    @Test
    fun retry_of_youtube_does_not_repeat_the_completed_archive_search() {
        online.youtubeProblem = OnlineProblem.RATE_LIMITED
        show()
        online.youtubeProblem = null
        list().performScrollToNode(hasTestTag("search-retry-${OnlineProviderIds.YOUTUBE}"))
        compose.onNodeWithTag("search-retry-${OnlineProviderIds.YOUTUBE}").performClick()
        compose.waitUntil(WAIT_MS) { online.calls.count { it == OnlineProviderIds.YOUTUBE } == 2 }

        assertThat(online.calls.count { it == OnlineProviderIds.ARCHIVE }).isEqualTo(1)
        list().performScrollToNode(hasText("${OnlineProviderIds.YOUTUBE}-5"))
    }

    @Test
    fun first_and_second_player_returns_keep_the_same_visible_song_offset() {
        show()
        list().performScrollToNode(hasText(TestSong.TITLE))
        val before =
            compose
                .onNodeWithText(TestSong.TITLE)
                .fetchSemanticsNode()
                .boundsInRoot.top
        val calls = online.calls.toList()

        repeat(2) {
            compose.onNodeWithText(TestSong.TITLE).performClick()
            compose.waitUntil(WAIT_MS) { inPlayer }
            compose.runOnIdle {
                hasMiniPlayer = true
                inPlayer = false
            }
            compose.waitForIdle()

            val after =
                compose
                    .onNodeWithText(TestSong.TITLE)
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            assertThat(after.toDouble()).isWithin(1.0).of(before.toDouble())
            assertThat(online.calls.toList()).containsExactlyElementsIn(calls).inOrder()
        }
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())

    private class SectionOnline : OnlineRepository by TestYoutubeOnline() {
        val calls = CopyOnWriteArrayList<String>()

        @Volatile
        var youtubeProblem: OnlineProblem? = null

        override suspend fun search(query: String): List<OnlineSection> =
            OnlineProviderIds.ALL.mapNotNull { provider -> searchIn(provider, query) }

        override suspend fun searchIn(
            provider: String,
            query: String,
        ): OnlineSection? {
            if (query.isBlank()) return null
            calls += provider
            val problem = if (provider == OnlineProviderIds.YOUTUBE) youtubeProblem else null
            val results =
                if (problem == null) {
                    (1..8).map { number ->
                        if (provider == OnlineProviderIds.YOUTUBE && number == 1) {
                            TestSong.result
                        } else {
                            OnlineResult("$provider-$number", OnlineKind.ALBUM, "$provider-$number", null, null)
                        }
                    }
                } else {
                    emptyList()
                }
            return OnlineSection(provider, results, problem)
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
