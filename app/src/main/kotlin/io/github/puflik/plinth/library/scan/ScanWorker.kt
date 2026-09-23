package io.github.puflik.plinth.library.scan

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Скан фонотеки в фоне (C2.4): переживает уход с экрана, прогресс — через
 * `setProgress`, итог — через выходные данные работы.
 *
 * Отмена — штатная для `CoroutineWorker`: WorkManager отменяет корутину, и
 * сканер останавливается на ближайшей порции, сохранив записанное.
 */
@HiltWorker
class ScanWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val scanner: LibraryScanner,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            try {
                val result =
                    scanner.scan(FolderConfig.DEFAULT) { written, total ->
                        setProgress(workDataOf(KEY_WRITTEN to written, KEY_TOTAL to total))
                    }
                Result.success(workDataOf(KEY_FOUND to result.found))
            } catch (expected: SecurityException) {
                // Разрешение отозвали посреди скана: повторять бессмысленно до новой выдачи.
                Result.failure()
            }

        companion object {
            const val KEY_WRITTEN = "written"
            const val KEY_TOTAL = "total"
            const val KEY_FOUND = "found"
        }
    }
