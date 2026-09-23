package io.github.puflik.plinth.library.model

import kotlin.time.Duration

/**
 * Трек фонотеки v0.1 (C3) — то, что сканер узнал о файле из `MediaStore`.
 *
 * Упрощённая модель: в v0.2 её сменит `Track/Version/Source` ядра на Rust.
 * Типов Android здесь нет — адрес файла хранится строкой, как в `AudioSource`.
 *
 * @property id идентификатор `MediaStore` (`_ID`): по нему сканер узнаёт файл
 *   при следующем обходе.
 * @property uri `content://` файла — из него собирается `AudioSource.LocalFile`.
 * @property title название; без тега — имя файла, пустым не бывает.
 * @property discNumber номер диска с единицы; `null` — тега нет.
 * @property trackNumber номер трека на диске с единицы; `null` — тега нет.
 * @property folder папка от корня хранилища: `Music/Queen/`.
 * @property modifiedAt время изменения файла, секунды эпохи — как `DATE_MODIFIED`.
 */
data class LibraryTrack(
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
