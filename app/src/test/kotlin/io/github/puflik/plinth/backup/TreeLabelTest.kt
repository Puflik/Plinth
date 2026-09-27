package io.github.puflik.plinth.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TreeLabelTest {
    @Test
    fun `a folder of the phone storage is its path`() {
        assertThat(TreeLabel.of("$TREE/primary%3AMusic")).isEqualTo("Music")
        assertThat(TreeLabel.of("$TREE/primary%3AMusic%2FBackup")).isEqualTo("Music/Backup")
    }

    @Test
    fun `a folder of an sd card is its path with spaces and cyrillic`() {
        assertThat(TreeLabel.of("$TREE/E68A-1416%3A%D0%9C%D1%83%D0%B7%D1%8B%D0%BA%D0%B0%20%2B")).isEqualTo("Музыка +")
    }

    @Test
    fun `a volume root is a slash and a strange address is itself`() {
        assertThat(TreeLabel.of("$TREE/primary%3A")).isEqualTo("/")
        assertThat(TreeLabel.of("content://elsewhere/document/1")).isEqualTo("content://elsewhere/document/1")
    }

    private companion object {
        const val TREE = "content://com.android.externalstorage.documents/tree"
    }
}
