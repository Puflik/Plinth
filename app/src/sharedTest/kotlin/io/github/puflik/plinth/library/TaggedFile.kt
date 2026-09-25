package io.github.puflik.plinth.library

import kotlin.time.Duration

/**
 * Файл с тегами — то, что скан узнал бы о файле (D3b). Контракт фонотеки
 * наполняет хранилище ими: методов записи у фасада нет, пишет скан.
 *
 * @property path путь к файлу: по нему трек играет и по нему файл пропадает.
 * @property title `null` или пусто — тега нет, название из имени файла.
 * @property artist исполнитель строкой, как в теге: «Queen & David Bowie».
 * @property folder папка файла от корня тома.
 */
data class TaggedFile(
    val path: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val duration: Duration? = null,
    val folder: String = "Music/",
)
