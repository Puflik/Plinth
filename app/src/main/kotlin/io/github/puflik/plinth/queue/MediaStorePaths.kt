package io.github.puflik.plinth.queue

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import io.github.puflik.plinth.diagnostics.log.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [MediaPaths] через `MediaStore` (ревью v0.2, №26): путь файла по адресу
 * `content://media/<том>/audio/media/<число>`, как его назвала 0.1.x.
 *
 * Один запрос ко всей аудиоколлекции на всю очередь, а не по запросу на каждый адрес:
 * очередь из тысяч треков держала бы восстановление секунды. Без доступа к `MediaStore` —
 * пустой ответ, адреса остаются как были: играют они и так.
 */
class MediaStorePaths(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher,
) : MediaPaths {
    override suspend fun pathsOf(uris: Set<String>): Map<String, String> {
        val wanted =
            uris
                .mapNotNull { uri -> idOf(uri)?.let { it to uri } }
                .groupBy({ it.first }, { it.second })
        return if (wanted.isEmpty()) emptyMap() else withContext(io) { query(wanted) }
    }

    /** Номер строки `MediaStore` из адреса; том (`external`, `external_primary`…) любой. */
    private fun idOf(uri: String): Long? = ADDRESS.matchEntire(uri)?.let { it.groupValues[1].toLongOrNull() }

    private fun query(wanted: Map<Long, List<String>>): Map<String, String> =
        try {
            resolver.query(COLLECTION, PROJECTION, null, null, null)?.use { read(it, wanted) }.orEmpty()
        } catch (e: SecurityException) {
            unreadable(e)
        } catch (e: IllegalArgumentException) {
            unreadable(e)
        } catch (e: UnsupportedOperationException) {
            unreadable(e)
        }

    /** Строки курсора, чьи номера нужны; обход кончается, когда нашлись все. */
    private fun read(
        cursor: Cursor,
        wanted: Map<Long, List<String>>,
    ): Map<String, String> {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val dataColumn = cursor.getColumnIndexOrThrow(DATA)
        val found = HashMap<String, String>()
        val left = wanted.keys.toMutableSet()
        while (left.isNotEmpty() && cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val uris = wanted[id] ?: continue
            left.remove(id)
            val path: String? = cursor.getString(dataColumn)
            if (!path.isNullOrBlank()) uris.forEach { found[it] = path }
        }
        return found
    }

    private fun unreadable(e: Exception): Map<String, String> {
        AppLog.w(TAG, "MediaStore is not readable, queue addresses stay as they were", e)
        return emptyMap()
    }

    private companion object {
        const val TAG = "MediaStorePaths"

        /** Все внешние тома сразу, как в `MediaStoreSource` 0.1.1. */
        val COLLECTION: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        /**
         * `DATA` объявлена устаревшей с API 29, но другого пути у файла нет: по нему играет и
         * сканирует приложение (так её читала и `MediaStoreSource` 0.1.1).
         */
        @Suppress("DEPRECATION")
        const val DATA = MediaStore.Audio.Media.DATA

        val PROJECTION = arrayOf(MediaStore.Audio.Media._ID, DATA)

        val ADDRESS = Regex("""content://media/[^/]+/audio/media/(\d+)""")
    }
}
