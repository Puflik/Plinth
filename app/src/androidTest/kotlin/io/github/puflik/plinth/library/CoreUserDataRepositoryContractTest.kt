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
 * `CoreUserDataRepository` проходит общий контракт лайков и истории (D4a) —
 * тот же класс, что `FakeUserDataRepository` проходит на JVM.
 *
 * Своё ядро на каждый тест в `cacheDir`; файлы попадают в фонотеку тестовым
 * вызовом `seed_for_test`, как в контракте фонотеки.
 */
class CoreUserDataRepositoryContractTest : UserDataRepositoryContractTest() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dataDir = File(context.cacheDir, "core-user-data-" + UUID.randomUUID())
    private lateinit var core: PlinthCore

    override fun createRepository(): UserDataRepository {
        core = PlinthCore(LogLevel.INFO, dataDir, CoreErrors())
        return CoreUserDataRepository(core, Dispatchers.IO)
    }

    override fun closeRepository(repository: UserDataRepository) {
        core.close()
        dataDir.deleteRecursively()
    }

    override suspend fun seed(
        repository: UserDataRepository,
        paths: List<String>,
    ) = withContext(Dispatchers.IO) {
        core.seedForTest(
            paths.map { path ->
                CoreTestFile(
                    uri = path,
                    folder = "Music/",
                    title = path.substringAfterLast('/'),
                    artist = null,
                    album = null,
                    albumArtist = null,
                    disc = null,
                    number = null,
                    duration = null,
                )
            },
        )
    }
}
