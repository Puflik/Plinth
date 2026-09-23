package io.github.puflik.plinth.library.scan

import io.github.puflik.plinth.library.LibraryRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Откуда сканер берёт файлы (C2.1): в приложении — `MediaStoreSource`. */
fun interface ScanSource {
    suspend fun rows(): List<MediaStoreRow>
}

/**
 * Итог скана.
 *
 * @property found треков в сканируемых папках.
 * @property updated из них прочитано заново: новые и изменённые.
 * @property missing помечено пропавшими.
 */
data class ScanResult(
    val found: Int,
    val updated: Int,
    val missing: Int,
)

/**
 * Сканер фонотеки (C2.1): сверяет файлы из [source] с хранилищем и пишет
 * разницу через фасад [repository] — как любой другой клиент библиотеки.
 *
 * Инкрементальный (13.1): неизменённые файлы не перечитываются, пропавшие и
 * оказавшиеся вне сканируемых папок помечаются пропавшими.
 *
 * Треки пишутся порциями по [WRITE_CHUNK]: после каждой — отчёт о прогрессе
 * и точка отмены. Отменённый скан оставляет записанное, следующий допишет
 * остальное: записанные порции он уже увидит неизменёнными.
 */
class LibraryScanner(
    private val source: ScanSource,
    private val repository: LibraryRepository,
) {
    /** @param onProgress записано треков и сколько всего записать; первый отчёт — `0` сразу после сверки. */
    suspend fun scan(
        folders: FolderConfig,
        onProgress: suspend (written: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult {
        val rows = source.rows().filter { folders.includes(it.folder) }
        val diff = ScanDiff.of(repository.knownVersions(), rows.associate { it.id to it.dateModified })
        val changed = rows.filter { it.id in diff.changed }
        onProgress(0, changed.size)
        var written = 0
        for (chunk in changed.chunked(WRITE_CHUNK)) {
            // Хранилище может писать, не приостанавливаясь, — отмену проверяем сами.
            currentCoroutineContext().ensureActive()
            repository.upsert(chunk.map(TagReader::read))
            written += chunk.size
            onProgress(written, changed.size)
        }
        repository.markMissing(diff.missing)
        return ScanResult(found = rows.size, updated = changed.size, missing = diff.missing.size)
    }

    companion object {
        const val WRITE_CHUNK = 500
    }
}
