package io.github.puflik.plinth.queue

import io.github.puflik.plinth.audio.engine.AudioSource

/** Пути файлов по адресам MediaStore вида content://media/<том>/audio/media/<число>. */
fun interface MediaPaths {
    /**
     * Для каждого из [uris], чей файл нашёлся, — его путь; остальных в ответе нет. Не бросает
     * ничего, кроме отмены корутины: нет доступа к MediaStore — пустой ответ.
     */
    suspend fun pathsOf(uris: Set<String>): Map<String, String>
}

/**
 * [QueueStore], который при чтении заменяет адреса MediaStore из очереди 0.1.x путями файлов
 * (ревью v0.2, №26).
 *
 * 0.1.x называла треки очереди адресами `content://media/external/audio/media/<id>`; ядро 0.2
 * узнаёт файл по пути, и без него у трека нет ни истории, ни сердца. Играют такие треки и так.
 * Файл на диске не переписывается — первая же запись очереди сохранит уже пути; адрес, которого
 * система не знает (базу переиндексировали), остаётся как был. Записи идут прямо в [store].
 */
class LegacyUriQueueStore(
    private val store: QueueStore,
    private val paths: MediaPaths,
) : QueueStore by store {
    override suspend fun load(): SavedQueue? =
        store.load()?.let { saved ->
            val queue = translated(saved.queue)
            if (queue === saved.queue) saved else saved.copy(queue = queue)
        }

    /** [queue] с путями вместо адресов MediaStore; система спрашивается один раз и только если есть о чём. */
    private suspend fun translated(queue: PlaybackQueue): PlaybackQueue {
        val wanted = (queue.contextItems + listOfNotNull(queue.playingManual) + queue.upNext).legacyUris()
        val found = if (wanted.isEmpty()) emptyMap() else pathsOf(wanted)
        return if (found.isEmpty()) {
            queue
        } else {
            queue.copy(
                contextItems = queue.contextItems.map { it.withPath(found) },
                playingManual = queue.playingManual?.withPath(found),
                upNext = queue.upNext.map { it.withPath(found) },
            )
        }
    }

    /** Ответ системы без чужих адресов и пустых путей: путь из пробелов — не путь, адрес остаётся как был. */
    private suspend fun pathsOf(wanted: Set<String>): Map<String, String> =
        paths.pathsOf(wanted).filter { (uri, path) -> uri in wanted && path.isNotBlank() }

    private fun List<QueueItem>.legacyUris(): Set<String> =
        mapNotNull { item ->
            (item.source as? AudioSource.LocalFile)?.uri?.takeIf { it.startsWith(MEDIA_PREFIX) }
        }.toSet()

    private fun QueueItem.withPath(found: Map<String, String>): QueueItem {
        val path = (source as? AudioSource.LocalFile)?.let { found[it.uri] }
        return if (path == null) this else copy(source = AudioSource.LocalFile(path))
    }

    private companion object {
        /** Начало адреса, который 0.1.x брала у `MediaStore`. */
        const val MEDIA_PREFIX = "content://media/"
    }
}
