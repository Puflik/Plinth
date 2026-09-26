package io.github.puflik.plinth.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.UserDataRepository
import io.github.puflik.plinth.queue.QueueItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration

/**
 * Экран плеера: то, что открыли библиотека или SAF, — звук, название,
 * перемотка, очередь. Сам плеер ничего не открывает: трек и очередь знает
 * [PlaybackController]. Лайк текущего трека (D4a) — через [UserDataRepository]:
 * очередь фонотеку не знает, трек находится по файлу.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel
    @Inject
    constructor(
        private val playback: PlaybackController,
        private val userData: UserDataRepository,
    ) : ViewModel() {
        /** Трек фонотеки, который играет; файл не из фонотеки — `null`. */
        private val track: StateFlow<TrackId?> =
            playback.queue
                .map { (it.current?.source as? AudioSource.LocalFile)?.uri }
                .distinctUntilChanged()
                .mapLatest { uri -> uri?.let { userData.trackAt(it) } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private val liked: Flow<Boolean?> =
            track.flatMapLatest { track -> track?.let(userData::liked) ?: flowOf(null) }

        val uiState: StateFlow<PlayerUiState> =
            combine(playback.state, playback.progress, playback.queue, liked) { state, progress, queue, liked ->
                PlayerUiState.from(state, progress, queue).copy(liked = liked)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlayerUiState.EMPTY)

        /** Сердце: лайк стоит — снять, нет — поставить. У файла не из фонотеки ничего не делает. */
        fun onLike() {
            val track = track.value ?: return
            val liked = uiState.value.liked ?: return
            viewModelScope.launch { userData.setLiked(track, !liked) }
        }

        fun onPlayPause() = playback.togglePlayPause()

        fun onSeek(position: Duration) = playback.seekTo(position)

        fun onSeekBy(delta: Duration) = playback.seekBy(delta)

        fun onNext() = playback.next()

        fun onPrevious() = playback.previous()

        fun onShuffle() = playback.toggleShuffle()

        fun onRemoveUpcoming(
            index: Int,
            item: QueueItem,
        ) = playback.removeUpcoming(index, item)

        fun onMoveUpcoming(
            from: Int,
            to: Int,
            item: QueueItem,
        ) = playback.moveUpcoming(from, to, item)

        fun onRepeat() = playback.cycleRepeat()

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
