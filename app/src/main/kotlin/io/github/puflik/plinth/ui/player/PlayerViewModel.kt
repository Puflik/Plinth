package io.github.puflik.plinth.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.history.trackChangesOf
import io.github.puflik.plinth.history.trackOf
import io.github.puflik.plinth.library.UserDataRepository
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration

/**
 * Экран плеера: то, что открыли библиотека или SAF, — звук, название,
 * перемотка, очередь. Сам плеер ничего не открывает: трек и очередь знает
 * [PlaybackController]. Лайк текущего трека (D4a) — через [UserDataRepository]:
 * очередь фонотеку не знает, трек находится по источнику — файл по пути,
 * сетевой трек (E3) своим ID. ID файла не вечен: перепривязка каталога (Р9а)
 * переводит его на другой, поэтому лайк и «В плейлист» берут ID трека в момент
 * действия, а сердце следует за каталогом. «В плейлист» из меню плеера (Р6)
 * пишет [TrackActions] — в журнал приложения, как строка списка: плеер можно
 * свернуть сразу после выбора, и запись из его собственной области пропала бы.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel
    @Inject
    constructor(
        private val playback: PlaybackController,
        private val userData: UserDataRepository,
        private val actions: TrackActions,
    ) : ViewModel() {
        /**
         * Трек фонотеки, который играет, — для сердца; файл не из фонотеки — `null`. ID трека
         * под путём меняется, когда перепривязка (скан после переустановки, «Восстановить»)
         * переводит трек на ID журнала (Р9а), поэтому ID следует за каталогом, а запись берёт
         * его в момент действия, а не отсюда ([onLike], [onAddToPlaylist]).
         */
        private val track: StateFlow<TrackId?> =
            playback.queue
                .map { it.current?.source }
                .distinctUntilChanged()
                .flatMapLatest { source -> source?.let(userData::trackChangesOf) ?: flowOf(null) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        /** Источник трека, для которого открыт выбор плейлиста; `null` — выбор закрыт, берётся играющий. */
        private var pinned: AudioSource? = null

        private val liked: Flow<Boolean?> =
            track.flatMapLatest { track -> track?.let(userData::liked) ?: flowOf(null) }

        val uiState: StateFlow<PlayerUiState> =
            combine(playback.state, playback.progress, playback.queue, liked) { state, progress, queue, liked ->
                PlayerUiState.from(state, progress, queue).copy(liked = liked)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlayerUiState.EMPTY)

        /** Сердце: лайк стоит — снять, нет — поставить. У файла не из фонотеки ничего не делает. */
        fun onLike() {
            val liked = uiState.value.liked ?: return
            val source = playing() ?: return
            viewModelScope.launch {
                userData.trackOf(source)?.let { userData.setLiked(it, !liked) }
            }
        }

        /**
         * Выбор плейлиста открыт (Р6): трек, для которого его открыли, запоминается.
         * Пока диалог открыт, очередь может уйти дальше, а в диалоге не видно, какой
         * трек добавляется, — в плейлист идёт запомненный, а не играющий в момент выбора.
         * Запоминается источник, а не ID: перепривязка успеет перевести трек на другой ID (Р9а).
         */
        fun onPlaylistPickerShown() {
            pinned = playing()
        }

        /** Выбор плейлиста закрыт — выбрали или отказались: запомненный трек забыт. */
        fun onPlaylistPickerClosed() {
            pinned = null
        }

        /**
         * «В плейлист…» из меню: трек фонотеки — запомненный при открытии выбора, а без
         * открытого выбора играющий, — в конец [playlist]; трека нет — ничего.
         */
        fun onAddToPlaylist(playlist: PlaylistId) {
            val source = pinned ?: playing() ?: return
            actions.addToPlaylist(source, playlist)
        }

        /**
         * «В плейлист…» → «Новый плейлист»: тот же трек, что у [onAddToPlaylist], — в
         * плейлист [name]; трека нет — нет и плейлиста.
         */
        fun onAddToNewPlaylist(name: String) {
            val source = pinned ?: playing() ?: return
            actions.addToNewPlaylist(source, name)
        }

        /** Источник играющего трека в этот момент; очередь пуста — `null`. */
        private fun playing(): AudioSource? {
            val queue = playback.queue.value
            return queue.current?.source
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
