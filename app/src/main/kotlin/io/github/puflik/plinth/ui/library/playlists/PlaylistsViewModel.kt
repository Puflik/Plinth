package io.github.puflik.plinth.ui.library.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.ffi.Playlist
import io.github.puflik.plinth.library.PlaylistFiles
import io.github.puflik.plinth.library.PlaylistRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Вкладка «Плейлисты» (D4b): свои плейлисты по имени — создать,
 * переименовать, удалить. Имя без пробелов по краям; пустое не принимается.
 *
 * Импорт и экспорт M3U (D4c): файл выбирает и создаёт системный выбор SAF,
 * здесь — чтение, запись и короткий итог в [notices].
 */
@HiltViewModel
class PlaylistsViewModel
    @Inject
    constructor(
        private val repository: PlaylistRepository,
        private val files: PlaylistFiles,
    ) : ViewModel() {
        val playlists: StateFlow<List<Playlist>> =
            repository
                .playlists()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        private val noticeChannel = Channel<PlaylistNotice>(Channel.BUFFERED)

        /** Итоги импорта и экспорта — каждый показывается один раз. */
        val notices: Flow<PlaylistNotice> = noticeChannel.receiveAsFlow()

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

        /** Плейлист из выбранного файла [uri]; имя — имя файла. */
        fun import(uri: String) {
            viewModelScope.launch {
                val file = files.read(uri)
                val report = file?.let { repository.import(playlistName(it.name) ?: it.name, it.content, it.folder) }
                noticeChannel.send(
                    report?.let { PlaylistNotice.Imported(it.added, it.notFound) } ?: PlaylistNotice.ImportFailed,
                )
            }
        }

        /** [playlist] в M3U8 — в созданный файл [uri]. */
        fun export(
            playlist: Playlist,
            uri: String,
        ) {
            viewModelScope.launch {
                val text = repository.export(playlist.id)
                val written = text?.let { files.write(uri, it) } == true
                // M3U хранит только файлы: треки провайдеров в него не попадают, и об этом надо сказать (ревью v0.2, №15).
                val skipped =
                    text?.let { m3u ->
                        val kept = m3u.lines().count { it.isNotBlank() && !it.startsWith("#") }
                        (repository.tracks(playlist.id).first().size - kept).coerceAtLeast(0)
                    } ?: 0
                noticeChannel.send(
                    if (written) PlaylistNotice.Exported(playlist.name, skipped) else PlaylistNotice.ExportFailed,
                )
            }
        }

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/** Итог импорта или экспорта плейлиста — короткое сообщение на вкладке (D4c). */
sealed interface PlaylistNotice {
    /** Нашлось [added] строк, не нашлось [notFound]; ничего не нашлось — плейлиста нет. */
    data class Imported(
        val added: Int,
        val notFound: Int,
    ) : PlaylistNotice

    data object ImportFailed : PlaylistNotice

    data class Exported(
        val name: String,
        val skipped: Int = 0,
    ) : PlaylistNotice

    data object ExportFailed : PlaylistNotice
}
