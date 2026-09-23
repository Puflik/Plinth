package io.github.puflik.plinth.library.scan

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Audio.AudioColumns
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Музыка из `MediaStore` (C2.1) — всё, что нашёл и разобрал системный сканер.
 *
 * Решение C2: теги v0.1 берутся у системы, своей библиотеки тегов нет.
 * Плата — видно только то, что система проиндексировала. Какие папки из
 * этого брать, решает `LibraryScanner` по `FolderConfig`: здесь — все
 * строки с `IS_MUSIC` (без рингтонов, уведомлений, будильников, подкастов).
 *
 * Без разрешения на чтение `MediaStore` отдаёт только файлы самого приложения.
 */
class MediaStoreSource(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher,
) : ScanSource {
    override suspend fun rows(): List<MediaStoreRow> =
        withContext(io) {
            resolver.query(COLLECTION, PROJECTION, "${AudioColumns.IS_MUSIC} != 0", null, null)?.use(::read).orEmpty()
        }

    private fun read(cursor: Cursor): List<MediaStoreRow> {
        val id = cursor.getColumnIndexOrThrow(AudioColumns._ID)
        val displayName = cursor.getColumnIndexOrThrow(AudioColumns.DISPLAY_NAME)
        val title = cursor.getColumnIndexOrThrow(AudioColumns.TITLE)
        val artist = cursor.getColumnIndexOrThrow(AudioColumns.ARTIST)
        val album = cursor.getColumnIndexOrThrow(AudioColumns.ALBUM)
        val albumArtist = cursor.getColumnIndexOrThrow(ALBUM_ARTIST)
        val track = cursor.getColumnIndexOrThrow(AudioColumns.TRACK)
        val duration = cursor.getColumnIndexOrThrow(AudioColumns.DURATION)
        val dateModified = cursor.getColumnIndexOrThrow(AudioColumns.DATE_MODIFIED)
        val location = cursor.getColumnIndexOrThrow(LOCATION)
        return buildList(cursor.count) {
            while (cursor.moveToNext()) {
                val rowId = cursor.getLong(id)
                add(
                    MediaStoreRow(
                        id = rowId,
                        uri = ContentUris.withAppendedId(COLLECTION, rowId).toString(),
                        displayName = cursor.getStringOrNull(displayName),
                        title = cursor.getStringOrNull(title),
                        artist = cursor.getStringOrNull(artist),
                        album = cursor.getStringOrNull(album),
                        albumArtist = cursor.getStringOrNull(albumArtist),
                        track = cursor.getIntOrNull(track),
                        durationMs = cursor.getLongOrNull(duration),
                        folder = folderOf(cursor.getStringOrNull(location)),
                        dateModified = cursor.getLongOrNull(dateModified) ?: 0,
                    ),
                )
            }
        }
    }

    private companion object {
        /** Все внешние тома сразу: основное хранилище и SD-карты. */
        val COLLECTION = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        /**
         * `AudioColumns.ALBUM_ARTIST` открыт только с API 30, но колонка с
         * этим именем есть в `MediaStore` и раньше.
         */
        const val ALBUM_ARTIST = "album_artist"

        /** Папка: `RELATIVE_PATH` с Android 10, до него — только полный путь `DATA`. */
        val HAS_RELATIVE_PATH = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        @Suppress("DEPRECATION")
        val LOCATION: String = if (HAS_RELATIVE_PATH) AudioColumns.RELATIVE_PATH else AudioColumns.DATA

        val PROJECTION =
            arrayOf(
                AudioColumns._ID,
                AudioColumns.DISPLAY_NAME,
                AudioColumns.TITLE,
                AudioColumns.ARTIST,
                AudioColumns.ALBUM,
                ALBUM_ARTIST,
                AudioColumns.TRACK,
                AudioColumns.DURATION,
                AudioColumns.DATE_MODIFIED,
                LOCATION,
            )

        fun folderOf(location: String?): String =
            when {
                location == null -> ""
                HAS_RELATIVE_PATH -> location
                else -> FolderConfig.folderOf(location).orEmpty()
            }
    }
}
