package io.github.puflik.plinth.audio.engine

import kotlin.time.Duration

/**
 * Где мы в треке (B1.2, добавлено на шаге 4 вертикали — для полосы перемотки).
 *
 * В отличие от [PlaybackEvent.PositionChanged], это снимок: экран, открытый
 * заново на паузе, сразу видит позицию и длительность, а не ждёт события,
 * которое на паузе не придёт.
 *
 * @property duration длительность трека; `null`, пока движок её не знает
 *   (источник ещё готовится или это живой поток).
 */
data class PlaybackProgress(
    val position: Duration,
    val duration: Duration?,
) {
    companion object {
        /** Источника нет: движок новый, освобождён или источник упал. */
        val NONE = PlaybackProgress(Duration.ZERO, null)
    }
}
