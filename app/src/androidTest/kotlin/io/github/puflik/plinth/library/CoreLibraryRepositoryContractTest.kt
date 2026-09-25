package io.github.puflik.plinth.library

import androidx.test.platform.app.InstrumentationRegistry
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.CoreTestFile
import io.github.puflik.plinth.ffi.PlinthCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
}
