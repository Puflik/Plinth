package io.github.puflik.plinth.library.model

import io.github.puflik.plinth.ffi.TrackId
import kotlin.time.Duration

/**
 * Трек фонотеки (C3, D3b) — строка списка, какой её отдаёт хранилище.
 *
 * Типов Android здесь нет — адрес файла хранится строкой, как в `AudioSource`.
 *
 * @property id трек ядра — тот, на который ссылаются лайки, плейлисты и
 *   история. Пока фонотеку читает Room (до D3c) — `_ID` из `MediaStore` строкой.
 * @property uri путь к файлу — из него собирается `AudioSource.LocalFile`.
 *   Пока фонотеку читает Room — `content://` файла.
 * @property title название; без тега — имя файла, пустым не бывает.
 * @property albumArtist исполнитель альбома; `null` — трек не на альбоме или
 *   у альбома нет исполнителя.
 * @property discNumber номер диска с единицы; `null` — тега нет.
 * @property trackNumber номер трека на диске с единицы; `null` — тега нет.
 * @property duration длительность; `null` — неизвестна.
 * @property folder папка от корня хранилища: `Music/Queen/`.
 */
data class LibraryTrack(
    val id: TrackId,
    val uri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val duration: Duration?,
    val folder: String,
) {
    init {
        require(uri.isNotBlank()) { "uri трека не может быть пустым" }
        require(title.isNotBlank()) { "у трека должно быть название" }
        require(discNumber == null || discNumber > 0) { "номер диска начинается с 1: $discNumber" }
        require(trackNumber == null || trackNumber > 0) { "номер трека начинается с 1: $trackNumber" }
    }

    /**
     * Чей это альбом: исполнитель альбома, а без него — исполнитель трека.
     * По нему альбомы группируются: сборник с десятью исполнителями остаётся
     * одним альбомом, а не рассыпается на десять.
     */
    val albumOwner: String? get() = albumArtist ?: artist
}
