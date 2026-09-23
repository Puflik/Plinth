package io.github.puflik.plinth.library.sort

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SortKeysTest {
    private val keys = SortKeys()

    @Test
    fun `article is ignored`() {
        val sorted = listOf("Coldplay", "The Beatles", "ABBA").sortedBy(keys::of)

        assertThat(sorted).containsExactly("ABBA", "The Beatles", "Coldplay").inOrder()
    }

    @Test
    fun `numbers after the article follow natural order`() {
        val sorted = listOf("The 10 Bears", "The 2 Bears").sortedBy(keys::of)

        assertThat(sorted).containsExactly("The 2 Bears", "The 10 Bears").inOrder()
    }

    @Test
    fun `case of the article does not matter`() {
        assertThat(keys.of("the beatles")).isEqualTo(keys.of("The Beatles"))
    }

    @Test
    fun `keys use the given list of articles`() {
        val spanish = SortKeys(ArticleStripper(listOf("El")))

        assertThat(spanish.of("El Guincho")).isEqualTo(NaturalOrder.key("Guincho"))
        assertThat(spanish.of("The Beatles")).isEqualTo(NaturalOrder.key("The Beatles"))
    }
}
