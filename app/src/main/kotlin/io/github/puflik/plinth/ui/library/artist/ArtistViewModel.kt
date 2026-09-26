package io.github.puflik.plinth.ui.library.artist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.library.LibraryRepository
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Что показывает экран исполнителя: его альбомы и его треки. */
data class ArtistUiState(
    val albums: List<Album> = emptyList(),
    val tracks: List<LibraryTrack> = emptyList(),
)

/**
 * Минимальный экран исполнителя (E5): альбомы, где он есть, и его треки.
 * Исполнитель приходит аргументом навигации — это тег трека, как в списке
 * исполнителей; своей записи у него нет.
 */
@HiltViewModel
class ArtistViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        repository: LibraryRepository,
        private val actions: TrackActions,
    ) : ViewModel() {
        val artist: String = checkNotNull(savedState.get<String>(ARG_NAME)) { "экран исполнителя открыт без имени" }

        val uiState: StateFlow<ArtistUiState> =
            combine(repository.artistAlbums(artist), repository.artistTracks(artist), ::ArtistUiState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ArtistUiState())

        /** Трек исполнителя: контекст — все его треки в порядке экрана. */
        fun onTrack(
            track: LibraryTrack,
            action: TrackAction,
        ) = actions.act(action, QueueContext.Artist(artist), uiState.value.tracks, track)

        companion object {
            const val ARG_NAME = "name"

            // Переживает поворот экрана, не держит подписку в фоне.
            private const val STOP_TIMEOUT_MS = 5_000L

            /** Аргументы навигации, из которых экран возьмёт [artist]. */
            fun arguments(artist: String): Map<String, Any?> = mapOf(ARG_NAME to artist)
        }
    }
