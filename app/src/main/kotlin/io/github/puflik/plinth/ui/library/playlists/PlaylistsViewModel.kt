package io.github.puflik.plinth.ui.library.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.ffi.Playlist
import io.github.puflik.plinth.library.PlaylistRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Вкладка «Плейлисты» (D4b): свои плейлисты по имени — создать,
 * переименовать, удалить. Имя без пробелов по краям; пустое не принимается.
 */
@HiltViewModel
class PlaylistsViewModel
    @Inject
    constructor(
        private val repository: PlaylistRepository,
    ) : ViewModel() {
        val playlists: StateFlow<List<Playlist>> =
            repository
                .playlists()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        fun create(name: String) {
            val clean = playlistName(name) ?: return
            viewModelScope.launch { repository.create(clean) }
        }

        fun rename(
            playlist: Playlist,
            name: String,
        ) {
            val clean = playlistName(name) ?: return
            viewModelScope.launch { repository.rename(playlist.id, clean) }
        }

        fun delete(playlist: Playlist) {
            viewModelScope.launch { repository.delete(playlist.id) }
        }

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
