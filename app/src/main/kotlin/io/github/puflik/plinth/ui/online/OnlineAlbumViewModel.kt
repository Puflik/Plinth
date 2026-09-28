package io.github.puflik.plinth.ui.online

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.UserDataRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.online.OnlineAlbum
import io.github.puflik.plinth.online.OnlineRepository
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import io.github.puflik.plinth.ui.library.components.TrackLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Альбом провайдера, каким его знает результат поиска, — до ответа сети. */
data class OnlineAlbumHeader(
    val provider: String,
    val item: String,
    val title: String,
    val artist: String?,
    val year: Int?,
)

/** Трек альбома провайдера и его строка. */
data class OnlineAlbumTrack(
    val track: OnlineTrack,
    val label: TrackLabel,
)

/**
 * Что видит экран альбома провайдера.
 *
 * @property problem почему треков нет: нет сети, провайдер не ответил или
 *   альбома больше нет.
 */
data class OnlineAlbumUiState(
    val loading: Boolean = true,
    val tracks: List<OnlineAlbumTrack> = emptyList(),
    val problem: OnlineProblem? = null,
)

/**
 * Альбом провайдера (E3, ответ автора): треки из сети, «Играть»; касание —
 * трек и остальные треки альбома в очередь, меню — как у локального (лайк,
 * в плейлист).
 *
 * В каталог трек попадает при действии, а не при показе (ответ автора E1):
 * играть — весь альбом, лайк и плейлист — только этот трек. Уже заведённые
 * треки экран узнаёт сразу — у них виден лайк.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OnlineAlbumViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val online: OnlineRepository,
        private val userData: UserDataRepository,
        private val actions: TrackActions,
    ) : ViewModel() {
        val album: OnlineAlbumHeader =
            OnlineAlbumHeader(
                provider = checkNotNull(savedState.get<String>(ARG_PROVIDER)) { "альбом открыт без провайдера" },
                item = checkNotNull(savedState.get<String>(ARG_ITEM)) { "альбом открыт без элемента" },
                title = savedState.get<String>(ARG_TITLE).orEmpty(),
                artist = savedState.get<String>(ARG_ARTIST),
                year = savedState.get<Int>(ARG_YEAR)?.takeIf { it > 0 },
            )

        private val loaded = MutableStateFlow<Loaded?>(null)

        /** ID каталога по трекам альбома; `null` — трек ещё не заведён. */
        private val ids = MutableStateFlow<List<TrackId?>>(emptyList())

        private val likes: Flow<List<Boolean>> =
            ids.flatMapLatest { ids ->
                if (ids.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(ids.map { id -> id?.let(userData::liked) ?: flowOf(false) }) { it.toList() }
                }
            }

        val uiState: StateFlow<OnlineAlbumUiState> =
            combine(loaded, likes) { loaded, likes ->
                when (loaded) {
                    null -> OnlineAlbumUiState()
                    is Loaded.Failed -> OnlineAlbumUiState(loading = false, problem = loaded.problem)
                    is Loaded.Tracks ->
                        OnlineAlbumUiState(
                            loading = false,
                            tracks =
                                loaded.tracks.mapIndexed { index, track ->
                                    OnlineAlbumTrack(track, label(track, likes.getOrElse(index) { false }))
                                },
                        )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OnlineAlbumUiState())

        init {
            load()
        }

        fun onRetry() = load()

        /** «Играть»: альбом с первого трека. */
        fun onPlay() = onTrack(0, TrackAction.PLAY)

        /** [action] над треком [index]: играть — весь альбом, остальное — только он. */
        fun onTrack(
            index: Int,
            action: TrackAction,
        ) {
            val tracks = tracks() ?: return
            if (index !in tracks.indices) return
            val whole = action == TrackAction.PLAY || action == TrackAction.REPLACE_QUEUE
            val chosen = if (whole) tracks.indices.toList() else listOf(index)
            viewModelScope.launch {
                val library = catalog(tracks, chosen) ?: return@launch
                val at = chosen.indexOf(index)
                actions.act(action, QueueContext.Album(album.title, album.artist), library, library[at], at)
            }
        }

        /** «В плейлист» → [playlist]: трек [index] — в каталог и в конец плейлиста. */
        fun onAddToPlaylist(
            index: Int,
            playlist: PlaylistId,
        ) = withTrack(index) { actions.addToPlaylist(it, playlist) }

        /** «В плейлист» → «Новый плейлист» [name] с треком [index]. */
        fun onAddToNewPlaylist(
            index: Int,
            name: String,
        ) = withTrack(index) { actions.addToNewPlaylist(it, name) }

        private fun withTrack(
            index: Int,
            block: (LibraryTrack) -> Unit,
        ) {
            val tracks = tracks() ?: return
            if (index !in tracks.indices) return
            viewModelScope.launch { catalog(tracks, listOf(index))?.single()?.let(block) }
        }

        /** Треки [indices] в каталоге — как треки фонотеки; не вышло — `null`. */
        private suspend fun catalog(
            tracks: List<OnlineTrack>,
            indices: List<Int>,
        ): List<LibraryTrack>? {
            val chosen = indices.map(tracks::get)
            val added = online.add(album.provider, chosen) ?: return null
            ids.update { known ->
                known.toMutableList().apply { indices.zip(added).forEach { (index, id) -> set(index, id) } }
            }
            return chosen.zip(added) { track, id -> track.toLibraryTrack(id) }
        }

        private fun tracks(): List<OnlineTrack>? = (loaded.value as? Loaded.Tracks)?.tracks

        private fun load() {
            loaded.value = null
            viewModelScope.launch {
                when (val answer = online.album(album.provider, album.item)) {
                    is OnlineAlbum.Tracks -> {
                        ids.value = online.known(album.provider, answer.tracks)
                        loaded.value = Loaded.Tracks(answer.tracks)
                    }
                    is OnlineAlbum.Failed -> loaded.value = Loaded.Failed(answer.problem)
                }
            }
        }

        private fun label(
            track: OnlineTrack,
            liked: Boolean,
        ) = TrackLabel(track.title, track.artist, track.album, track.duration, liked, online = true)

        private fun OnlineTrack.toLibraryTrack(id: TrackId) =
            LibraryTrack(
                id = id,
                uri = null,
                title = title,
                artist = artist,
                album = album,
                trackNumber = number?.takeIf { it > 0 },
                duration = duration,
                folder = "",
            )

        private sealed interface Loaded {
            data class Tracks(
                val tracks: List<OnlineTrack>,
            ) : Loaded

            data class Failed(
                val problem: OnlineProblem,
            ) : Loaded
        }

        companion object {
            const val ARG_PROVIDER = "provider"
            const val ARG_ITEM = "item"
            const val ARG_TITLE = "title"
            const val ARG_ARTIST = "artist"
            const val ARG_YEAR = "year"

            // Переживает поворот экрана, не держит подписку в фоне.
            private const val STOP_TIMEOUT_MS = 5_000L

            /** Аргументы навигации, из которых экран соберёт [album]. */
            fun arguments(album: OnlineAlbumHeader): Map<String, Any?> =
                mapOf(
                    ARG_PROVIDER to album.provider,
                    ARG_ITEM to album.item,
                    ARG_TITLE to album.title,
                    ARG_ARTIST to album.artist,
                    ARG_YEAR to album.year,
                )
        }
    }
