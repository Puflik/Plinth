package io.github.puflik.plinth.ui.library.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.library.LibraryRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * «Любимое» или «Недавнее» (D4b): список фонотеки, который складывается сам.
 * Какой — приходит аргументом навигации.
 */
@HiltViewModel
class AutoPlaylistViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        repository: LibraryRepository,
        private val actions: TrackActions,
    ) : ViewModel() {
        val kind: AutoPlaylist =
            AutoPlaylist.valueOf(checkNotNull(savedState.get<String>(ARG_KIND)) { "список открыт без вида" })

        /** Треки списка; `null` — ещё не прочитаны: пустой список — это «пусто», а не «ждём». */
        val tracks: StateFlow<List<LibraryTrack>?> =
            when (kind) {
                AutoPlaylist.LIKED -> repository.likedTracks()
                AutoPlaylist.RECENT -> repository.recentTracks()
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        /** Трек списка: контекст — сам список. */
        fun onTrack(
            track: LibraryTrack,
            action: TrackAction,
        ) {
            val context =
                when (kind) {
                    AutoPlaylist.LIKED -> QueueContext.Liked
                    AutoPlaylist.RECENT -> QueueContext.Recent
                }
            actions.act(action, context, tracks.value.orEmpty(), track)
        }

        companion object {
            const val ARG_KIND = "kind"

            // Переживает поворот экрана, не держит подписку в фоне.
            private const val STOP_TIMEOUT_MS = 5_000L

            /** Аргументы навигации списка [kind]. */
            fun arguments(kind: AutoPlaylist): Map<String, Any?> = mapOf(ARG_KIND to kind.name)
        }
    }
