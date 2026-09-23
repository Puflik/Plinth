package io.github.puflik.plinth.library.scan

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.puflik.plinth.library.LibraryScan
import io.github.puflik.plinth.library.ScanProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Скан как уникальная работа WorkManager (C2.4): второй запуск, пока идёт
 * первый, ничего не добавляет, а состояние видно из любого места приложения.
 */
class WorkManagerLibraryScan(
    private val workManager: WorkManager,
) : LibraryScan {
    override val progress: Flow<ScanProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { it.lastOrNull().toScanProgress() }

    override fun start() {
        val request = OneTimeWorkRequestBuilder<ScanWorker>().build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "library-scan"

        fun WorkInfo?.toScanProgress(): ScanProgress =
            when (this?.state) {
                null, WorkInfo.State.CANCELLED -> ScanProgress.Idle
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ScanProgress.Running(written = 0, total = 0)
                WorkInfo.State.RUNNING ->
                    ScanProgress.Running(
                        written = progress.getInt(ScanWorker.KEY_WRITTEN, 0),
                        total = progress.getInt(ScanWorker.KEY_TOTAL, 0),
                    )
                WorkInfo.State.SUCCEEDED -> ScanProgress.Done(found = outputData.getInt(ScanWorker.KEY_FOUND, 0))
                WorkInfo.State.FAILED -> ScanProgress.Failed
            }
    }
}
