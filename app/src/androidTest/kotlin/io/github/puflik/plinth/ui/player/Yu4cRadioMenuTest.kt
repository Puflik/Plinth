package io.github.puflik.plinth.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.R
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.components.TrackLabel
import io.github.puflik.plinth.ui.library.components.TrackRow
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Пункт «Радио» (Ю4в) на экране: в меню строки трека, в меню ⋮ плеера и подпись над очередью радио. Тексты берутся из
 * ресурсов приложения: язык эмулятора не важен.
 */
class Yu4cRadioMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val more = context.getString(R.string.library_more)
    private val radio = context.getString(R.string.track_radio)
    private val calls = mutableListOf<String>()
    private val playing = PlayerUiState.EMPTY.copy(title = "Song", hasTrack = true, canControl = true)

    @Test
    fun the_menu_of_a_track_row_offers_radio() {
        val actions = mutableListOf<TrackAction>()
        compose.setContent {
            TrackRow(
                label = TrackLabel("Song", "Queen", "Jazz", 3.minutes, liked = false, online = false),
                onAction = { actions += it },
            ) { dismiss -> Text(PICKER, modifier = Modifier.clickable(onClick = dismiss)) }
        }

        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(radio).assertIsDisplayed().performClick()

        assertThat(actions).containsExactly(TrackAction.RADIO)
        compose.onNodeWithText(radio).assertDoesNotExist()
    }

    @Test
    fun the_player_menu_offers_radio_for_what_plays_and_a_choice_closes_it() {
        compose.setContent { Menu(playing, onRadio = { calls += "radio" }) }

        compose.onNodeWithContentDescription(more).assertIsEnabled().performClick()
        compose.onNodeWithText(radio).assertIsDisplayed().performClick()

        assertThat(calls).containsExactly("radio")
        compose.onNodeWithText(radio).assertDoesNotExist()
    }

    @Test
    fun a_track_without_a_title_has_no_radio_and_the_button_has_nothing_to_open() {
        compose.setContent { Menu(playing.copy(title = null), onRadio = { calls += "radio" }) }

        compose.onNodeWithContentDescription(more).assertIsNotEnabled()
    }

    @Test
    fun without_a_handler_the_player_menu_has_no_radio() {
        compose.setContent { Menu(playing.copy(liked = false)) }

        compose.onNodeWithContentDescription(more).performClick()

        compose.onNodeWithText(context.getString(R.string.track_like)).assertIsDisplayed()
        compose.onNodeWithText(radio).assertDoesNotExist()
    }

    @Test
    fun the_queue_of_a_radio_says_where_it_comes_from() {
        compose.setContent { QueuePanel(upcoming, QueueEdits({ _, _ -> }, { _, _, _ -> }), radio = "Get Lucky") }

        compose.onNodeWithText(context.getString(R.string.player_queue_radio, "Get Lucky")).assertIsDisplayed()
        compose.onNodeWithText("Next").assertIsDisplayed()
    }

    @Test
    fun the_queue_of_anything_else_has_no_caption() {
        compose.setContent { QueuePanel(upcoming, QueueEdits({ _, _ -> }, { _, _, _ -> })) }

        compose.onNodeWithText(context.getString(R.string.player_queue_radio, "Get Lucky")).assertDoesNotExist()
        compose.onNodeWithText("Next").assertIsDisplayed()
    }

    private val upcoming = listOf(QueueItem(AudioSource.Online("next"), "Next", "Band"))

    /** Меню плеера без экрана: выбор плейлиста — заглушка с текстом [PICKER]. */
    @Composable
    private fun Menu(
        state: PlayerUiState,
        onRadio: (() -> Unit)? = null,
    ) = PlayerMenu(
        state = state,
        onLike = {},
        onOpenAlbum = {},
        onOpenArtist = {},
        playlistPicker = { dismiss -> Text(PICKER, modifier = Modifier.clickable(onClick = dismiss)) },
        onRadio = onRadio,
    )

    private companion object {
        const val PICKER = "PICKER"
    }
}
