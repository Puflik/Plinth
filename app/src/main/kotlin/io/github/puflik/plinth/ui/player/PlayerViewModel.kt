package io.github.puflik.plinth.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.time.Duration

/**
 * Экран плеера первой вертикали (шаг 4): файл из SAF → звук.
 *
 * Название файла движку не нужно — его знает только экран, поэтому оно
 * живёт здесь, рядом с состоянием, а не в `AudioSource`.
 */
@HiltViewModel
class PlayerViewModel
    @Inject
    constructor(
        private val playback: PlaybackController,
    ) : ViewModel() {
        private val title = MutableStateFlow<String?>(null)

        val uiState: StateFlow<PlayerUiState> =
            combine(playback.state, playback.progress, title, PlayerUiState::from)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlayerUiState.EMPTY)

        fun onFilePicked(
            uri: String,
            name: String?,
        ) {
            title.value = name
            playback.open(AudioSource.LocalFile(uri))
        }

        fun onPlayPause() = playback.togglePlayPause()

        fun onSeek(position: Duration) = playback.seekTo(position)

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
