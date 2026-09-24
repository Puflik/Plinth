package io.github.puflik.plinth.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Перетаскивание в панели «Очередь» (E4): куда встанет строка и кто уступит ей место. */
class QueueReorderTest {
    @Test
    fun `row dragged less than half its height stays in place`() {
        assertThat(QueueReorder.target(from = 2, offset = 40f, rowHeight = ROW, count = 5)).isEqualTo(2)
        assertThat(QueueReorder.target(from = 2, offset = -40f, rowHeight = ROW, count = 5)).isEqualTo(2)
    }

    @Test
    fun `row takes the place it is held over`() {
        assertThat(QueueReorder.target(from = 2, offset = 60f, rowHeight = ROW, count = 5)).isEqualTo(3)
        assertThat(QueueReorder.target(from = 2, offset = -160f, rowHeight = ROW, count = 5)).isEqualTo(0)
    }

    @Test
    fun `row cannot leave the list`() {
        assertThat(QueueReorder.target(from = 0, offset = -500f, rowHeight = ROW, count = 5)).isEqualTo(0)
        assertThat(QueueReorder.target(from = 4, offset = 500f, rowHeight = ROW, count = 5)).isEqualTo(4)
    }

    @Test
    fun `rows between the old and new place make room`() {
        val down = (0 until 5).map { QueueReorder.shift(index = it, from = 1, target = 3) }
        val up = (0 until 5).map { QueueReorder.shift(index = it, from = 3, target = 1) }

        assertThat(down).containsExactly(0, 0, -1, -1, 0).inOrder()
        assertThat(up).containsExactly(0, 1, 1, 0, 0).inOrder()
    }

    private companion object {
        const val ROW = 100f
    }
}
