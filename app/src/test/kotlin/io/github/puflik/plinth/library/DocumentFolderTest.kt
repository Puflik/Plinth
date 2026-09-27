package io.github.puflik.plinth.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Папка файла плейлиста, выбранного через SAF (D4c): от неё считаются относительные пути. */
class DocumentFolderTest {
    @Test
    fun `a document on the phone storage is in its folder`() {
        assertThat(DocumentFolder.of(STORAGE, "primary:Music/Lists/road.m3u", PRIMARY))
            .isEqualTo("/storage/emulated/0/Music/Lists")
        assertThat(DocumentFolder.of(STORAGE, "primary:road.m3u", PRIMARY)).isEqualTo("/storage/emulated/0")
    }

    /** «Документы» — свой корень провайдера, `home:` — это `Documents/` основного тома. */
    @Test
    fun `the documents root is the documents folder`() {
        assertThat(DocumentFolder.of(STORAGE, "home:Lists/road.m3u8", PRIMARY))
            .isEqualTo("/storage/emulated/0/Documents/Lists")
    }

    @Test
    fun `a document on an sd card is under its volume`() {
        assertThat(
            DocumentFolder.of(STORAGE, "1A2B-3C4D:Music/road.pls", PRIMARY),
        ).isEqualTo("/storage/1A2B-3C4D/Music")
    }

    /** «Загрузки» иногда отдают путь файла прямо в идентификаторе. */
    @Test
    fun `a raw download names its path`() {
        assertThat(DocumentFolder.of(DOWNLOADS, "raw:/storage/emulated/0/Download/road.m3u", PRIMARY))
            .isEqualTo("/storage/emulated/0/Download")
        assertThat(DocumentFolder.of(DOWNLOADS, "msf:42", PRIMARY)).isNull()
    }

    /** Облака и медиапровайдер путей на устройстве не знают. */
    @Test
    fun `other providers have no folder`() {
        assertThat(DocumentFolder.of("com.android.providers.media.documents", "document:12", PRIMARY)).isNull()
        assertThat(DocumentFolder.of("com.google.android.apps.docs.storage", "abc", PRIMARY)).isNull()
        assertThat(DocumentFolder.of(STORAGE, "no-volume", PRIMARY)).isNull()
    }

    private companion object {
        const val STORAGE = "com.android.externalstorage.documents"
        const val DOWNLOADS = "com.android.providers.downloads.documents"
        const val PRIMARY = "/storage/emulated/0"
    }
}
