package io.github.puflik.plinth.library.sort

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun `numbers compare by value, not digit by digit`() {
        val sorted = listOf("Track 10", "Track 2", "Track 1").sortedWith(NaturalOrder.comparator)

        assertThat(sorted).containsExactly("Track 1", "Track 2", "Track 10").inOrder()
    }

    @Test
    fun `leading zeros do not change the number`() {
        assertThat(NaturalOrder.key("Track 02")).isEqualTo(NaturalOrder.key("Track 2"))
    }

    @Test
    fun `several numbers in one name compare one by one`() {
        val sorted = listOf("Disc 2 Track 1", "Disc 1 Track 10", "Disc 1 Track 9").sortedWith(NaturalOrder.comparator)

        assertThat(sorted).containsExactly("Disc 1 Track 9", "Disc 1 Track 10", "Disc 2 Track 1").inOrder()
    }

    @Test
    fun `numbers go before letters`() {
        val sorted = listOf("Abba", "10 Years").sortedWith(NaturalOrder.comparator)

        assertThat(sorted).containsExactly("10 Years", "Abba").inOrder()
    }

    @Test
    fun `case is ignored`() {
        assertThat(NaturalOrder.key("ABBA")).isEqualTo(NaturalOrder.key("abba"))
    }

    @Test
    fun `surrounding and repeated spaces are ignored`() {
        assertThat(NaturalOrder.key("  Track   2 ")).isEqualTo(NaturalOrder.key("Track 2"))
    }

    @Test
    fun `accented latin letters sort with their base letter`() {
        val sorted = listOf("Fame", "Élan", "Eagle").sortedWith(NaturalOrder.comparator)

        assertThat(sorted).containsExactly("Eagle", "Élan", "Fame").inOrder()
    }

    @Test
    fun `yo sorts as ye, as russian dictionaries do`() {
        val sorted = listOf("Жук", "Ёж", "Еда").sortedWith(NaturalOrder.comparator)

        assertThat(sorted).containsExactly("Еда", "Ёж", "Жук").inOrder()
    }

    @Test
    fun `short i stays a letter of its own after i`() {
        val sorted = listOf("Кит", "Йога", "Иволга").sortedWith(NaturalOrder.comparator)

        assertThat(sorted).containsExactly("Иволга", "Йога", "Кит").inOrder()
    }

    @Test
    fun `comparator agrees with keys`() {
        val names = listOf("b10", "B9", "a", "Ä", "10", "9")

        assertThat(names.sortedWith(NaturalOrder.comparator)).isEqualTo(names.sortedBy(NaturalOrder::key))
    }
}
