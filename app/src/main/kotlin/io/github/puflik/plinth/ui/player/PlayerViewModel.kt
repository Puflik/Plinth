package io.github.puflik.plinth.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.audio.PlaybackController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.time.Duration

/**
 * Экран плеера: то, что открыли библиотека или SAF, — звук, название,
 * перемотка. Сам плеер ничего не открывает: название и трек знает
 * [PlaybackController].
 */
@HiltViewModel
class PlayerViewModel
    @Inject
    constructor(
        private val playback: PlaybackController,
    ) : ViewModel() {
        val uiState: StateFlow<PlayerUiState> =
            combine(playback.state, playback.progress, playback.title, PlayerUiState::from)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlayerUiState.EMPTY)

        fun onPlayPause() = playback.togglePlayPause()

        fun onSeek(position: Duration) = playback.seekTo(position)

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
