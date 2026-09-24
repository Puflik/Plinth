package io.github.puflik.plinth.ui.player

import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackError
import io.github.puflik.plinth.audio.engine.PlaybackProgress
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.library.model.Album
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
 * @property album альбом текущего трека — для «к альбому»; число треков
 *   экран альбома считает сам, здесь оно `0`.
 * @property hasTrack в очереди есть текущий трек — играет он или стоит на
 *   паузе; по нему показывается мини-плеер.
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
    val hasTrack: Boolean,
    val album: Album?,
) {
    /** Какая доля трека сыграна, `0..1`; без длительности — `0`. */
    val progressFraction: Float
        get() = duration?.takeIf { it.isPositive() }?.let { (position / it).toFloat().coerceIn(0f, 1f) } ?: 0f

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
            hasTrack = queue.current != null,
            album = queue.current?.let { item -> item.album?.let { Album(it, item.albumOwner, trackCount = 0) } },
        )
    }
}
