package io.github.puflik.plinth.ui.player

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.model.Album
import org.junit.Rule
import org.junit.Test

/**
 * Р6: меню ⋮ справа сверху в плеере. Автор искал в нём лайк и «добавить в
 * плейлист» и не нашёл: было только «К альбому» и «К исполнителю». Теперь
 * первыми идут «Нравится» (или «Больше не нравится») и «В плейлист…» — для трека
 * фонотеки; у файла не из фонотеки (SAF) лайка и плейлиста нет, как нет и
 * сердца. Кнопка не гаснет, пока в меню есть хоть один пункт.
 *
 * Меню собрано без экрана: состояние — значение `PlayerUiState`, выбор
 * плейлиста — заглушка с текстом [PICKER].
 */
class PlayerMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val more = text(R.string.library_more)
    private val calls = mutableListOf<String>()
    private val album = Album("Jazz", "Queen", trackCount = 0)
    private val libraryTrack =
        PlayerUiState.EMPTY.copy(
            title = "Song",
            artist = "Queen",
            album = album,
            liked = false,
            hasTrack = true,
            canControl = true,
        )

    @Test
    fun a_library_track_offers_like_and_add_to_playlist_even_without_album_or_artist() {
        show(libraryTrack.copy(album = null, artist = null))

        compose.onNodeWithContentDescription(more).assertIsEnabled().performClick()

        compose.onNodeWithText(text(R.string.track_like)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.track_add_to_playlist)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.player_go_album)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.player_go_artist)).assertDoesNotExist()
    }

    @Test
    fun a_liked_track_offers_unlike() {
        show(libraryTrack.copy(liked = true))

        compose.onNodeWithContentDescription(more).performClick()

        compose.onNodeWithText(text(R.string.track_unlike)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.track_like)).assertDoesNotExist()
    }

    @Test
    fun a_file_outside_the_library_offers_no_like_and_no_playlist() {
        show(libraryTrack.copy(liked = null))

        compose.onNodeWithContentDescription(more).performClick()

        compose.onNodeWithText(text(R.string.player_go_album)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.player_go_artist)).assertIsDisplayed()
        for (absent in listOf(R.string.track_like, R.string.track_unlike, R.string.track_add_to_playlist)) {
            compose.onNodeWithText(text(absent)).assertDoesNotExist()
        }
    }

    @Test
    fun a_file_with_nothing_to_offer_has_a_disabled_button() {
        show(libraryTrack.copy(liked = null, album = null, artist = null))

        compose.onNodeWithContentDescription(more).assertIsNotEnabled()
    }

    @Test
    fun like_is_sent_once_and_the_menu_closes() {
        show(libraryTrack)

        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(text(R.string.track_like)).performClick()

        assertThat(calls).containsExactly("like")
        compose.onNodeWithText(text(R.string.track_like)).assertDoesNotExist()
    }

    @Test
    fun add_to_playlist_closes_the_menu_and_opens_the_picker() {
        show(libraryTrack)

        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(text(R.string.track_add_to_playlist)).performClick()

        compose.onNodeWithText(PICKER).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.track_like)).assertDoesNotExist()
        assertThat(calls).isEmpty()

        compose.onNodeWithText(PICKER).performClick()
        compose.onNodeWithText(PICKER).assertDoesNotExist()
    }

    /** Страж: «К альбому» и «К исполнителю» остались и ведут туда же. */
    @Test
    fun go_to_album_and_go_to_artist_still_work() {
        show(libraryTrack)

        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(text(R.string.player_go_album)).performClick()
        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(text(R.string.player_go_artist)).performClick()

        assertThat(calls).containsExactly("album:Jazz", "artist:Queen").inOrder()
    }

    /** Лайк и плейлист — то, что ищут, поэтому выше переходов. */
    @Test
    fun like_comes_first_then_playlist_then_album_then_artist() {
        show(libraryTrack)

        compose.onNodeWithContentDescription(more).performClick()

        val tops =
            listOf(
                R.string.track_like,
                R.string.track_add_to_playlist,
                R.string.player_go_album,
                R.string.player_go_artist,
            ).map { top(it) }
        assertThat(tops).isInStrictOrder()
    }

    private fun show(state: PlayerUiState) {
        compose.setContent {
            PlayerMenu(
                state = state,
                onLike = { calls += "like" },
                onOpenAlbum = { calls += "album:${it.title}" },
                onOpenArtist = { calls += "artist:$it" },
                playlistPicker = { dismiss -> Text(PICKER, modifier = Modifier.clickable(onClick = dismiss)) },
            )
        }
    }

    /** Высота пункта меню в окне меню; у пунктов одного меню отсчёт общий. */
    private fun top(
        @StringRes id: Int,
    ): Float {
        val bounds = compose.onNodeWithText(text(id)).getBoundsInRoot()
        return bounds.top.value
    }

    private fun text(
        @StringRes id: Int,
    ) = context.getString(id)

    private companion object {
        const val PICKER = "PICKER"
    }
}
