package io.github.puflik.plinth.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Свайп (E3, E5): направление решает большая составляющая сдвига, короткое
 * движение — не свайп, а неточное касание.
 */
class SwipeTest {
    @Test
    fun `short drag is not a swipe`() {
        assertThat(Swipe.of(dx = -40f, dy = -10f, threshold = THRESHOLD)).isNull()
    }

    @Test
    fun `sideways swipes`() {
        assertThat(Swipe.of(dx = -120f, dy = 30f, threshold = THRESHOLD)).isEqualTo(Swipe.LEFT)
        assertThat(Swipe.of(dx = 120f, dy = -30f, threshold = THRESHOLD)).isEqualTo(Swipe.RIGHT)
    }

    @Test
    fun `vertical swipes`() {
        assertThat(Swipe.of(dx = 40f, dy = -120f, threshold = THRESHOLD)).isEqualTo(Swipe.UP)
        assertThat(Swipe.of(dx = 10f, dy = 120f, threshold = THRESHOLD)).isEqualTo(Swipe.DOWN)
    }

    @Test
    fun `left and up move forward`() {
        assertThat(Swipe.entries.filter(Swipe::forward)).containsExactly(Swipe.LEFT, Swipe.UP)
    }

    private companion object {
        const val THRESHOLD = 100f
    }
}
