package io.github.puflik.plinth.library.sort

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArticleStripperTest {
    private val stripper = ArticleStripper()

    @Test
    fun `leading article is cut off`() {
        assertThat(stripper.strip("The Beatles")).isEqualTo("Beatles")
    }

    @Test
    fun `article is recognised in any case`() {
        assertThat(stripper.strip("THE BEATLES")).isEqualTo("BEATLES")
    }

    @Test
    fun `every default article is recognised`() {
        val stripped = ArticleStripper.DEFAULT_ARTICLES.map { stripper.strip("$it Name") }

        assertThat(stripped.toSet()).containsExactly("Name")
    }

    @Test
    fun `default list is the one from the plan`() {
        assertThat(ArticleStripper.DEFAULT_ARTICLES)
            .containsExactly("The", "A", "An", "Der", "Die", "Das", "Le", "La", "Los")
    }

    @Test
    fun `article must be a whole word`() {
        assertThat(stripper.strip("Theatre of Tragedy")).isEqualTo("Theatre of Tragedy")
        assertThat(stripper.strip("Anathema")).isEqualTo("Anathema")
        assertThat(stripper.strip("A-ha")).isEqualTo("A-ha")
    }

    @Test
    fun `only the first article is cut off`() {
        assertThat(stripper.strip("The The")).isEqualTo("The")
    }

    @Test
    fun `lone article stays as it is`() {
        assertThat(stripper.strip("The")).isEqualTo("The")
        assertThat(stripper.strip("The  ")).isEqualTo("The")
    }

    @Test
    fun `leading spaces do not hide the article`() {
        assertThat(stripper.strip("  The Beatles")).isEqualTo("Beatles")
    }

    @Test
    fun `list of articles is configurable`() {
        val spanish = ArticleStripper(listOf("El"))

        assertThat(spanish.strip("El Guincho")).isEqualTo("Guincho")
        assertThat(spanish.strip("The Beatles")).isEqualTo("The Beatles")
    }
}
