package io.github.puflik.plinth.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.PlaylistEntryId
import org.junit.Test

/**
 * Перестановка на экране — в индекс ядра (D4b). Ядро ставит запись на место
 * среди всех записей, скрытых тоже, а экран видит только видимые.
 */
class PlaylistOrderTest {
    private val a = PlaylistEntryId("a")
    private val hidden = PlaylistEntryId("hidden")
    private val b = PlaylistEntryId("b")
    private val c = PlaylistEntryId("c")

    @Test
    fun `without hidden entries the index is the same`() {
        val all = listOf(a, b, c)

        assertThat(PlaylistOrder.indexAmongAll(all, all, a, to = 2)).isEqualTo(2)
        assertThat(PlaylistOrder.indexAmongAll(all, all, c, to = 0)).isEqualTo(0)
    }

    /** Вниз — сразу после видимого соседа, а не на его индекс среди всех. */
    @Test
    fun `moving down lands right after the visible neighbour`() {
        val all = listOf(a, hidden, b, c)

        val index = PlaylistOrder.indexAmongAll(listOf(a, b, c), all, a, to = 1)

        assertThat(placed(all, a, checkNotNull(index))).containsExactly(hidden, b, a, c).inOrder()
    }

    /** Вверх — сразу перед видимым соседом: скрытая запись остаётся за ним. */
    @Test
    fun `moving up lands right before the visible neighbour`() {
        val all = listOf(a, b, hidden, c)

        val index = PlaylistOrder.indexAmongAll(listOf(a, b, c), all, c, to = 1)

        assertThat(placed(all, c, checkNotNull(index))).containsExactly(a, c, b, hidden).inOrder()
    }

    @Test
    fun `a place past the end means the last one`() {
        val all = listOf(a, b, hidden)

        val index = PlaylistOrder.indexAmongAll(listOf(a, b), all, a, to = 5)

        assertThat(placed(all, a, checkNotNull(index))).containsExactly(b, a, hidden).inOrder()
    }

    @Test
    fun `nothing to move when the entry stays or is not visible`() {
        val all = listOf(a, hidden, b)

        assertThat(PlaylistOrder.indexAmongAll(listOf(a, b), all, b, to = 1)).isNull()
        assertThat(PlaylistOrder.indexAmongAll(listOf(a, b), all, hidden, to = 0)).isNull()
        assertThat(PlaylistOrder.indexAmongAll(listOf(a, b), listOf(a, hidden), b, to = 0)).isNull()
    }

    /** Как ядро: запись [entry] уходит со своего места и встаёт на [index] среди остальных. */
    private fun placed(
        all: List<PlaylistEntryId>,
        entry: PlaylistEntryId,
        index: Int,
    ): List<PlaylistEntryId> = (all - entry).toMutableList().apply { add(index, entry) }
}
