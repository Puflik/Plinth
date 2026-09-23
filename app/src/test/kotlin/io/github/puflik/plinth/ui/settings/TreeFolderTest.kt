package io.github.puflik.plinth.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Папка из `OpenDocumentTree` → путь, как его знает `MediaStore` (C2.5). */
class TreeFolderTest {
    @Test
    fun `internal storage folder becomes a relative path`() {
        assertThat(TreeFolder.path(EXTERNAL_STORAGE, "primary:Download")).isEqualTo("Download/")
        assertThat(TreeFolder.path(EXTERNAL_STORAGE, "primary:Music/Rock")).isEqualTo("Music/Rock/")
    }

    @Test
    fun `sd card folder is relative to its volume`() {
        assertThat(TreeFolder.path(EXTERNAL_STORAGE, "1A2B-3C4D:Music")).isEqualTo("Music/")
    }

    @Test
    fun `storage root is the empty path`() {
        assertThat(TreeFolder.path(EXTERNAL_STORAGE, "primary:")).isEmpty()
    }

    @Test
    fun `folders of other providers are not local folders`() {
        assertThat(TreeFolder.path("com.google.android.apps.docs.storage", "acc=1;doc=2")).isNull()
        assertThat(TreeFolder.path(EXTERNAL_STORAGE, "no-volume")).isNull()
    }

    private companion object {
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    }
}
