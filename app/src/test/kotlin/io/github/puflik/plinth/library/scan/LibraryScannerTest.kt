package io.github.puflik.plinth.library.scan

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.model.FolderConfig
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LibraryScannerTest {
    private val source = FakeScanSource()
    private val store = FakeScanStore()
    private val scanner = LibraryScanner(source, store)

    @Test
    fun `first scan stores tracks of scanned folders only`() =
        runTest {
            source.files =
                listOf(
                    row(id = 1, folder = "Music/Queen/"),
                    row(id = 2, folder = "Download/"),
                    row(id = 3, folder = "Ringtones/"),
                )

            val result = scanner.scan(FolderConfig.DEFAULT)

            assertThat(ids()).containsExactly(1L, 2L)
            assertThat(result).isEqualTo(ScanResult(found = 2, updated = 2, missing = 0))
        }

    @Test
    fun `tracks are stored with tags read from rows`() =
        runTest {
            source.files = listOf(row(id = 1, folder = "Music/", title = null, track = 2003))

            scanner.scan(FolderConfig.DEFAULT)

            val track = store.present().single()
            assertThat(track.title).isEqualTo("file-1")
            assertThat(track.discNumber).isEqualTo(2)
            assertThat(track.trackNumber).isEqualTo(3)
        }

    @Test
    fun `unchanged files are not read again`() =
        runTest {
            source.files = listOf(row(id = 1, title = "Old"))
            scanner.scan(FolderConfig.DEFAULT)
            source.files = listOf(row(id = 1, title = "New"))

            val result = scanner.scan(FolderConfig.DEFAULT)

            assertThat(titles()).containsExactly("Old")
            assertThat(result).isEqualTo(ScanResult(found = 1, updated = 0, missing = 0))
        }

    @Test
    fun `changed files are read again`() =
        runTest {
            source.files = listOf(row(id = 1, title = "Old"))
            scanner.scan(FolderConfig.DEFAULT)
            source.files = listOf(row(id = 1, title = "New", modified = LATER))

            val result = scanner.scan(FolderConfig.DEFAULT)

            assertThat(titles()).containsExactly("New")
            assertThat(result.updated).isEqualTo(1)
        }

    @Test
    fun `without access to music the library is left as it was`() =
        runTest {
            source.files = listOf(row(id = 1), row(id = 2))
            scanner.scan(FolderConfig.DEFAULT)
            // Доступ отозвали: MediaStore отдаёт только файлы самого приложения — пустоту.
            source.files = emptyList()
            val blind = LibraryScanner(source, store, canRead = { false })

            val failure = runCatching { blind.scan(FolderConfig.DEFAULT) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SecurityException::class.java)
            assertThat(ids()).containsExactly(1L, 2L)
        }

    @Test
    fun `files that are gone go missing`() =
        runTest {
            source.files = listOf(row(id = 1), row(id = 2))
            scanner.scan(FolderConfig.DEFAULT)
            source.files = listOf(row(id = 1))

            val result = scanner.scan(FolderConfig.DEFAULT)

            assertThat(ids()).containsExactly(1L)
            assertThat(result).isEqualTo(ScanResult(found = 1, updated = 0, missing = 1))
        }

    @Test
    fun `excluding a folder hides its tracks on the next scan`() =
        runTest {
            source.files = listOf(row(id = 1, folder = "Music/"), row(id = 2, folder = "Download/"))
            scanner.scan(FolderConfig.DEFAULT)

            scanner.scan(FolderConfig(excluded = listOf("Download/")))

            assertThat(ids()).containsExactly(1L)
        }

    @Test
    fun `file that comes back is listed again`() =
        runTest {
            source.files = listOf(row(id = 1))
            scanner.scan(FolderConfig.DEFAULT)
            source.files = emptyList()
            scanner.scan(FolderConfig.DEFAULT)
            source.files = listOf(row(id = 1))

            val result = scanner.scan(FolderConfig.DEFAULT)

            assertThat(ids()).containsExactly(1L)
            assertThat(result.updated).isEqualTo(1)
        }

    @Test
    fun `progress is reported after every written chunk`() =
        runTest {
            val total = LibraryScanner.WRITE_CHUNK * 2 + 1
            source.files = (1L..total).map { row(id = it) }
            val reports = mutableListOf<Pair<Int, Int>>()

            scanner.scan(FolderConfig.DEFAULT) { written, changed -> reports += written to changed }

            val chunk = LibraryScanner.WRITE_CHUNK
            assertThat(reports.map { it.first }).containsExactly(0, chunk, 2 * chunk, total).inOrder()
            assertThat(reports.map { it.second }.distinct()).containsExactly(total)
        }

    @Test
    fun `cancelled scan keeps what it wrote and the next scan writes the rest`() =
        runTest {
            val total = LibraryScanner.WRITE_CHUNK * 2 + 1
            source.files = (1L..total).map { row(id = it) }

            launch {
                scanner.scan(FolderConfig.DEFAULT) { written, _ ->
                    if (written == LibraryScanner.WRITE_CHUNK) currentCoroutineContext().cancel()
                }
            }.join()

            assertThat(ids()).hasSize(LibraryScanner.WRITE_CHUNK)
            val result = scanner.scan(FolderConfig.DEFAULT)
            assertThat(ids()).hasSize(total)
            assertThat(result.updated).isEqualTo(total - LibraryScanner.WRITE_CHUNK)
        }

    private fun ids(): List<Long> = store.present().map(ScannedTrack::id)

    private fun titles(): List<String> = store.present().map(ScannedTrack::title)

    private fun row(
        id: Long,
        folder: String = "Music/",
        title: String? = "Track $id",
        track: Int? = null,
        modified: Long = EARLIER,
    ) = MediaStoreRow(
        id = id,
        uri = "content://media/external/audio/media/$id",
        displayName = "file-$id.mp3",
        title = title,
        artist = null,
        album = null,
        albumArtist = null,
        track = track,
        durationMs = 1_000,
        folder = folder,
        dateModified = modified,
    )

    private class FakeScanSource : ScanSource {
        var files: List<MediaStoreRow> = emptyList()

        override suspend fun rows(): List<MediaStoreRow> = files
    }

    private companion object {
        const val EARLIER = 1_700_000_000L
        const val LATER = 1_700_000_100L
    }
}
