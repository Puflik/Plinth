package io.github.puflik.plinth.audio

import io.github.puflik.plinth.audio.engine.PlaybackError
import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.core.FailedTrack
import io.github.puflik.plinth.core.TrackProblem
import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueItem

/**
 * Треки, что не сыграли подряд после последнего сыгравшего (G3), — и что
 * делать с очередным: пропустить или остановиться.
 *
 * Решение автора: недоступный, битый и чужой формат очередь пропускает, на
 * сети и неизвестной причине останавливается. Останавливается и когда играть
 * дальше нечего или следующий трек в этой серии уже не сыграл — иначе очередь
 * с повтором ходила бы по кругу. Пропуск — как «дальше» человека: повтор
 * одного трека битый трек не держит. Сам трек остаётся в очереди — вернётся
 * файл, сыграет.
 */
internal class FailedRun {
    private val tracks = mutableListOf<FailedTrack>()

    /** Что дальше после трека, который не сыграл. */
    sealed interface Step {
        /** Играть [item] — текущий трек очереди [queue]. */
        data class Skip(
            val queue: PlaybackQueue,
            val item: QueueItem,
        ) : Step

        data class Stop(
            val error: AppError.PlaybackStopped,
        ) : Step
    }

    /**
     * Текущий трек [queue] не сыграл из-за [error]; `null` — очередь пуста.
     * [whileRestoring] — трек готовили на паузе, возвращая сохранённую очередь.
     */
    fun failed(
        queue: PlaybackQueue,
        error: PlaybackError,
        whileRestoring: Boolean,
    ): Step? =
        queue.current?.let { item ->
            step(queue, FailedTrack(item.title, item.source.key, error.problem), whileRestoring)
        }

    /** Трек заиграл: серия кончилась; что в ней пропустили, если было что. */
    fun played(whileRestoring: Boolean): AppError.TracksSkipped? {
        if (tracks.isEmpty()) return null
        val skipped = AppError.TracksSkipped(tracks.toList(), whileRestoring)
        tracks.clear()
        return skipped
    }

    private fun step(
        queue: PlaybackQueue,
        failed: FailedTrack,
        whileRestoring: Boolean,
    ): Step {
        tracks += failed
        val next = if (failed.problem.skipsTrack) queue.next() else null
        val nextItem = next?.current
        return if (next != null && nextItem != null && tracks.none { it.file == nextItem.source.key }) {
            AppLog.w(TAG, "skipped ${failed.problem}")
            Step.Skip(next, nextItem)
        } else {
            AppLog.w(TAG, "stopped on ${failed.problem}, ${tracks.size} failed in a row")
            val stopped = AppError.PlaybackStopped(failed, tracks.dropLast(1), whileRestoring)
            tracks.clear()
            Step.Stop(stopped)
        }
    }

    private companion object {
        const val TAG = "Playback"
    }
}

/** Ошибка движка → что это для человека. */
private val PlaybackError.problem: TrackProblem
    get() =
        when (this) {
            is PlaybackError.SourceUnavailable -> TrackProblem.UNAVAILABLE
            is PlaybackError.Malformed, is PlaybackError.UnsupportedFormat -> TrackProblem.UNPLAYABLE
            is PlaybackError.Network -> TrackProblem.NETWORK
            is PlaybackError.Unknown -> TrackProblem.UNKNOWN
        }

private val TrackProblem.skipsTrack: Boolean
    get() = this == TrackProblem.UNAVAILABLE || this == TrackProblem.UNPLAYABLE
