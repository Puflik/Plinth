package io.github.puflik.plinth.library

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.db.PlinthDatabase
import io.github.puflik.plinth.library.sort.SortKeys
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * `RoomLibraryRepository` проходит общий контракт фонотеки (C3.3) — тот же
 * класс, что `FakeLibraryRepository` проходит на JVM. Здесь порядок и
 * группировку списков считает SQLite, поэтому тесты живут на эмуляторе.
 *
 * База в памяти: каждый тест начинает с пустой и ничего не оставляет на диске.
 */
class RoomLibraryRepositoryContractTest : LibraryRepositoryContractTest() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // JUnit создаёт экземпляр класса на каждый тест, а контракт — одно
    // хранилище на тест: одной базы на экземпляр достаточно.
    private lateinit var database: PlinthDatabase

    override fun createRepository(): LibraryRepository {
        database = Room.inMemoryDatabaseBuilder(context, PlinthDatabase::class.java).build()
        return RoomLibraryRepository(database.trackDao(), SortKeys())
    }

    override fun closeRepository(repository: LibraryRepository) {
        database.close()
    }

    // Ниже — то, чего контракт не требует от всех хранилищ: так ломается
    // только SQL.

    /**
     * Сканер помечает пропавшими все треки удалённой папки разом. Столько
     * параметров одним запросом SQLite не примет: 999 до версии 3.32
     * (Android 8–11), 32 766 после.
     */
    @Test
    fun marking_more_tracks_missing_than_sqlite_binds_at_once() =
        runBlocking {
            val repository = createRepository()
            try {
                repository.upsert(listOf(track(id = 1), track(id = MANY_TRACKS)))

                repository.markMissing((1L..MANY_TRACKS).toList())

                assertThat(repository.knownVersions()).isEmpty()
            } finally {
                closeRepository(repository)
            }
        }

    private companion object {
        const val MANY_TRACKS = 40_000L
    }
}
