package io.github.puflik.plinth.ui.player

import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackError
import io.github.puflik.plinth.audio.engine.PlaybackProgress
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.queue.RepeatMode
import kotlin.time.Duration

/**
 * Что показывает экран плеера: трек, звук и очередь.
 *
 * @property canControl есть открытый трек, которым можно управлять.
 * @property upcoming что сыграет дальше без повтора.
 * @property artworkUri файл, чья встроенная обложка показывается; `null` —
 *   показывать нечего (ничего не играет или играет поток).
 */
data class PlayerUiState(
    val title: String?,
    val artist: String?,
    val isPlaying: Boolean,
    val canControl: Boolean,
    val position: Duration,
    val duration: Duration?,
    val error: PlaybackError?,
    val upcoming: List<QueueItem>,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val artworkUri: String?,
) {
    companion object {
        val EMPTY = from(PlaybackState.Idle, PlaybackProgress.NONE, PlaybackQueue.EMPTY)

        fun from(
            state: PlaybackState,
            progress: PlaybackProgress,
            queue: PlaybackQueue,
        ) = PlayerUiState(
            title = queue.current?.title,
            artist = queue.current?.artist,
            isPlaying = state == PlaybackState.Playing,
            canControl = state != PlaybackState.Idle && state !is PlaybackState.Error,
            position = progress.position,
            duration = progress.duration,
            error = (state as? PlaybackState.Error)?.error,
            upcoming = queue.upcoming,
            shuffle = queue.shuffle,
            repeat = queue.repeat,
            artworkUri = (queue.current?.source as? AudioSource.LocalFile)?.uri,
        )
    }
}
