package io.github.puflik.plinth.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Свайп по мини-плееру (12.10, 12.11): вбок — смена трека, вверх — плеер.
 * Направление решает большая составляющая, короткое движение — не свайп.
 */
class MiniPlayerSwipeTest {
    @Test
    fun `short drag is not a swipe`() {
        assertThat(MiniPlayerSwipe.of(dx = -40f, dy = -10f, threshold = THRESHOLD)).isNull()
    }

    @Test
    fun `swipe to the left goes to the next track`() {
        assertThat(MiniPlayerSwipe.of(dx = -120f, dy = 30f, threshold = THRESHOLD)).isEqualTo(MiniPlayerSwipe.NEXT)
    }

    @Test
    fun `swipe to the right goes to the previous track`() {
        assertThat(MiniPlayerSwipe.of(dx = 120f, dy = -30f, threshold = THRESHOLD)).isEqualTo(MiniPlayerSwipe.PREVIOUS)
    }

    @Test
    fun `swipe up opens the player`() {
        assertThat(MiniPlayerSwipe.of(dx = 40f, dy = -120f, threshold = THRESHOLD)).isEqualTo(MiniPlayerSwipe.EXPAND)
    }

    @Test
    fun `swipe down does nothing`() {
        assertThat(MiniPlayerSwipe.of(dx = 10f, dy = 120f, threshold = THRESHOLD)).isNull()
    }

    private companion object {
        const val THRESHOLD = 100f
    }
}
