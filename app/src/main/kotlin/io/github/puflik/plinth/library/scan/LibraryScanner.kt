package io.github.puflik.plinth.library.scan

import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.ffi.CoreScanPhase
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.library.model.FolderConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * Итог скана.
 *
 * @property found треков в сканируемых папках.
 * @property updated из них прочитано заново: новые и изменённые.
 * @property missing отмечено пропавшими.
 */
data class ScanResult(
    val found: Int,
    val updated: Int,
    val missing: Int,
)

/**
 * Скан фонотеки ядром (D1–D3c): тома — [volumes], папки — из настроек. Ядро
 * само обходит файлы, читает теги и пишет каталог пачками; каждая пачка
 * двигает `PlinthCore.catalogChanges`, и списки растут по ходу скана.
 *
 * Инкрементальный (13.1): неизменённые файлы не перечитываются, пропавшие и
 * оказавшиеся вне сканируемых папок отмечаются недоступными.
 *
 * Без доступа к музыке ([canRead]) скан не начинается (`SecurityException`):
 * ядро увидело бы одни файлы приложения, и всё прочее ушло бы в пропавшие.
 * Отмена корутины останавливает ядро на ближайшем отчёте о ходе, записанное
 * остаётся — следующий скан допишет остальное.
 */
class LibraryScanner(
    private val core: PlinthCore,
    private val volumes: StorageVolumes,
    private val io: CoroutineDispatcher,
    private val canRead: () -> Boolean = { true },
) {
    /**
     * @param onProgress обработано новых и изменённых файлов и сколько их всего;
     *   пока файлы ищутся — оба ноль. Зовётся из потока ядра.
     */
    suspend fun scan(
        folders: FolderConfig,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult {
        if (!canRead()) throw SecurityException("no access to music")
        val job = currentCoroutineContext().job
        val report =
            withContext(io) {
                core.scan.scan(volumes.roots(), folders.included, folders.excluded) { progress ->
                    when (progress.phase) {
                        CoreScanPhase.WALKING -> onProgress(0, 0)
                        CoreScanPhase.READING -> onProgress(progress.done, progress.total)
                        // Запись быстрая: для человека файлы уже обработаны.
                        CoreScanPhase.WRITING -> onProgress(progress.total, progress.total)
                    }
                    job.isActive
                }
            }
        if (report.unreadableFolders > 0 || report.missingVolumes > 0) {
            AppLog.w(TAG, "folders unreadable: ${report.unreadableFolders}, volumes missing: ${report.missingVolumes}")
        }
        return ScanResult(found = report.found, updated = report.added + report.changed, missing = report.missing)
    }

    private companion object {
        const val TAG = "Scan"
    }
}
