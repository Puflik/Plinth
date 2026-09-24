package io.github.puflik.plinth.queue

import kotlin.time.Duration

/** Очередь и место в текущем треке, как их сохранили (D2.1). */
data class SavedQueue(
    val queue: PlaybackQueue,
    val position: Duration,
)

/**
 * Где очередь переживает перезапуск (D1.5, D2.1). Очередь и позиция пишутся
 * порознь: очередь меняется при смене трека, позиция — каждую секунду.
 */
interface QueueStore {
    /** Сохранённое; `null` — нечего восстанавливать или сохранённое не читается. */
    suspend fun load(): SavedQueue?

    /** Новая очередь; позиция сбрасывается к началу трека. */
    suspend fun saveQueue(queue: PlaybackQueue)

    suspend fun savePosition(position: Duration)
}
