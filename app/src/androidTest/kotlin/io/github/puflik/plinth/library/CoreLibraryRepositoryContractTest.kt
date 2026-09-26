package io.github.puflik.plinth.library

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.CoreTestFile
import io.github.puflik.plinth.ffi.NewPlay
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.library.model.LibraryTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * `CoreLibraryRepository` проходит общий контракт фонотеки (D3b) — тот же
 * класс, что `FakeLibraryRepository` проходит на JVM. Порядок, группировку
 * и поиск считает ядро на Rust, поэтому тесты живут на эмуляторе.
 *
 * Своё ядро на каждый тест в `cacheDir`; наполняется оно тестовым вызовом
 * `seed_for_test` — тем же путём записи, что у скана, только без файлов.
 */
class CoreLibraryRepositoryContractTest : LibraryRepositoryContractTest() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // JUnit создаёт экземпляр класса на каждый тест, а контракт — одно
    // хранилище на тест: одного ядра на экземпляр достаточно.
    private val dataDir = File(context.cacheDir, "core-contract-" + UUID.randomUUID())
    private lateinit var core: PlinthCore
    private var playedAt = Instant.fromEpochMilliseconds(1_790_000_000_000)

    override fun createRepository(): LibraryRepository {
        core = PlinthCore(LogLevel.INFO, dataDir, CoreErrors())
        return CoreLibraryRepository(core, Dispatchers.IO)
    }

    override fun closeRepository(repository: LibraryRepository) {
        core.close()
        dataDir.deleteRecursively()
    }

    override suspend fun seed(
        repository: LibraryRepository,
        files: List<TaggedFile>,
    ) = withContext(Dispatchers.IO) { core.seedForTest(files.map { it.toCore() }) }

    override suspend fun hide(
        repository: LibraryRepository,
        paths: List<String>,
    ) = withContext(Dispatchers.IO) { core.hideForTest(paths) }

    override suspend fun like(
        repository: LibraryRepository,
        paths: List<String>,
    ) = withContext(Dispatchers.IO) { paths.forEach { core.journal.like(trackAt(it)) } }

    // Дослушан до конца — засчитан по правилу Last.fm; каждое следующее — на десять минут позже.
    override suspend fun play(
        repository: LibraryRepository,
        path: String,
        times: Int,
    ) = withContext(Dispatchers.IO) {
        val track = trackAt(path)
        repeat(times) {
            playedAt += PLAY_GAP
            core.journal.recordPlay(
                NewPlay(track, playedAt, utcOffsetMinutes = 0, listened = PLAY_LENGTH, trackLength = PLAY_LENGTH),
            )
        }
    }

    private fun trackAt(path: String) = checkNotNull(core.library.trackAt(path)) { "нет трека у $path" }

    /** Лайк из журнала приходит в открытый список (D4a): он перечитывается и по сигналу пользовательского. */
    @Test
    fun a_like_reaches_the_track_row() =
        runBlocking {
            val repository = createRepository()
            try {
                withTimeout(timeout) {
                    seed(repository, listOf(TaggedFile(path = "/storage/emulated/0/Music/liked.mp3", title = "Liked")))
                    val rows = MutableStateFlow(emptyList<LibraryTrack>())
                    val subscription = launch { repository.tracks().collect { rows.value = it } }
                    val track = rows.first { it.isNotEmpty() }.single()

                    withContext(Dispatchers.IO) { core.journal.like(track.id) }

                    assertThat(rows.first { it.singleOrNull()?.liked == true }.single().id).isEqualTo(track.id)
                    subscription.cancel()
                }
            } finally {
                closeRepository(repository)
            }
        }

    private fun TaggedFile.toCore() =
        CoreTestFile(
            uri = path,
            folder = folder,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            disc = discNumber,
            number = trackNumber,
            duration = duration,
        )

    private companion object {
        val PLAY_GAP = 10.minutes
        val PLAY_LENGTH = 4.minutes
    }
}
