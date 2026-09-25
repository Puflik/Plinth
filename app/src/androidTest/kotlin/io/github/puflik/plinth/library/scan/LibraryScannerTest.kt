package io.github.puflik.plinth.library.scan

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.library.model.FolderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Скан фонотеки ядром (D3c): тома и папки — от приложения, обход, теги и
 * каталог — ядро. Том — папка в `cacheDir`, файлы — не звук: такие скан
 * добавляет под именем файла.
 */
class LibraryScannerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "scanner-" + UUID.randomUUID())
    private val volume = File(root, "volume")
    private val core = PlinthCore(LogLevel.INFO, File(root, "core"), CoreErrors())
    private var canRead = true
    private val scanner = LibraryScanner(core, { listOf(volume) }, Dispatchers.IO, canRead = { canRead })

    @After
    fun tearDown() {
        core.close()
        root.deleteRecursively()
    }

    @Test
    fun first_scan_stores_files_of_scanned_folders_only() =
        runBlocking<Unit> {
            put("Music/Queen/a.mp3", "Download/b.mp3", "Ringtones/c.mp3")

            val result = scanner.scan(FolderConfig.DEFAULT)

            assertThat(result).isEqualTo(ScanResult(found = 2, updated = 2, missing = 0))
            assertThat(titles()).containsExactly("a", "b")
        }

    @Test
    fun unchanged_files_are_not_read_again_and_gone_ones_go_missing() =
        runBlocking<Unit> {
            put("Music/a.mp3", "Music/b.mp3")
            scanner.scan(FolderConfig.DEFAULT)
            File(volume, "Music/b.mp3").delete()

            val result = scanner.scan(FolderConfig.DEFAULT)

            assertThat(result).isEqualTo(ScanResult(found = 1, updated = 0, missing = 1))
            assertThat(titles()).containsExactly("a")
        }

    @Test
    fun excluding_a_folder_hides_its_tracks_on_the_next_scan() =
        runBlocking<Unit> {
            put("Music/a.mp3", "Download/b.mp3")
            scanner.scan(FolderConfig.DEFAULT)

            scanner.scan(FolderConfig.DEFAULT.exclude("Download/"))

            assertThat(titles()).containsExactly("a")
        }

    /** Без доступа к музыке ядро увидело бы одни файлы приложения, и всё прочее ушло бы в пропавшие. */
    @Test
    fun without_access_to_music_the_library_is_left_as_it_was() =
        runBlocking<Unit> {
            put("Music/a.mp3")
            scanner.scan(FolderConfig.DEFAULT)
            canRead = false
            File(volume, "Music/a.mp3").delete()

            val failure = runCatching { scanner.scan(FolderConfig.DEFAULT) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SecurityException::class.java)
            assertThat(titles()).containsExactly("a")
        }

    /** Ход — сколько новых и изменённых файлов обработано; пока файлы ищутся, итог неизвестен. */
    @Test
    fun progress_goes_from_unknown_to_every_new_file() =
        runBlocking<Unit> {
            put("Music/a.mp3", "Music/b.mp3", "Music/c.mp3")
            val reports = mutableListOf<Pair<Int, Int>>()

            scanner.scan(FolderConfig.DEFAULT) { done, total -> synchronized(reports) { reports += done to total } }

            assertThat(reports.first()).isEqualTo(0 to 0)
            assertThat(reports.last()).isEqualTo(3 to 3)
            assertThat(reports.map { it.first }).isInOrder()
        }

    /** Отмена работы останавливает ядро на ближайшем отчёте о ходе. */
    @Test
    fun cancelled_scan_stops_the_core() =
        runBlocking<Unit> {
            put("Music/a.mp3", "Music/b.mp3")
            var failure: Throwable? = null

            launch(Dispatchers.IO) {
                val scope = this
                failure =
                    runCatching { scanner.scan(FolderConfig.DEFAULT) { _, _ -> scope.cancel() } }.exceptionOrNull()
            }.join()

            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(titles()).isEmpty()
        }

    private fun put(vararg paths: String) {
        for (path in paths) {
            File(volume, path).apply {
                parentFile?.mkdirs()
                writeText("not audio")
            }
        }
    }

    private fun titles(): List<String> = core.library.tracks().map { it.title }
}
