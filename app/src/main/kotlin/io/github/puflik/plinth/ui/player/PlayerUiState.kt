package io.github.puflik.plinth.ui.player

import io.github.puflik.plinth.audio.engine.PlaybackError
import io.github.puflik.plinth.audio.engine.PlaybackProgress
import io.github.puflik.plinth.audio.engine.PlaybackState
import kotlin.time.Duration

/**
 * Что показывает экран плеера первой вертикали.
 *
 * @property canControl есть открытый трек, которым можно управлять.
 */
data class PlayerUiState(
    val title: String?,
    val isPlaying: Boolean,
    val canControl: Boolean,
    val position: Duration,
    val duration: Duration?,
    val error: PlaybackError?,
) {
    companion object {
        val EMPTY = from(PlaybackState.Idle, PlaybackProgress.NONE, title = null)

        fun from(
            state: PlaybackState,
            progress: PlaybackProgress,
            title: String?,
        ) = PlayerUiState(
            title = title,
            isPlaying = state == PlaybackState.Playing,
            canControl = state != PlaybackState.Idle && state !is PlaybackState.Error,
            position = progress.position,
            duration = progress.duration,
            error = (state as? PlaybackState.Error)?.error,
        )
    }
}
