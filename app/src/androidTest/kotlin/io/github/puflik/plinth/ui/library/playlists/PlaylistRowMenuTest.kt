package io.github.puflik.plinth.ui.library.playlists

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.R
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.TaggedFile
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * Р6: кнопка «⋮» есть и в строке своего плейлиста — самой тесной строке
 * приложения: справа от неё ручка перестановки, а саму строку можно смахнуть.
 * Экран собран из настоящих `PlaylistViewModel` и `TrackActions` на заглушках
 * хранилищ: что нажато в меню, доходит до журнала лайков.
 */
class PlaylistRowMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val more = context.getString(R.string.library_more)
    private val like = context.getString(R.string.track_like)

    private val library = FakeLibraryRepository()
    private val tracks =
        library.add(
            listOf(
                TaggedFile("/music/mustapha.mp3", title = "Mustapha"),
                TaggedFile("/music/jealousy.mp3", title = "Jealousy"),
            ),
        )
    private val playlists = FakePlaylistRepository(library)
    private val userData = FakeUserDataRepository()
    private val playback = PlaybackController(FakeAudioEngine(), CoroutineScope(Dispatchers.Unconfined))
    private val actions = TrackActions(playback, userData, playlists, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun each_row_of_a_playlist_has_its_own_more_button() {
        showPlaylist()

        compose.onAllNodesWithContentDescription(more).assertCountEquals(2)
        compose.onAllNodesWithContentDescription(more)[0].assertIsDisplayed()
        compose.onAllNodesWithContentDescription(more)[1].assertIsDisplayed()
    }

    @Test
    fun the_button_of_the_second_row_likes_the_second_track() {
        showPlaylist()

        compose.onAllNodesWithContentDescription(more)[1].performClick()
        compose.onNodeWithText(like).performClick()

        runBlocking {
            assertThat(userData.liked(tracks[1].id).first()).isTrue()
            assertThat(userData.liked(tracks[0].id).first()).isFalse()
        }
    }

    /** Кнопка открывает меню и только: ни игры, ни перестановки, ни удаления строки. */
    @Test
    fun the_button_only_opens_the_menu() {
        showPlaylist()

        compose.onAllNodesWithContentDescription(more)[0].performClick()

        assertThat(playback.queue.value.current).isNull()
        compose.onNodeWithText("Mustapha").assertIsDisplayed()
        compose.onNodeWithText("Jealousy").assertIsDisplayed()
    }

    private fun showPlaylist() {
        val id =
            runBlocking {
                playlists.create("Road").also { playlist -> tracks.forEach { playlists.add(playlist, it.id) } }
            }
        val viewModel = PlaylistViewModel(SavedStateHandle(PlaylistViewModel.arguments(id, "Road")), playlists, actions)
        compose.setContent { PlaylistScreen(onBack = {}, onOpenPlayer = {}, viewModel = viewModel) }
        compose.waitUntil(LOAD_MS) { compose.onAllNodesWithText("Mustapha").fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val LOAD_MS = 5_000L
    }
}
