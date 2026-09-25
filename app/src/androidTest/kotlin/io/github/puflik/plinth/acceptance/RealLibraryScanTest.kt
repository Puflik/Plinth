package io.github.puflik.plinth.acceptance

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.library.CoreLibraryRepository
import io.github.puflik.plinth.library.model.FolderConfig
import io.github.puflik.plinth.library.permission.MediaPermission
import io.github.puflik.plinth.library.scan.AndroidStorageVolumes
import io.github.puflik.plinth.library.scan.LibraryScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * Скан настоящей фонотеки устройства ядром (D3d): тома — как у приложения,
 * музыка — та, что на устройстве, включая SD-карту.
 *
 * Приёмочный, только по запросу (`-Pacceptance`). Ядро — своё, в `cacheDir`:
 * ни фонотеку приложения, ни его настройки тест не трогает. Папки — аргумент
 * `folders` через запятую (`-e folders Music/`), без него — тома целиком.
 * Итог — в logcat под тегом `PlinthAcceptance`: время, числа, папки.
 */
class RealLibraryScanTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dataDir = File(context.cacheDir, "real-library-" + UUID.randomUUID())
    private val core = PlinthCore(LogLevel.INFO, dataDir, CoreErrors())

    @After
    fun cleanUp() {
        core.close()
        dataDir.deleteRecursively()
    }

    @Test
    fun the_device_library_scans_and_every_track_is_a_readable_file() =
        runBlocking<Unit> {
            grantMusicAccess()
            val volumes = AndroidStorageVolumes(context).roots()
            val folders = FolderConfig(included = folders())
            val scanner = LibraryScanner(core, { volumes }, Dispatchers.IO)
            val library = CoreLibraryRepository(core, Dispatchers.IO)

            val (first, firstTime) = measureTimedValue { scanner.scan(folders) }
            val (again, againTime) = measureTimedValue { scanner.scan(folders) }
            val tracks = library.tracks().first()
            val albums = library.albums().first()
            val artists = library.artists().first()
            val covers = withContext(Dispatchers.IO) { albums.take(COVER_SAMPLE).count { hasCover(it.coverTrackUri) } }

            Log.i(TAG, "Тома: $volumes; папки: ${folders.included}")
            Log.i(TAG, "Первый скан: $first за $firstTime; повторный: $again за $againTime")
            Log.i(TAG, "Треков ${tracks.size}, альбомов ${albums.size}, исполнителей ${artists.size}")
            Log.i(TAG, "Обложки: у $covers из ${minOf(albums.size, COVER_SAMPLE)} первых альбомов")
            tracks
                .groupingBy { it.folder.substringBefore('/') }
                .eachCount()
                .forEach { (top, count) -> Log.i(TAG, "  $top/: $count") }
            assumeTrue("на устройстве нет музыки", first.found > 0)
            assertThat(tracks).hasSize(first.found)
            assertThat(again.updated + again.missing).isEqualTo(0)
            assertThat(firstTime).isLessThan(LIMIT)
            val unreadable = tracks.filterNot { File(it.uri).canRead() }.map { it.uri }
            assertWithMessage("пути треков, которые не прочесть").that(unreadable).isEmpty()
        }

    /** `pm grant` от оболочки: `UiAutomation.grantRuntimePermission` есть только с Android 9. */
    private fun grantMusicAccess() {
        val command = "pm grant ${context.packageName} ${MediaPermission.name()}"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }

    private fun folders(): List<String> =
        InstrumentationRegistry
            .getArguments()
            .getString("folders")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?: listOf(WHOLE_VOLUME)

    private fun hasCover(path: String?): Boolean =
        path != null && runCatching { core.library.artwork(path) }.getOrNull() != null

    private companion object {
        const val TAG = "PlinthAcceptance"

        /** Пустая папка — том целиком. */
        const val WHOLE_VOLUME = ""
        const val COVER_SAMPLE = 50
        val LIMIT: Duration = 30.seconds
    }
}
