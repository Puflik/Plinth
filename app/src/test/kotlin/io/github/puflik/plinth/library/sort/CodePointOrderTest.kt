package io.github.puflik.plinth.library.sort

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CodePointOrderTest {
    @Test
    fun `characters beyond the basic plane go after its last characters`() {
        // U+1F600 (эмодзи, в UTF-16 — суррогатная пара) после U+FF76 (полуширинная
        // катакана) и U+FFFD (замена битого символа): так их ставят SQLite и Rust.
        val sorted = listOf("😀", "�", "ｶ", "z").sortedWith(CodePointOrder)

        assertThat(sorted).containsExactly("z", "ｶ", "�", "😀").inOrder()
    }

    @Test
    fun `below the surrogates it agrees with compareTo`() {
        val words = listOf("b", "a", "ab", "", "Ё", "é", "中", "A")

        assertThat(words.sortedWith(CodePointOrder)).isEqualTo(words.sorted())
    }

    @Test
    fun `characters beyond the basic plane compare by code point`() {
        // U+1F600 и U+20000 (иероглиф расширения B): старшие суррогаты D83D и D840.
        val sorted = listOf("𠀀", "😁", "😀").sortedWith(CodePointOrder)

        assertThat(sorted).containsExactly("😀", "😁", "𠀀").inOrder()
    }

    @Test
    fun `equal strings are equal`() {
        assertThat(CodePointOrder.compare("😀 a", "😀 a")).isEqualTo(0)
    }
}
