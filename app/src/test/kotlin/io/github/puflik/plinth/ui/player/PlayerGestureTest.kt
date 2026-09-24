package io.github.puflik.plinth.ui.player

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ui.player.GestureAction.CHANGE_TRACK
import io.github.puflik.plinth.ui.player.GestureAction.COLLAPSE
import io.github.puflik.plinth.ui.player.GestureAction.FAST_SEEK
import io.github.puflik.plinth.ui.player.GestureAction.LIKE
import io.github.puflik.plinth.ui.player.GestureAction.NEXT_ALBUM
import io.github.puflik.plinth.ui.player.GestureAction.NEXT_PANEL
import io.github.puflik.plinth.ui.player.GestureAction.NOTHING
import io.github.puflik.plinth.ui.player.GestureAction.OPEN_QUEUE
import io.github.puflik.plinth.ui.player.GestureAction.SEEK_10_SECONDS
import io.github.puflik.plinth.ui.player.GestureAction.SWITCH_PANEL
import io.github.puflik.plinth.ui.player.GestureAction.TOGGLE_LYRICS
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Жесты плеера таблицей (E5, 12.11): умолчания и варианты — данные, из них
 * потом соберётся экран настройки; действие жеста — команда плееру.
 */
class PlayerGestureTest {
    @Test
    fun `defaults follow the plan`() {
        assertThat(PlayerGesture.entries.associateWith(PlayerGesture::default))
            .containsExactly(
                PlayerGesture.COVER_SWIPE,
                CHANGE_TRACK,
                PlayerGesture.SWIPE_UP,
                OPEN_QUEUE,
                PlayerGesture.SWIPE_DOWN,
                COLLAPSE,
                PlayerGesture.COVER_TAP,
                TOGGLE_LYRICS,
                PlayerGesture.DOUBLE_TAP,
                SEEK_10_SECONDS,
                PlayerGesture.HOLD_SKIP,
                FAST_SEEK,
                PlayerGesture.MINI_PLAYER_SWIPE,
                CHANGE_TRACK,
            )
    }

    @Test
    fun `every gesture offers its default and can be turned off`() {
        for (gesture in PlayerGesture.entries) {
            assertThat(gesture.options).contains(gesture.default)
            assertThat(gesture.options).contains(NOTHING)
        }
    }

    @Test
    fun `track and seek go the way of the gesture`() {
        assertThat(CHANGE_TRACK.command(forward = true, PlayerPanel.COVER)).isEqualTo(PlayerCommand.Next)
        assertThat(CHANGE_TRACK.command(forward = false, PlayerPanel.COVER)).isEqualTo(PlayerCommand.Previous)
        assertThat(SEEK_10_SECONDS.command(forward = false, PlayerPanel.COVER))
            .isEqualTo(PlayerCommand.SeekBy((-10).seconds))
        assertThat(FAST_SEEK.command(forward = true, PlayerPanel.COVER))
            .isEqualTo(PlayerCommand.SeekBy(PlayerCommand.FAST_SEEK_STEP))
    }

    @Test
    fun `panel gestures open and toggle panels`() {
        assertThat(OPEN_QUEUE.command(forward = true, PlayerPanel.COVER))
            .isEqualTo(PlayerCommand.Show(PlayerPanel.QUEUE))
        assertThat(TOGGLE_LYRICS.command(forward = true, PlayerPanel.COVER))
            .isEqualTo(PlayerCommand.Show(PlayerPanel.LYRICS))
        assertThat(TOGGLE_LYRICS.command(forward = true, PlayerPanel.LYRICS))
            .isEqualTo(PlayerCommand.Show(PlayerPanel.COVER))
        assertThat(SWITCH_PANEL.command(forward = false, PlayerPanel.COVER))
            .isEqualTo(PlayerCommand.Show(PlayerPanel.INFO))
        assertThat(NEXT_PANEL.command(forward = true, PlayerPanel.INFO))
            .isEqualTo(PlayerCommand.Show(PlayerPanel.COVER))
        assertThat(COLLAPSE.command(forward = false, PlayerPanel.QUEUE)).isEqualTo(PlayerCommand.Collapse)
    }

    /** Лайк ждёт v0.2, следующий альбом — очередь по альбомам; пока они, как «ничего», молчат. */
    @Test
    fun `actions that do not exist yet do nothing`() {
        for (action in listOf(LIKE, NEXT_ALBUM, NOTHING)) {
            assertThat(action.command(forward = true, PlayerPanel.COVER)).isNull()
        }
    }
}
