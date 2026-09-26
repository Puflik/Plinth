package io.github.puflik.plinth.ui.library.album

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
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Минимальный экран альбома (C4.1): его треки по диску и номеру.
 *
 * Альбом приходит аргументами навигации — своей записи и `id` у него нет,
 * он узнаётся по названию и владельцу, как в [LibraryRepository.albumTracks].
 */
@HiltViewModel
class AlbumViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        repository: LibraryRepository,
        private val actions: TrackActions,
    ) : ViewModel() {
        val album: Album =
            Album(
                title = checkNotNull(savedState.get<String>(ARG_TITLE)) { "экран альбома открыт без названия" },
                artist = savedState.get<String>(ARG_ARTIST),
                trackCount = savedState.get<Int>(ARG_TRACK_COUNT) ?: 0,
            )

        val tracks: StateFlow<List<LibraryTrack>> =
            repository
                .albumTracks(album)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        /** Трек альбома: контекст — альбом по диску и номеру. */
        fun onTrack(
            track: LibraryTrack,
            action: TrackAction,
        ) = actions.act(action, QueueContext.Album(album.title, album.artist), tracks.value, track)

        companion object {
            const val ARG_TITLE = "title"
            const val ARG_ARTIST = "artist"
            const val ARG_TRACK_COUNT = "tracks"

            // Переживает поворот экрана, не держит подписку в фоне.
            private const val STOP_TIMEOUT_MS = 5_000L

            /** Аргументы навигации, из которых экран соберёт [album]. */
            fun arguments(album: Album): Map<String, Any?> =
                mapOf(ARG_TITLE to album.title, ARG_ARTIST to album.artist, ARG_TRACK_COUNT to album.trackCount)
        }
    }
