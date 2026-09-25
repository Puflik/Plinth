package io.github.puflik.plinth.library.scan

import android.os.Build
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import io.github.puflik.plinth.di.LibraryEntryPoint
import io.github.puflik.plinth.library.ScanProgress
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.permission.MediaPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Фоновый скан приложения целиком (C2.4): граф Hilt → WorkManager →
 * `HiltWorkerFactory` → `ScanWorker` → `MediaStore` → база приложения.
 * Ловит то, чего не видят тесты по частям: воркер, который WorkManager не
 * может построить, или WorkManager, настроенный мимо Hilt.
 *
 * Пишет в настоящую базу приложения и потому убирает за собой: второй скан
 * без фикстур помечает их пропавшими.
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class LibraryScanWorkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = EntryPointAccessors.fromApplication<LibraryEntryPoint>(context)
    private val fixtures = TagFixtures(context.contentResolver)
    private val fixtureTitles = TagFixtures.ALL.map { it.title }

    @Before
    fun putFixtures() {
        // Без доступа к музыке приложение не сканирует (ревью №13). Выдача процесс не убивает, отзыв — убил бы.
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(context.packageName, MediaPermission.name())
        fixtures.removeAll()
        TagFixtures.ALL.forEach(fixtures::put)
    }

    @After
    fun removeFixtures() {
        fixtures.removeAll()
    }

    @Test
    fun background_scan_fills_the_app_library_and_notices_removal() =
        runBlocking<Unit> {
            fixtures.awaitScanned(MediaStoreSource(context.contentResolver, Dispatchers.IO))
            val scan = graph.libraryScan()
            val library = graph.libraryRepository()

            withTimeout(TIMEOUT) {
                scan.start()
                library.tracks().first { tracks -> tracks.fixtureTitles().containsAll(fixtureTitles) }
                val done = scan.progress.first { it is ScanProgress.Done || it is ScanProgress.Failed }
                // С доступом к музыке скан видит и чужие файлы — найдено не меньше фикстур.
                assertThat(done).isInstanceOf(ScanProgress.Done::class.java)
                assertThat((done as ScanProgress.Done).found).isAtLeast(TagFixtures.ALL.size)

                fixtures.removeAll()
                scan.start()
                library.tracks().first { tracks -> tracks.fixtureTitles().isEmpty() }
            }
        }

    /**
     * Только треки из папки фикстур: с разрешением на музыку скан видит и
     * чужие файлы, а копии фикстур в других папках (ручные проверки) носят
     * те же названия и не пропадают вместе с фикстурами.
     */
    private fun List<LibraryTrack>.fixtureTitles(): List<String> =
        filter { it.folder == TagFixtures.FOLDER }.map(LibraryTrack::title)

    private companion object {
        val TIMEOUT = 30.seconds
    }
}
