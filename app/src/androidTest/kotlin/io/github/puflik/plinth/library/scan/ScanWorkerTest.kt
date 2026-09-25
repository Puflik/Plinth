package io.github.puflik.plinth.library.scan

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.library.FakeFolderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * `ScanWorker` сам по себе (C2.4, D3c): сканирует папки из настроек ядром,
 * публикует ход и отдаёт итог выходными данными работы. Ядро и том — во
 * временной папке; настоящий путь через WorkManager и Hilt — в
 * `LibraryScanWorkTest`.
 */
class ScanWorkerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "worker-" + UUID.randomUUID())
    private val volume = File(root, "volume")
    private val core = PlinthCore(LogLevel.INFO, File(root, "core"), CoreErrors())
    private val folders = FakeFolderSettings()
    private val published = mutableListOf<Data>()
    private var canRead = true

    @After
    fun tearDown() {
        core.close()
        root.deleteRecursively()
    }

    @Test
    fun scan_result_is_the_work_output() =
        runBlocking<Unit> {
            put("Music/a.mp3", "Ringtones/b.mp3")

            val result = worker().doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.success(workDataOf(ScanWorker.KEY_FOUND to 1)))
            assertThat(core.library.tracks().map { it.title }).containsExactly("a")
        }

    @Test
    fun progress_is_published_while_scanning() =
        runBlocking<Unit> {
            put("Music/a.mp3", "Download/b.mp3")

            worker().doWork()

            val reports =
                synchronized(published) {
                    published.map { it.getInt(ScanWorker.KEY_WRITTEN, -1) to it.getInt(ScanWorker.KEY_TOTAL, -1) }
                }
            assertThat(reports.first()).isEqualTo(0 to 0)
            assertThat(reports.last()).isEqualTo(2 to 2)
        }

    @Test
    fun excluded_folder_from_the_settings_is_not_scanned() =
        runBlocking<Unit> {
            folders.update { it.exclude("Download/") }
            put("Music/a.mp3", "Download/b.mp3")

            worker().doWork()

            assertThat(core.library.tracks().map { it.title }).containsExactly("a")
        }

    @Test
    fun lost_permission_fails_the_work() =
        runBlocking {
            canRead = false

            assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.failure())
        }

    private fun worker(): ScanWorker =
        TestListenableWorkerBuilder<ScanWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = ScanWorker(
                        appContext,
                        workerParameters,
                        LibraryScanner(core, { listOf(volume) }, Dispatchers.IO, canRead = { canRead }),
                        folders,
                    )
                },
            ).setProgressUpdater { _, _, data ->
                synchronized(published) { published += data }
                Futures.immediateVoidFuture()
            }.build()

    private fun put(vararg paths: String) {
        for (path in paths) {
            File(volume, path).apply {
                parentFile?.mkdirs()
                writeText("not audio")
            }
        }
    }
}
