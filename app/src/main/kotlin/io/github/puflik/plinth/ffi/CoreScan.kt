package io.github.puflik.plinth.ffi

import java.io.File
import io.github.puflik.plinth.ffi.generated.FolderConfig as RustFolderConfig
import io.github.puflik.plinth.ffi.generated.ScanListener as RustScanListener
import io.github.puflik.plinth.ffi.generated.ScanPhase as RustScanPhase
import io.github.puflik.plinth.ffi.generated.ScanProgress as RustScanProgress
import io.github.puflik.plinth.ffi.generated.ScanReport as RustScanReport

/**
 * Скан библиотеки ядром (D1) — `api/scan_api.rs`. Ядро само обходит тома по
 * прямым путям, а не через `MediaStore`, и видит файлы в любых папках.
 * Теги пока не читаются — название из имени файла (D2). `ScanWorker`
 * переключится на этот скан в D3.
 */
class CoreScan internal constructor(
    private val core: PlinthCore,
) {
    /**
     * Сканирует [volumes] — корни томов — по папкам [included] без [excluded]:
     * пути от корня тома, как в `FolderConfig` (`Music/`, `Music/Podcasts/`).
     * Блокирует до конца; [onProgress] вернул `false` — скан останавливается,
     * записанное к этому моменту остаётся.
     */
    fun scan(
        volumes: List<File>,
        included: List<String>,
        excluded: List<String> = emptyList(),
        onProgress: (CoreScanProgress) -> Boolean = { true },
    ): CoreScanReport =
        core.call {
            it.scan(volumes.map(File::getPath), RustFolderConfig(included, excluded), Listener(onProgress)).toApp()
        }

    private class Listener(
        private val onProgress: (CoreScanProgress) -> Boolean,
    ) : RustScanListener {
        override fun progress(progress: RustScanProgress): Boolean = onProgress(progress.toApp())
    }
}

/** Этап скана: обход не знает, сколько файлов впереди; чтение и запись — знают. */
enum class CoreScanPhase {
    WALKING,
    READING,
    WRITING,
}

/** Ход скана: [total] у обхода — 0, итог ещё неизвестен. */
data class CoreScanProgress(
    val phase: CoreScanPhase,
    val done: Int,
    val total: Int,
)

/**
 * Итог скана. Пропавший файл не удаляется, а становится недоступным
 * ([missing]); вернувшийся — снова доступен ([returned]).
 */
data class CoreScanReport(
    val found: Int,
    val added: Int,
    val changed: Int,
    val returned: Int,
    val missing: Int,
    val unreadableFiles: Int,
    val unreadableFolders: Int,
    val missingVolumes: Int,
    val stopped: Boolean,
)

private fun RustScanProgress.toApp() =
    CoreScanProgress(
        phase =
            when (phase) {
                RustScanPhase.WALKING -> CoreScanPhase.WALKING
                RustScanPhase.READING -> CoreScanPhase.READING
                RustScanPhase.WRITING -> CoreScanPhase.WRITING
            },
        done = done.toInt(),
        total = total.toInt(),
    )

private fun RustScanReport.toApp() =
    CoreScanReport(
        found = found.toInt(),
        added = added.toInt(),
        changed = changed.toInt(),
        returned = returned.toInt(),
        missing = missing.toInt(),
        unreadableFiles = unreadableFiles.toInt(),
        unreadableFolders = unreadableFolders.toInt(),
        missingVolumes = missingVolumes.toInt(),
        stopped = stopped,
    )
