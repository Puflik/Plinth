package io.github.puflik.plinth.library

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.db.PlinthDatabase
import io.github.puflik.plinth.library.scan.ScannedTrack
import io.github.puflik.plinth.library.sort.SortKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Хранилище сканера v0.1 на Room (C3.3). Общий контракт фонотеки Room с D3b
 * не проходит — исполнителей не делит; до D3c проверяется то, что ломается
 * только в SQL. База в памяти: тест ничего не оставляет на диске.
 */
class RoomLibraryRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, PlinthDatabase::class.java).build()
    private val repository = RoomLibraryRepository(database.trackDao(), SortKeys())

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Сканер помечает пропавшими все треки удалённой папки разом. Столько
     * параметров одним запросом SQLite не примет: 999 до версии 3.32
     * (Android 8–11), 32 766 после.
     */
    @Test
    fun marking_more_tracks_missing_than_sqlite_binds_at_once() =
        runBlocking {
            repository.upsert(listOf(track(id = 1), track(id = MANY_TRACKS)))

            repository.markMissing((1L..MANY_TRACKS).toList())

            assertThat(repository.knownVersions()).isEmpty()
        }

    private fun track(id: Long) =
        ScannedTrack(
            id = id,
            uri = "content://media/external/audio/media/$id",
            title = "Track $id",
            duration = 3.minutes,
            folder = "Music/",
            modifiedAt = id,
        )

    private companion object {
        const val MANY_TRACKS = 40_000L
    }
}
