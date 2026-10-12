package io.github.puflik.plinth.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.ui.common.formatTime
import io.github.puflik.plinth.ui.library.components.TrackLabel
import io.github.puflik.plinth.ui.library.components.TrackRow
import io.github.puflik.plinth.ui.library.tabs.TracksTab
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Р6: у строки трека есть видимая кнопка «⋮» с тем же меню, что у долгого
 * нажатия — очередь, «В плейлист…», лайк. Автор не нашёл ни лайка, ни «в
 * плейлист» нигде, кроме долгого нажатия во вкладке «Треки». Строка одна на
 * все списки (`TrackRow`), поэтому кнопка проверяется на ней и на списке.
 *
 * Тексты берутся из ресурсов приложения, а не из литералов: язык эмулятора
 * не важен.
 */
class TrackRowMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val more = text(R.string.library_more)
    private val queueItems = listOf(R.string.track_play_next, R.string.track_add_to_queue, R.string.track_replace_queue)
    private val actions = mutableListOf<TrackAction>()

    @Test
    fun the_more_button_opens_the_track_menu() {
        show()

        compose.onNodeWithContentDescription(more).performClick()

        assertMenuShown(liked = false)
    }

    /** Страж: долгое нажатие работает и открывает то же самое меню. */
    @Test
    fun a_long_press_still_opens_the_same_menu() {
        show()

        compose.onNodeWithText(TITLE).performTouchInput { longClick() }

        assertMenuShown(liked = false)
    }

    @Test
    fun the_menu_of_a_liked_track_offers_unlike() {
        show(liked = true)

        compose.onNodeWithContentDescription(more).performClick()

        assertMenuShown(liked = true)
    }

    /** Кнопка — это меню, а не касание строки: трек не заиграл. */
    @Test
    fun the_more_button_does_not_play_the_track() {
        show()

        compose.onNodeWithContentDescription(more).performClick()

        assertThat(actions).isEmpty()
    }

    /** Страж: касание самой строки по-прежнему играет трек. */
    @Test
    fun a_tap_on_the_row_still_plays() {
        show()

        compose.onNodeWithText(TITLE).performClick()

        assertThat(actions).containsExactly(TrackAction.PLAY)
    }

    @Test
    fun a_queue_item_chosen_from_the_more_button_acts_once_and_closes_the_menu() {
        show()

        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(text(R.string.track_add_to_queue)).performClick()

        assertThat(actions).containsExactly(TrackAction.ADD_TO_QUEUE)
        compose.onNodeWithText(text(R.string.track_add_to_queue)).assertDoesNotExist()
    }

    @Test
    fun like_chosen_from_the_more_button_is_sent() {
        show()
        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(text(R.string.track_like)).performClick()

        assertThat(actions).containsExactly(TrackAction.LIKE)
    }

    @Test
    fun unlike_chosen_from_the_more_button_is_sent() {
        show(liked = true)
        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(text(R.string.track_unlike)).performClick()

        assertThat(actions).containsExactly(TrackAction.UNLIKE)
    }

    /** «В плейлист…» не уходит наружу как действие: строка сама открывает выбор плейлиста. */
    @Test
    fun add_to_playlist_chosen_from_the_more_button_opens_the_picker() {
        show()

        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(text(R.string.track_add_to_playlist)).performClick()

        compose.onNodeWithText(PICKER).assertIsDisplayed()
        assertThat(actions).isEmpty()
    }

    /** Кнопка — настоящая цель касания: видна, нажимается, область касания 48 dp. */
    @Test
    fun the_more_button_is_a_real_tap_target() {
        show()

        compose
            .onNodeWithContentDescription(more)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertTouchWidthIsEqualTo(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
    }

    /** Раньше справа не было ничего, когда нет ни облака, ни длительности. */
    @Test
    fun a_row_without_duration_or_cloud_has_the_button() {
        show(duration = null)

        compose.onNodeWithContentDescription(more).assertIsDisplayed()
    }

    @Test
    fun an_online_row_keeps_its_duration_beside_the_button() {
        show(online = true)

        compose.onNodeWithText(formatTime(LENGTH)).assertIsDisplayed()
        compose.onNodeWithContentDescription(more).assertIsDisplayed()
    }

    /** Список: у каждой строки своя кнопка, и она действует на свой трек. */
    @Test
    fun in_a_list_each_row_has_its_own_button_for_its_own_track() {
        val first = track(1, "Mustapha")
        val second = track(2, "Jealousy")
        val picked = mutableListOf<Pair<TrackId, TrackAction>>()
        compose.setContent {
            TracksTab(listOf(first, second), onAction = { track, action -> picked += track.id to action })
        }

        compose.onAllNodesWithContentDescription(more).assertCountEquals(2)
        compose.onAllNodesWithContentDescription(more)[1].performClick()
        compose.onNodeWithText(text(R.string.track_like)).performClick()

        assertThat(picked).containsExactly(second.id to TrackAction.LIKE)
    }

    private fun show(
        liked: Boolean = false,
        online: Boolean = false,
        duration: Duration? = LENGTH,
    ) {
        compose.setContent {
            TrackRow(
                label = TrackLabel(TITLE, "Queen", "Jazz", duration, liked, online),
                onAction = { actions += it },
            ) { dismiss -> Text(PICKER, modifier = Modifier.clickable(onClick = dismiss)) }
        }
    }

    /** Меню трека: очередь, «В плейлист…» и лайк — или «Больше не нравится». */
    private fun assertMenuShown(liked: Boolean) {
        for (item in queueItems + R.string.track_add_to_playlist) compose.onNodeWithText(text(item)).assertIsDisplayed()
        val shown = if (liked) R.string.track_unlike else R.string.track_like
        val hidden = if (liked) R.string.track_like else R.string.track_unlike
        compose.onNodeWithText(text(shown)).assertIsDisplayed()
        compose.onNodeWithText(text(hidden)).assertDoesNotExist()
    }

    private fun text(
        @StringRes id: Int,
    ) = context.getString(id)

    private fun track(
        n: Int,
        title: String,
    ) = LibraryTrack(
        id = TrackId("01a0da9e-761f-7073-bc9b-e5437326adc$n"),
        uri = "/storage/emulated/0/Music/track-$n.mp3",
        title = title,
        artist = "Queen",
        duration = LENGTH,
        folder = "Music/",
    )

    private companion object {
        const val TITLE = "Mustapha"
        const val PICKER = "PICKER"
        val LENGTH = 3.minutes
    }
}
