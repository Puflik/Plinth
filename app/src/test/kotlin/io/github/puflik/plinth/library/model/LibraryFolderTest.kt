package io.github.puflik.plinth.library.model

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.sort.SortKeys
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/** Дерево папок фонотеки (C4.1): файловая структура как есть. */
class LibraryFolderTest {
    @Test
    fun `empty library is an empty root`() =
        runTest {
            val root = tree(emptyList())

            assertThat(root.path).isEmpty()
            assertThat(root.folders).isEmpty()
            assertThat(root.tracks).isEmpty()
            assertThat(root.trackCount).isEqualTo(0)
        }

    @Test
    fun `nested folders are built from track paths`() =
        runTest {
            val song = track(1, "Music/Queen/A Night at the Opera/")

            val root = tree(listOf(song))

            val music = root.folders.single()
            assertThat(music.name).isEqualTo("Music")
            assertThat(music.path).isEqualTo("Music/")
            assertThat(music.tracks).isEmpty()
            val queen = music.folders.single()
            assertThat(queen.path).isEqualTo("Music/Queen/")
            val album = queen.folders.single()
            assertThat(album.name).isEqualTo("A Night at the Opera")
            assertThat(album.path).isEqualTo("Music/Queen/A Night at the Opera/")
            assertThat(album.tracks).containsExactly(song)
        }

    @Test
    fun `tracks keep the order they came in`() =
        runTest {
            val tracks = listOf(track(3, "Download/"), track(1, "Download/"), track(2, "Download/"))

            val download = tree(tracks).folders.single()

            assertThat(download.tracks).containsExactlyElementsIn(tracks).inOrder()
        }

    @Test
    fun `subfolders go in natural order without articles`() =
        runTest {
            val tracks =
                listOf("Disc 10", "The Beatles", "Disc 2", "abba", "Coldplay")
                    .mapIndexed { index, name -> track(index.toLong(), "Music/$name/") }

            val music = tree(tracks).open("Music/")
            val names = music.folders.map(LibraryFolder::name)

            assertThat(names).containsExactly("abba", "The Beatles", "Coldplay", "Disc 2", "Disc 10").inOrder()
        }

    @Test
    fun `track count includes every subfolder`() =
        runTest {
            val tracks =
                listOf(
                    track(1, "Music/"),
                    track(2, "Music/Queen/"),
                    track(3, "Music/Queen/Live/"),
                    track(4, "Download/"),
                )

            val root = tree(tracks)

            assertThat(root.trackCount).isEqualTo(4)
            assertThat(root.open("Music/").trackCount).isEqualTo(3)
            assertThat(root.open("Music/Queen/").trackCount).isEqualTo(2)
        }

    @Test
    fun `tracks at the storage root stay in the root`() =
        runTest {
            val loose = track(1, "")

            val root = tree(listOf(loose))

            assertThat(root.tracks).containsExactly(loose)
            assertThat(root.folders).isEmpty()
        }

    @Test
    fun `open finds a folder by its path`() =
        runTest {
            val root = tree(listOf(track(1, "Music/Queen/Live/")))

            assertThat(root.open("Music/Queen/").path).isEqualTo("Music/Queen/")
            assertThat(root.open("").path).isEmpty()
        }

    @Test
    fun `open of a vanished folder stops at the nearest one that is left`() =
        runTest {
            val root = tree(listOf(track(1, "Music/Queen/")))

            assertThat(root.open("Music/Queen/Live/").path).isEqualTo("Music/Queen/")
            assertThat(root.open("Download/Old/").path).isEmpty()
        }

    @Test
    fun `parent is one level up and the root has none`() =
        runTest {
            val root = tree(listOf(track(1, "Music/Queen/")))

            assertThat(root.open("Music/Queen/").parentPath).isEqualTo("Music/")
            assertThat(root.open("Music/").parentPath).isEmpty()
            assertThat(root.parentPath).isNull()
        }

    /** Имена папок сортирует не дерево, а ключи хранилища — один запрос на всё дерево. */
    @Test
    fun `folders go in the order of the storage keys asked once`() =
        runTest {
            val asked = mutableListOf<List<String>>()
            val tracks = listOf(track(1, "Music/Zz/"), track(2, "Music/Aa/"), track(3, "Download/Zz/"))

            val root =
                LibraryFolder.tree(tracks) { names ->
                    asked += names
                    names.map { if (it.startsWith("Z")) "0" else "1" }
                }

            assertThat(root.open("Music/").folders.map(LibraryFolder::name)).containsExactly("Zz", "Aa").inOrder()
            assertThat(asked.single()).containsExactly("Music", "Zz", "Aa", "Download")
        }

    @Test
    fun `empty library asks no keys`() =
        runTest {
            val root = LibraryFolder.tree(emptyList()) { error("ключи не нужны") }

            assertThat(root).isEqualTo(LibraryFolder.EMPTY)
        }

    private suspend fun tree(tracks: List<LibraryTrack>) = LibraryFolder.tree(tracks) { it.map(KEYS::of) }

    private fun track(
        id: Long,
        folder: String,
    ) = LibraryTrack(
        id = TrackId("track-$id"),
        uri = "/storage/emulated/0/${folder}track-$id.mp3",
        title = "Track $id",
        duration = 3.minutes,
        folder = folder,
    )

    private companion object {
        val KEYS = SortKeys()
    }
}
