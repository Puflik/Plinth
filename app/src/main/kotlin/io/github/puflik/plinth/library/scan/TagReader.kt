package io.github.puflik.plinth.library.scan

import io.github.puflik.plinth.library.model.LibraryTrack
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Теги трека из строки `MediaStore` (C2.2).
 *
 * Теги уже прочитал системный сканер (решение C2 — `MediaStore`, без своей
 * библиотеки тегов); здесь — только его соглашения:
 * - `<unknown>` и пустая строка — тега нет;
 * - `TRACK` = номер диска × 1000 + номер трека, ноль в любой части — нет номера;
 * - без названия трек называется по имени файла без расширения.
 */
object TagReader {
    /** `MediaStore.UNKNOWN_STRING`: так системный сканер пишет отсутствующий тег. */
    private const val UNKNOWN = "<unknown>"
    private const val DISC_FACTOR = 1000

    fun read(row: MediaStoreRow): LibraryTrack {
        val track = row.track?.takeIf { it > 0 }
        return LibraryTrack(
            id = row.id,
            uri = row.uri,
            title = tag(row.title) ?: fileTitle(row.displayName) ?: row.id.toString(),
            artist = tag(row.artist),
            album = tag(row.album),
            albumArtist = tag(row.albumArtist),
            discNumber = track?.div(DISC_FACTOR)?.takeIf { it > 0 },
            trackNumber = track?.rem(DISC_FACTOR)?.takeIf { it > 0 },
            duration = row.durationMs?.takeIf { it > 0 }?.milliseconds ?: Duration.ZERO,
            folder = row.folder,
            modifiedAt = row.dateModified,
        )
    }

    private fun tag(value: String?): String? = value?.trim()?.takeUnless { it.isEmpty() || it == UNKNOWN }

    /** Имя файла без расширения; имя из одного расширения (`.mp3`) остаётся целым. */
    private fun fileTitle(displayName: String?): String? {
        val name = displayName?.trim()?.takeUnless(String::isEmpty) ?: return null
        return name.substringBeforeLast('.').ifBlank { name }
    }
}
