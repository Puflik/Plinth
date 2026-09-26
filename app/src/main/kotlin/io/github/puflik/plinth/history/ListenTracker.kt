package io.github.puflik.plinth.history

import io.github.puflik.plinth.audio.engine.PlaybackProgress
import io.github.puflik.plinth.queue.QueueItem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Одно прослушивание: что играло, когда зазвучало и сколько звучало.
 *
 * @property skippedAt где трек бросили; `null` — доиграл (или бросили в
 *   последние секунды, что то же самое).
 * @property length длительность трека, если движок её знал.
 */
data class Listen(
    val item: QueueItem,
    val startedAt: Instant,
    val listened: Duration,
    val skippedAt: Duration?,
    val length: Duration?,
)

/**
 * Сколько сыграл трек (D4a). Прослушивание начинается, когда текущий трек
 * зазвучал, и кончается с концом трека или сменой текущего. Считается время,
 * пока шёл звук, а не позиция: пауза не идёт в счёт, перемотка вперёд не
 * становится прослушиванием. Засчитать ли его в счётчик, решает ядро.
 *
 * Событие конца трека и смена текущего приходят из разных потоков в любом
 * порядке; поэтому смена в последние [NEAR_END] — тоже конец, а не пропуск.
 *
 * Не потокобезопасен: события подаёт по порядку один сборщик.
 */
class ListenTracker(
    private val now: () -> Instant,
) {
    private var place: Any? = null
    private var item: QueueItem? = null
    private var playing = false
    private var progress = PlaybackProgress.NONE
    private var open: Open? = null

    /**
     * Играет [item] на месте [place] (`PlaybackQueue.playing`). Новое место
     * закрывает прежнее прослушивание — его и возвращает.
     */
    fun current(
        place: Any?,
        item: QueueItem?,
    ): Listen? {
        if (place == this.place) return null
        val closed = close(skipped = true)
        this.place = place
        this.item = item
        progress = PlaybackProgress.NONE
        if (playing) begin()
        return closed
    }

    /** Звук пошёл или остановился. */
    fun playing(playing: Boolean) {
        if (playing == this.playing) return
        this.playing = playing
        val at = now()
        when {
            !playing -> open?.pause(at)
            open == null -> begin()
            else -> open?.resume(at)
        }
    }

    /**
     * Позиция и длительность. Позиция идёт, а прослушивания нет — трек
     * зазвучал снова, не останавливаясь (повтор одного трека).
     */
    fun progress(progress: PlaybackProgress) {
        this.progress = progress
        if (playing && open == null) begin()
    }

    /** Трек доиграл до конца. */
    fun ended(): Listen? = close(skipped = false)

    private fun begin() {
        if (item != null) open = Open(now())
    }

    private fun close(skipped: Boolean): Listen? {
        val session = open
        open = null
        val item = item
        val listened = session?.listened(now()) ?: Duration.ZERO
        if (session == null || item == null || listened < MIN_LISTEN) return null
        val length = progress.duration ?: item.duration
        val atEnd = length != null && progress.position >= length - NEAR_END
        return Listen(item, session.startedAt, listened, progress.position.takeIf { skipped && !atEnd }, length)
    }

    /** Открытое прослушивание: сколько набралось и с какого мгновения идёт звук. */
    private class Open(
        val startedAt: Instant,
    ) {
        private var total = Duration.ZERO
        private var since: Instant? = startedAt

        fun pause(at: Instant) {
            since?.let { total += at - it }
            since = null
        }

        fun resume(at: Instant) {
            if (since == null) since = at
        }

        fun listened(at: Instant): Duration = total + (since?.let { at - it } ?: Duration.ZERO)
    }

    private companion object {
        /** Бросить трек в последние секунды — то же, что доиграть. */
        val NEAR_END = 2.seconds

        /** Меньше — не прослушивание, а щелчок по списку. */
        val MIN_LISTEN = 1.seconds
    }
}
