package io.github.puflik.plinth.queue

import io.github.puflik.plinth.ffi.PlaylistId

/**
 * Откуда взялся контекст очереди (D1.1): для подписи «играет из …» и для
 * сохранения очереди. Сами треки контекста лежат в [PlaybackQueue].
 */
sealed interface QueueContext {
    /** Все треки библиотеки в выбранном порядке. */
    data object Tracks : QueueContext

    data class Album(
        val title: String,
        val artist: String?,
    ) : QueueContext

    /** Треки одной папки; путь — как `LibraryFolder.path`. */
    data class Folder(
        val path: String,
    ) : QueueContext

    data class Search(
        val query: String,
    ) : QueueContext

    /** Треки исполнителя (E5): имя — тег трека, как в списке исполнителей. */
    data class Artist(
        val name: String,
    ) : QueueContext

    /** Свой плейлист (D4b); имя — каким оно было, когда заиграл. */
    data class Playlist(
        val id: PlaylistId,
        val name: String,
    ) : QueueContext

    /** «Любимое» (D4b): треки с лайком. */
    data object Liked : QueueContext

    /** «Недавнее» (D4b): треки по последнему прослушиванию. */
    data object Recent : QueueContext

    /** Один файл, открытый мимо библиотеки через SAF. */
    data object File : QueueContext
}
