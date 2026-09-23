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
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * `ScanWorker` сам по себе (C2.4): сканирует папки по умолчанию, публикует
 * прогресс и отдаёт итог выходными данными работы. Источник и хранилище —
 * фейки; настоящий путь через WorkManager и Hilt — в `LibraryScanWorkTest`.
 */
class ScanWorkerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = FakeLibraryRepository()
    private val published = mutableListOf<Data>()

    @Test
    fun scan_result_is_the_work_output() =
        runBlocking<Unit> {
            val worker = worker { listOf(row(id = 1, folder = "Music/"), row(id = 2, folder = "Ringtones/")) }

            val result = worker.doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.success(workDataOf(ScanWorker.KEY_FOUND to 1)))
            assertThat(repository.tracks().first().map(LibraryTrack::id)).containsExactly(1L)
        }

    @Test
    fun progress_is_published_while_writing() =
        runBlocking<Unit> {
            val worker = worker { listOf(row(id = 1, folder = "Music/"), row(id = 2, folder = "Download/")) }

            worker.doWork()

            val reports = published.map { it.getInt(ScanWorker.KEY_WRITTEN, -1) to it.getInt(ScanWorker.KEY_TOTAL, -1) }
            assertThat(reports).containsExactly(0 to 2, 2 to 2).inOrder()
        }

    @Test
    fun lost_permission_fails_the_work() =
        runBlocking<Unit> {
            val worker = worker { throw SecurityException("разрешение отозвано") }

            assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.failure())
        }

    private fun worker(source: ScanSource): ScanWorker =
        TestListenableWorkerBuilder<ScanWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = ScanWorker(appContext, workerParameters, LibraryScanner(source, repository))
                },
            ).setProgressUpdater { _, _, data ->
                published += data
                Futures.immediateVoidFuture()
            }.build()

    private fun row(
        id: Long,
        folder: String,
    ) = MediaStoreRow(
        id = id,
        uri = "content://media/external/audio/media/$id",
        displayName = "file-$id.mp3",
        title = "Track $id",
        artist = null,
        album = null,
        albumArtist = null,
        track = null,
        durationMs = 1_000,
        folder = folder,
        dateModified = 1_700_000_000,
    )
}
