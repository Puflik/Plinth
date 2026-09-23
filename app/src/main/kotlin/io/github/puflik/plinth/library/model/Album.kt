package io.github.puflik.plinth.library.model

/**
 * Альбом — треки с одним названием альбома и одним
 * [владельцем][LibraryTrack.albumOwner]. Своей записи у альбома нет, он
 * собирается из треков; треки без тега альбома альбомов не образуют.
 *
 * @property artist владелец; `null` — ни у одного трека нет исполнителя.
 */
data class Album(
    val title: String,
    val artist: String?,
    val trackCount: Int,
)
