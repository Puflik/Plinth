package io.github.puflik.plinth.backup

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
 * `CoreJournalMirror` проходит общий контракт копии журнала (C4) — тот же
 * класс, что `FakeJournalMirror` проходит на JVM.
 *
 * Установка — своё ядро в `cacheDir`, переустановка — новое ядро рядом:
 * новый каталог данных, новый журнал, новые ID треков. Файлы попадают в
 * фонотеку тестовым вызовом `seed_for_test`, как скан.
 */
class CoreJournalMirrorContractTest : JournalMirrorContractTest() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    override fun install(): Installation = CoreInstallation(File(context.cacheDir, "core-mirror-" + UUID.randomUUID()))

    override fun uninstall(installation: Installation) = (installation as CoreInstallation).close()

    private class CoreInstallation(
        private val dataDir: File,
    ) : Installation {
        private val core = PlinthCore(LogLevel.INFO, dataDir, CoreErrors())

        override val mirror: JournalMirror = CoreJournalMirror(core, Dispatchers.IO)

        override suspend fun scan(paths: List<String>) =
            io {
                core.seedForTest(
                    paths.map { path ->
                        CoreTestFile(
                            uri = path,
                            folder = "Music/",
                            title = path.substringAfterLast('/'),
                            artist = "Plinth",
                        )
                    },
                )
            }

        override suspend fun like(path: String) = io { core.journal.like(track(path)) }

        override suspend fun playlist(
            name: String,
            paths: List<String>,
        ) = io {
            val playlist = core.journal.createPlaylist(name)
            paths.forEach { core.journal.addToPlaylist(playlist.id, track(it)) }
        }

        override suspend fun liked(): Set<String> =
            io {
                core.library
                    .likedTracks()
                    .mapNotNull { it.uri }
                    .toSet()
            }

        override suspend fun playlists(): Map<String, List<String>> =
            io {
                core.journal.playlists().associate { playlist ->
                    playlist.name to core.library.playlistTracks(playlist.id).mapNotNull { it.track.uri }
                }
            }

        fun close() {
            core.close()
            dataDir.deleteRecursively()
        }

        private fun track(path: String): TrackId =
            checkNotNull(core.library.trackAt(path)) { "not in the library: $path" }

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    }
}
