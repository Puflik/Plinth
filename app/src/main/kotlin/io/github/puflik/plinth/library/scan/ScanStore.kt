package io.github.puflik.plinth.library.scan

import kotlin.time.Duration

/**
 * Трек, как его прочёл сканер v0.1 из строки `MediaStore` (C2.2).
 *
 * Отдельно от `LibraryTrack`: сканеру нужны `_ID` и время изменения файла,
 * экранам — нет. Уходит в D3c вместе со сканером.
 *
 * @property id идентификатор `MediaStore` (`_ID`): по нему сканер узнаёт файл
 *   при следующем обходе.
 * @property uri `content://` файла.
 * @property modifiedAt время изменения файла, секунды эпохи — как `DATE_MODIFIED`.
 */
data class ScannedTrack(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val duration: Duration,
    val folder: String,
    val modifiedAt: Long,
) {
    /** Чей это альбом — как `LibraryTrack.albumOwner`. */
    val albumOwner: String? get() = albumArtist ?: artist
}

/**
 * Куда пишет сканер v0.1 (C2.1): хранилище на Room. Экранам не виден —
 * они читают `LibraryRepository`. Уходит в D3c вместе со сканером.
 */
interface ScanStore {
    /** `id` → `modifiedAt` всех видимых треков: по ним сканер решает, что перечитать. */
    suspend fun knownVersions(): Map<Long, Long>

    /** Добавляет треки или заменяет их по `id`; пропавший трек снова становится видимым. */
    suspend fun upsert(tracks: Collection<ScannedTrack>)

    /** Скрывает треки, файлов которых больше нет. */
    suspend fun markMissing(ids: Collection<Long>)
}
