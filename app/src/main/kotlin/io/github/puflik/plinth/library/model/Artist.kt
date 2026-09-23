package io.github.puflik.plinth.library.model

/**
 * Исполнитель — значение тега `artist` у треков. Своей записи нет, как и у
 * [Album]; треки без исполнителя в список не попадают.
 *
 * @property albumCount сколько разных альбомов среди его треков, сборники тоже.
 * @property trackCount сколько у него треков.
 */
data class Artist(
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
)
