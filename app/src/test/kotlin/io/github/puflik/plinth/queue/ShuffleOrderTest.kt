package io.github.puflik.plinth.queue

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.SourceTree
import org.junit.Test
import kotlin.random.Random

/** Порядок обхода при shuffle (D1.3) и граница пакета очереди. */
class ShuffleOrderTest {
    @Test
    fun `shuffled order starts with the given track and holds every index once`() {
        val order = ShuffleOrder.shuffled(size = 10, first = 4, random = Random(1))

        assertThat(order.first()).isEqualTo(4)
        assertThat(order).containsExactlyElementsIn(0 until 10)
    }

    @Test
    fun `same seed gives the same order`() {
        assertThat(ShuffleOrder.shuffled(20, 0, Random(42))).isEqualTo(ShuffleOrder.shuffled(20, 0, Random(42)))
    }

    @Test
    fun `plain order is the list as it is`() {
        assertThat(ShuffleOrder.plain(4)).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test
    fun `queue does not import android`() {
        val imports =
            SourceTree.importsStartingWith(
                SourceTree.kotlinFiles("queue"),
                listOf("android.", "androidx.", "com.google.android"),
            )

        assertThat(imports).isEmpty()
    }
}
