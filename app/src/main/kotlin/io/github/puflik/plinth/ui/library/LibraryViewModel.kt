package io.github.puflik.plinth.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.library.LibraryRepository
import io.github.puflik.plinth.library.LibraryScan
import io.github.puflik.plinth.library.ScanProgress
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import io.github.puflik.plinth.library.model.LibraryFolder
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.permission.PermissionState
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.TrackSort
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.settings.SortSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Что видит экран библиотеки: разрешение, скан, списки вкладок и их порядок.
 *
 * @property folder открытая на вкладке «Папки» папка; треки в ней — в
 *   порядке [trackSort], как на вкладке «Треки».
 */
data class LibraryUiState(
    val permission: PermissionState,
    val scan: ScanProgress,
    val trackSort: TrackSort = TrackSort.TITLE,
    val albumSort: AlbumSort = AlbumSort.TITLE,
    val tracks: List<LibraryTrack> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val folder: LibraryFolder = LibraryFolder.tree(emptyList()),
)

/**
 * Библиотека на экране (C4.1): разрешение, фоновый скан и списки фонотеки.
 *
 * Скан запускается, когда разрешение становится выданным: при старте
 * приложения, если оно уже есть, и сразу после выдачи. Узнаёт о разрешении
 * экран — только у него есть Activity для запроса и объяснения.
 *
 * Списки приходят из [LibraryRepository] потоками и обновляются сами, когда
 * скан что-то записал. Касание трека открывает его в [PlaybackController].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val scan: LibraryScan,
        repository: LibraryRepository,
        private val playback: PlaybackController,
        private val sorts: SortSettings,
    ) : ViewModel() {
        private val permission = MutableStateFlow(PermissionState.NotRequested)
        private val trackSort = sorts.trackSort
        private val albumSort = sorts.albumSort

        // Какую папку открыли. Показывается ближайшая уцелевшая на этом пути — см. LibraryFolder.open.
        private val folderPath = MutableStateFlow("")

        private val lists: Flow<Lists> =
            combine(
                trackSort.flatMapLatest(repository::tracks),
                albumSort.flatMapLatest(repository::albums),
                repository.artists(),
                folderPath,
            ) { tracks, albums, artists, path -> Lists(tracks, albums, artists, LibraryFolder.tree(tracks).open(path)) }

        val uiState: StateFlow<LibraryUiState> =
            combine(permission, scan.progress, trackSort, albumSort, lists) { permission, scan, tracks, albums, lists ->
                LibraryUiState(
                    permission = permission,
                    scan = scan,
                    trackSort = tracks,
                    albumSort = albums,
                    tracks = lists.tracks,
                    albums = lists.albums,
                    artists = lists.artists,
                    folder = lists.folder,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), INITIAL)

        fun onPermission(state: PermissionState) {
            val wasGranted = permission.value == PermissionState.Granted
            permission.value = state
            if (state == PermissionState.Granted && !wasGranted) scan.start()
        }

        /** Порядок сохраняется и переживает перезапуск; список перестроится, когда он запишется. */
        fun onTrackSort(sort: TrackSort) {
            viewModelScope.launch { sorts.setTrackSort(sort) }
        }

        fun onAlbumSort(sort: AlbumSort) {
            viewModelScope.launch { sorts.setAlbumSort(sort) }
        }

        /** Трек со вкладки «Треки»: контекст — весь список в выбранном порядке. */
        fun onTrack(
            track: LibraryTrack,
            action: TrackAction,
        ) = playback.act(action, QueueContext.Tracks, uiState.value.tracks, track)

        /** Трек со вкладки «Папки»: контекст — треки открытой папки. */
        fun onFolderTrack(
            track: LibraryTrack,
            action: TrackAction,
        ) {
            val folder = uiState.value.folder
            playback.act(action, QueueContext.Folder(folder.path), folder.tracks, track)
        }

        /** Файл, выбранный через SAF: играет и без разрешения на музыку — контекстом из себя одного. */
        fun openFile(
            uri: String,
            name: String?,
        ) = playback.play(QueueContext.File, listOf(QueueItem(AudioSource.LocalFile(uri), title = name)), start = 0)

        fun openFolder(path: String) {
            folderPath.value = path
        }

        /** На уровень выше той папки, что сейчас на экране; из корня — никуда. */
        fun folderUp() {
            uiState.value.folder.parentPath
                ?.let { folderPath.value = it }
        }

        private data class Lists(
            val tracks: List<LibraryTrack>,
            val albums: List<Album>,
            val artists: List<Artist>,
            val folder: LibraryFolder,
        )

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
            val INITIAL = LibraryUiState(PermissionState.NotRequested, ScanProgress.Idle)
        }
    }
