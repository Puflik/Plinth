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

    override suspend fun seed(
        repository: PlaylistRepository,
        paths: List<String>,
    ): List<TrackId> =
        withContext(Dispatchers.IO) {
            core.seedForTest(
                paths.map { CoreTestFile(uri = it, folder = "Music/", title = it.substringAfterLast('/')) },
            )
            paths.map { checkNotNull(core.library.trackAt(it)) { "no track at $it" } }
        }

    override suspend fun hide(
        repository: PlaylistRepository,
        paths: List<String>,
    ) = withContext(Dispatchers.IO) { core.hideForTest(paths) }
}
