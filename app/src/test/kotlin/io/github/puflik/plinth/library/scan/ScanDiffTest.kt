package io.github.puflik.plinth.library.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScanDiffTest {
    @Test
    fun `new files are read`() {
        val diff = ScanDiff.of(known = emptyMap(), found = mapOf(1L to 10L))

        assertThat(diff.changed).containsExactly(1L)
        assertThat(diff.missing).isEmpty()
    }

    @Test
    fun `unchanged files are skipped`() {
        val diff = ScanDiff.of(known = mapOf(1L to 10L), found = mapOf(1L to 10L))

        assertThat(diff.changed).isEmpty()
        assertThat(diff.missing).isEmpty()
    }

    @Test
    fun `file with another modification time is read again`() {
        // Назад время тоже уходит: файл восстановили из резервной копии.
        val diff = ScanDiff.of(known = mapOf(1L to 10L, 2L to 20L), found = mapOf(1L to 11L, 2L to 19L))

        assertThat(diff.changed).containsExactly(1L, 2L)
    }

    @Test
    fun `known files that are not found are missing`() {
        val diff = ScanDiff.of(known = mapOf(1L to 10L, 2L to 10L), found = mapOf(1L to 10L))

        assertThat(diff.missing).containsExactly(2L)
        assertThat(diff.changed).isEmpty()
    }
}
