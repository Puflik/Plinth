package io.github.puflik.plinth.library

import androidx.test.platform.app.InstrumentationRegistry
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.CoreTestFile
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.ffi.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * `CorePlaylistRepository` проходит общий контракт плейлистов (D4b) — тот же
 * класс, что `FakePlaylistRepository` проходит на JVM.
 *
 * Своё ядро на каждый тест в `cacheDir`; файлы попадают в фонотеку тестовым
 * вызовом `seed_for_test` и пропадают `hide_for_test`, как в контракте фонотеки.
 */
class CorePlaylistRepositoryContractTest : PlaylistRepositoryContractTest() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dataDir = File(context.cacheDir, "core-playlists-" + UUID.randomUUID())
    private lateinit var core: PlinthCore

    override fun createRepository(): PlaylistRepository {
        core = PlinthCore(LogLevel.INFO, dataDir, CoreErrors())
        return CorePlaylistRepository(core, Dispatchers.IO)
    }

    override fun closeRepository(repository: PlaylistRepository) {
        core.close()
        dataDir.deleteRecursively()
    }

    override suspend fun seedFiles(
        repository: PlaylistRepository,
        files: List<TaggedFile>,
    ): List<TrackId> =
        withContext(Dispatchers.IO) {
            core.seedForTest(
                files.map {
                    CoreTestFile(
                        uri = it.path,
                        folder = it.folder,
                        title = it.title ?: it.path.substringAfterLast('/'),
                        artist = it.artist,
                        duration = it.duration,
                    )
                },
            )
            files.map { checkNotNull(core.library.trackAt(it.path)) { "no track at ${it.path}" } }
        }

    override suspend fun hide(
        repository: PlaylistRepository,
        paths: List<String>,
    ) = withContext(Dispatchers.IO) { core.hideForTest(paths) }
}
