package io.github.puflik.plinth.library.scan

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.ffi.CoreFailure
import io.github.puflik.plinth.library.FolderSettings
import kotlinx.coroutines.flow.first

/**
 * Скан фонотеки в фоне (C2.4, D3c): переживает уход с экрана, ход — через
 * `setProgressAsync` (его сообщает поток ядра), итог — через выходные данные
 * работы.
 *
 * Отмена — штатная для `CoroutineWorker`: WorkManager отменяет корутину, и
 * ядро останавливается на ближайшем отчёте о ходе, сохранив записанное.
 */
@HiltWorker
class ScanWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val scanner: LibraryScanner,
        private val settings: FolderSettings,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            try {
                val result =
                    scanner.scan(settings.folders.first()) { written, total ->
                        setProgressAsync(workDataOf(KEY_WRITTEN to written, KEY_TOTAL to total))
                    }
                AppLog.i(TAG, "done: ${result.found} tracks")
                Result.success(workDataOf(KEY_FOUND to result.found))
            } catch (expected: SecurityException) {
                // Доступа к музыке нет или его отозвали посреди скана: повторять бессмысленно до новой выдачи.
                AppLog.w(TAG, "no access to music", expected)
                Result.failure()
            } catch (failure: CoreFailure) {
                // Отказ ядра уже ушёл в CoreErrors — сказать ли человеку, решит ErrorPresenter.
                AppLog.w(TAG, "core scan failed", failure)
                Result.failure()
            }

        companion object {
            private const val TAG = "Scan"
            const val KEY_WRITTEN = "written"
            const val KEY_TOTAL = "total"
            const val KEY_FOUND = "found"
        }
    }
