package io.github.puflik.plinth.library.scan

import android.os.Build
import androidx.room.Room
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.RoomLibraryRepository
import io.github.puflik.plinth.library.db.PlinthDatabase
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.sort.SortKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Весь путь скана на настоящих частях (C2): системный сканер Android →
 * `MediaStoreSource` → `LibraryScanner` → Room. Библиотека видит файлы с
 * тегами, а удалённый файл пропадает после следующего скана.
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class MediaStoreScanTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = TagFixtures(context.contentResolver)
    private val source = MediaStoreSource(context.contentResolver, Dispatchers.IO)
    private val database = Room.inMemoryDatabaseBuilder(context, PlinthDatabase::class.java).build()
    private val repository = RoomLibraryRepository(database.trackDao(), SortKeys())
    private val scanner = LibraryScanner(source, repository)

    /** Только папка фикстур: остальное на устройстве тесту не принадлежит. */
    private val folders = FolderConfig(included = listOf(TagFixtures.FOLDER))

    @Before
    fun putFixtures() {
        fixtures.removeAll()
        TagFixtures.ALL.forEach(fixtures::put)
    }

    @After
    fun cleanUp() {
        fixtures.removeAll()
        database.close()
    }

    @Test
    fun scan_fills_the_library_and_notices_removed_files() =
        runBlocking {
            fixtures.awaitScanned(source)

            val first = scanner.scan(folders)

            assertThat(first).isEqualTo(ScanResult(found = 4, updated = 4, missing = 0))
            assertThat(titles()).containsExactly("FLAC Silence", "M4A Silence", "plinth-untagged", "Тишина").inOrder()
            assertThat(repository.albums().first())
                .containsExactly(Album("Fixtures", "Plinth Various", 3), Album("PlinthTest", null, 1))
                .inOrder()

            fixtures.remove(TagFixtures.FLAC)
            fixtures.awaitScanned(source, expected = TagFixtures.ALL.size - 1)
            val second = scanner.scan(folders)

            assertThat(second).isEqualTo(ScanResult(found = 3, updated = 0, missing = 1))
            assertThat(titles()).doesNotContain("FLAC Silence")
        }

    private suspend fun titles(): List<String> = repository.tracks().first().map(LibraryTrack::title)
}
