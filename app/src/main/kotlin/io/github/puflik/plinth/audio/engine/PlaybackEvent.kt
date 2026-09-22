package io.github.puflik.plinth.audio.engine

import kotlin.time.Duration

/**
 * Происшествия во время воспроизведения (B1.2).
 *
 * В отличие от [PlaybackState], событие не хранится: подписчик получает
 * только то, что случилось после подписки. Так устроено намеренно — иначе
 * заново открытый экран показал бы ошибку прошлого трека.
 */
sealed interface PlaybackEvent {
    /** Позиция изменилась: ход воспроизведения, перемотка, старт с позиции. */
    data class PositionChanged(
        val position: Duration,
    ) : PlaybackEvent

    /** Движок начал или закончил буферизацию. */
    data class BufferingChanged(
        val buffering: Boolean,
    ) : PlaybackEvent

    /** Трек доигран до конца — очередь решает, что делать дальше. */
    data object TrackEnded : PlaybackEvent

    /** Воспроизведение прервано ошибкой. */
    data class Failed(
        val error: PlaybackError,
    ) : PlaybackEvent
}
