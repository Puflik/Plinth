package io.github.puflik.plinth.queue

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

    /** Один файл, открытый мимо библиотеки через SAF. */
    data object File : QueueContext
}
