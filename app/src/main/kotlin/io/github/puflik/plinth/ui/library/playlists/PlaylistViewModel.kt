package io.github.puflik.plinth.ui.library.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.library.PlaylistRepository
import io.github.puflik.plinth.library.model.PlaylistTrack
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Что видит экран плейлиста.
 *
 * @property loaded треки уже прочитаны: пусто — значит, пусто.
 */
data class PlaylistUiState(
    val name: String,
    val tracks: List<PlaylistTrack> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * Экран своего плейлиста (D4b): треки по порядку; касание играет плейлист с
 * этого места, строки переставляют и убирают. Индексы — среди видимых строк,
 * в индекс ядра их переводит [PlaylistRepository.move].
 *
 * Перестановка и удаление видны сразу, не дожидаясь ядра: пока хранилище
 * отдаёт прежний список, экран показывает правленый. Ядро не записало — через
 * [SETTLE_MS] экран возвращается к тому, что есть.
 */
@HiltViewModel
class PlaylistViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val repository: PlaylistRepository,
        private val actions: TrackActions,
    ) : ViewModel() {
        private val id = PlaylistId(checkNotNull(savedState.get<String>(ARG_ID)) { "плейлист открыт без id" })
        private val openedAs = savedState.get<String>(ARG_NAME).orEmpty()

        /** Правка, которую ядро ещё не вернуло: [shown] вместо [basedOn], пока хранилище отдаёт его. */
        private class Pending(
            val basedOn: List<PlaylistTrack>,
            val shown: List<PlaylistTrack>,
        )

        private val pending = MutableStateFlow<Pending?>(null)
        private var stored: List<PlaylistTrack> = emptyList()

        val state: StateFlow<PlaylistUiState> =
            combine(repository.playlists(), repository.tracks(id).onEach { stored = it }, pending) {
                playlists,
                tracks,
                edit,
                ->
                PlaylistUiState(
                    name = playlists.find { it.id == id }?.name ?: openedAs,
                    tracks = edit?.takeIf { it.basedOn == tracks }?.shown ?: tracks,
                    loaded = true,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlaylistUiState(openedAs))

        /** Строка [index]: контекст — плейлист, играет то место, которого коснулись. */
        fun onTrack(
            index: Int,
            action: TrackAction,
        ) {
            val current = state.value
            val row = current.tracks.getOrNull(index) ?: return
            val tracks = current.tracks.map(PlaylistTrack::track)
            actions.act(action, QueueContext.Playlist(id, current.name), tracks, row.track, at = index)
        }

        /** Строку [from] опустили на место [to]. */
        fun move(
            from: Int,
            to: Int,
        ) {
            val shown = state.value.tracks
            val row = shown.getOrNull(from) ?: return
            val target = to.coerceIn(0, shown.lastIndex)
            if (target == from) return
            edit(shown.toMutableList().apply { add(target, removeAt(from)) }) { repository.move(id, row.entry, target) }
        }

        /** Убирает строку [index]; тот же трек на других местах остаётся. */
        fun remove(index: Int) {
            val shown = state.value.tracks
            val row = shown.getOrNull(index) ?: return
            edit(shown - row) { repository.remove(row.entry) }
        }

        private fun edit(
            shown: List<PlaylistTrack>,
            write: suspend () -> Unit,
        ) {
            val edit = Pending(basedOn = stored, shown = shown)
            pending.value = edit
            viewModelScope.launch {
                write()
                delay(SETTLE_MS)
                pending.compareAndSet(edit, null)
            }
        }

        companion object {
            const val ARG_ID = "id"
            const val ARG_NAME = "name"

            /** Сколько ждать ядро, прежде чем вернуть экрану прежний порядок. */
            const val SETTLE_MS = 1_000L

            // Переживает поворот экрана, не держит подписку в фоне.
            private const val STOP_TIMEOUT_MS = 5_000L

            /** Аргументы навигации: имя — чтобы заголовок был виден до первого чтения. */
            fun arguments(
                playlist: PlaylistId,
                name: String,
            ): Map<String, Any?> = mapOf(ARG_ID to playlist.value, ARG_NAME to name)
        }
    }
