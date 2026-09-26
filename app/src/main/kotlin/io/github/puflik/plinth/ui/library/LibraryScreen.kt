package io.github.puflik.plinth.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ffi.Playlist
import io.github.puflik.plinth.library.ScanProgress
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.permission.PermissionState
import io.github.puflik.plinth.startup.OnboardingStep
import io.github.puflik.plinth.ui.common.PermissionRationaleScreen
import io.github.puflik.plinth.ui.common.openAppSettings
import io.github.puflik.plinth.ui.common.rememberMediaPermission
import io.github.puflik.plinth.ui.library.components.rememberTrackActionFeedback
import io.github.puflik.plinth.ui.library.playlists.AutoPlaylist
import io.github.puflik.plinth.ui.library.playlists.PlaylistsTab
import io.github.puflik.plinth.ui.library.tabs.AlbumsTab
import io.github.puflik.plinth.ui.library.tabs.ArtistsTab
import io.github.puflik.plinth.ui.library.tabs.FoldersTab
import io.github.puflik.plinth.ui.library.tabs.TracksTab
import io.github.puflik.plinth.ui.player.rememberAudioFilePicker
import io.github.puflik.plinth.ui.settings.rememberFolderPicker

/**
 * Библиотека — стартовый экран (C4.1): вкладки треков, альбомов,
 * исполнителей, плейлистов (D4b) и папок. Касание трека включает его и открывает плеер; файл
 * мимо библиотеки открывается через SAF из меню.
 *
 * Разрешение проверяется при каждом возврате на экран: его могли выдать или
 * отозвать в настройках. Первый раз экран спрашивает сам, не объясняя, —
 * объяснение нужно только после отказа.
 */
@Composable
fun LibraryScreen(
    onOpenPlayer: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenAutoPlaylist: (AutoPlaylist) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var tab by rememberSaveable { mutableStateOf(LibraryTab.TRACKS) }
    val requestPermission = rememberMediaPermission(askFirst = true, onState = viewModel::onPermission)
    val context = LocalContext.current
    val openFile =
        rememberAudioFilePicker { uri, name ->
            viewModel.openFile(uri, name)
            onOpenPlayer()
        }
    val pickFolder = rememberFolderPicker(viewModel::onFolderPicked)
    val feedback = rememberTrackActionFeedback(onOpenPlayer)
    val onTrack = { track: LibraryTrack, action: TrackAction ->
        viewModel.onTrack(track, action)
        feedback(action)
    }
    val onFolderTrack = { track: LibraryTrack, action: TrackAction ->
        viewModel.onFolderTrack(track, action)
        feedback(action)
    }
    BackHandler(enabled = tab == LibraryTab.FOLDERS && state.folder.parentPath != null) { viewModel.folderUp() }

    Column(modifier = modifier.fillMaxSize()) {
        LibraryTopBar(
            tab = tab,
            state = state,
            showSort = state.permission == PermissionState.Granted && !state.isEmpty,
            onTrackSort = viewModel::onTrackSort,
            onAlbumSort = viewModel::onAlbumSort,
            onOpenFile = openFile,
        )
        when (state.permission) {
            PermissionState.Granted ->
                LibraryContent(
                    state = state,
                    tab = tab,
                    onTab = { tab = it },
                    onTrack = onTrack,
                    onFolderTrack = onFolderTrack,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenAutoPlaylist = onOpenAutoPlaylist,
                    onOpenFolder = viewModel::openFolder,
                    onFolderUp = viewModel::folderUp,
                    onPickFolder = pickFolder,
                    onDismissPrompt = viewModel::onDismissPrompt,
                )
            PermissionState.Denied, PermissionState.PermanentlyDenied ->
                PermissionRationaleScreen(
                    permanentlyDenied = state.permission == PermissionState.PermanentlyDenied,
                    onRequest = requestPermission,
                    onOpenSettings = { openAppSettings(context) },
                )
            PermissionState.NotRequested -> Unit
        }
    }
}

/**
 * Выданное разрешение: статус скана и вкладки. Пустая библиотека —
 * проводник и «что дальше» (F2); пока скан идёт по пустой — «ищу музыку».
 */
@Composable
private fun LibraryContent(
    state: LibraryUiState,
    tab: LibraryTab,
    onTab: (LibraryTab) -> Unit,
    onTrack: (LibraryTrack, TrackAction) -> Unit,
    onFolderTrack: (LibraryTrack, TrackAction) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenAutoPlaylist: (AutoPlaylist) -> Unit,
    onOpenFolder: (String) -> Unit,
    onFolderUp: () -> Unit,
    onPickFolder: () -> Unit,
    onDismissPrompt: (OnboardingStep) -> Unit,
) {
    ScanStatus(state.scan)
    if (state.isEmpty) {
        EmptyLibrary(state.scannedFolders, state.prompt, onPickFolder, onDismissPrompt)
        return
    }
    if (state.tracks.isEmpty()) {
        // Списки ещё не прочитаны — ничего; прочитаны, но скан не закончен — он и ищет.
        if (state.loaded) Scanning()
        return
    }
    // Пять вкладок в ширину телефона не влезают — ряд прокручивается, подпись в одну строку.
    PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
        LibraryTab.entries.forEach { entry ->
            Tab(
                selected = entry == tab,
                onClick = { onTab(entry) },
                text = { Text(stringResource(entry.labelRes), maxLines = 1) },
            )
        }
    }
    when (tab) {
        LibraryTab.TRACKS -> TracksTab(state.tracks, onAction = onTrack)
        LibraryTab.ALBUMS -> AlbumsTab(state.albums, onOpen = onOpenAlbum)
        LibraryTab.ARTISTS -> ArtistsTab(state.artists, onOpen = onOpenArtist)
        LibraryTab.PLAYLISTS -> PlaylistsTab(onOpenAuto = onOpenAutoPlaylist, onOpenPlaylist = onOpenPlaylist)
        LibraryTab.FOLDERS -> FoldersTab(state.folder, onOpenFolder, onUp = onFolderUp, onAction = onFolderTrack)
    }
}

/** Скан виден, только пока идёт или если сломался; готовый скан — это сами списки. */
@Composable
private fun ScanStatus(scan: ScanProgress) {
    when (scan) {
        is ScanProgress.Running ->
            if (scan.total == 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { scan.written.toFloat() / scan.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        ScanProgress.Failed ->
            Text(
                text = stringResource(R.string.library_scan_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            )
        ScanProgress.Idle, is ScanProgress.Done -> Unit
    }
}

@Composable
private fun Scanning() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.library_scanning),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}
