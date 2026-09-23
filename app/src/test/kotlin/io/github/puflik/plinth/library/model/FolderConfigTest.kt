package io.github.puflik.plinth.library.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FolderConfigTest {
    @Test
    fun `default scans Music and Download with subfolders`() {
        val folders = FolderConfig.DEFAULT

        assertThat(folders.includes("Music/")).isTrue()
        assertThat(folders.includes("Music/Queen/Jazz/")).isTrue()
        assertThat(folders.includes("Download/")).isTrue()
        assertThat(folders.includes("Podcasts/")).isFalse()
        assertThat(folders.includes("")).isFalse()
    }

    @Test
    fun `folder is matched by whole name, not by prefix`() {
        assertThat(FolderConfig.DEFAULT.includes("MusicVideos/")).isFalse()
        assertThat(FolderConfig.DEFAULT.includes("Downloads/")).isFalse()
    }

    @Test
    fun `excluded folder is skipped with its subfolders`() {
        val folders = FolderConfig(excluded = listOf("Music/Ringtones/"))

        assertThat(folders.includes("Music/Ringtones/")).isFalse()
        assertThat(folders.includes("Music/Ringtones/Old/")).isFalse()
        assertThat(folders.includes("Music/Rock/")).isTrue()
    }

    @Test
    fun `folders match regardless of case and slashes`() {
        // Общее хранилище Android не различает регистр: music и Music — одна папка.
        val folders = FolderConfig(included = listOf("/music"), excluded = listOf("MUSIC/podcasts"))

        assertThat(folders.includes("Music/Queen/")).isTrue()
        assertThat(folders.includes("Music/Podcasts/")).isFalse()
        assertThat(folders.includes("music/queen")).isTrue()
    }

    @Test
    fun `storage root as included folder takes everything`() {
        val folders = FolderConfig(included = listOf("/"))

        assertThat(folders.includes("")).isTrue()
        assertThat(folders.includes("Podcasts/")).isTrue()
    }

    @Test
    fun `folder of a file comes from its absolute path`() {
        assertThat(FolderConfig.folderOf("/storage/emulated/0/Music/Queen/Jazz.mp3")).isEqualTo("Music/Queen/")
        assertThat(FolderConfig.folderOf("/storage/1A2B-3C4D/Download/a.flac")).isEqualTo("Download/")
        assertThat(FolderConfig.folderOf("/storage/emulated/0/a.mp3")).isEqualTo("")
    }

    @Test
    fun `path outside shared storage has no folder`() {
        assertThat(FolderConfig.folderOf("/data/user/0/app/files/a.mp3")).isNull()
        assertThat(FolderConfig.folderOf("")).isNull()
    }

    @Test
    fun `included folder is added once, in canonical form, and stops being excluded`() {
        val config = FolderConfig(included = listOf("Music/"), excluded = listOf("Podcasts/"))

        val changed = config.include("podcasts").include("/Podcasts/").include("music")

        assertThat(changed.included).containsExactly("Music/", "podcasts/").inOrder()
        assertThat(changed.excluded).isEmpty()
    }

    @Test
    fun `excluded folder stops being included`() {
        val changed = FolderConfig.DEFAULT.exclude("Download")

        assertThat(changed.included).containsExactly("Music/")
        assertThat(changed.excluded).containsExactly("Download/")
        assertThat(changed.includes("Download/Telegram/")).isFalse()
    }

    @Test
    fun `removed folder leaves both lists`() {
        val config = FolderConfig(included = listOf("Music/", "Download/"), excluded = listOf("Music/Rain/"))

        val changed = config.remove("download/").remove("Music/Rain")

        assertThat(changed.included).containsExactly("Music/")
        assertThat(changed.excluded).isEmpty()
    }
}
