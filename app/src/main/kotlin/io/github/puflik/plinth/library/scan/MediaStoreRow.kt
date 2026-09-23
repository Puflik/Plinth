package io.github.puflik.plinth.library.scan

/**
 * Строка `MediaStore.Audio.Media` как есть, до разбора тегов (C2.1).
 *
 * Чистые данные без типов Android: из них [TagReader] собирает трек, а
 * тесты сканера обходятся без `MediaStore`.
 *
 * @property uri `content://` файла.
 * @property track колонка `TRACK`: номер диска × 1000 + номер трека.
 * @property durationMs `DURATION`, миллисекунды.
 * @property folder папка от корня хранилища: `RELATIVE_PATH` или папка из `DATA`.
 * @property dateModified `DATE_MODIFIED`, секунды эпохи.
 */
data class MediaStoreRow(
    val id: Long,
    val uri: String,
    val displayName: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val track: Int?,
    val durationMs: Long?,
    val folder: String,
    val dateModified: Long,
)
