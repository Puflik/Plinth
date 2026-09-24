package io.github.puflik.plinth.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.ScanProgress
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.permission.MediaPermission
import io.github.puflik.plinth.library.permission.PermissionState
import io.github.puflik.plinth.ui.common.PermissionRationaleScreen
import io.github.puflik.plinth.ui.library.components.rememberTrackActionFeedback
import io.github.puflik.plinth.ui.library.tabs.AlbumsTab
import io.github.puflik.plinth.ui.library.tabs.ArtistsTab
import io.github.puflik.plinth.ui.library.tabs.FoldersTab
import io.github.puflik.plinth.ui.library.tabs.TracksTab
import io.github.puflik.plinth.ui.player.rememberAudioFilePicker

/**
 * Библиотека — стартовый экран (C4.1): вкладки треков, альбомов,
 * исполнителей и папок. Касание трека включает его и открывает плеер; файл
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
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var tab by rememberSaveable { mutableStateOf(LibraryTab.TRACKS) }
    val requestPermission = rememberMediaPermission(viewModel::onPermission)
    val context = LocalContext.current
    val openFile =
        rememberAudioFilePicker { uri, name ->
            viewModel.openFile(uri, name)
            onOpenPlayer()
        }
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
            showSort = state.permission == PermissionState.Granted,
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
                    onOpenFolder = viewModel::openFolder,
                    onFolderUp = viewModel::folderUp,
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

/** Выданное разрешение: статус скана, вкладки и открытая вкладка. */
@Composable
private fun LibraryContent(
    state: LibraryUiState,
    tab: LibraryTab,
    onTab: (LibraryTab) -> Unit,
    onTrack: (LibraryTrack, TrackAction) -> Unit,
    onFolderTrack: (LibraryTrack, TrackAction) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenFolder: (String) -> Unit,
    onFolderUp: () -> Unit,
) {
    ScanStatus(state.scan)
    PrimaryTabRow(selectedTabIndex = tab.ordinal) {
        LibraryTab.entries.forEach { entry ->
            Tab(
                selected = entry == tab,
                onClick = { onTab(entry) },
                text = { Text(stringResource(entry.labelRes)) },
            )
        }
    }
    if (state.tracks.isEmpty()) {
        EmptyLibrary(scanning = state.scan is ScanProgress.Running)
        return
    }
    when (tab) {
        LibraryTab.TRACKS -> TracksTab(state.tracks, onAction = onTrack)
        LibraryTab.ALBUMS -> AlbumsTab(state.albums, onOpen = onOpenAlbum)
        LibraryTab.ARTISTS -> ArtistsTab(state.artists)
        LibraryTab.FOLDERS -> FoldersTab(state.folder, onOpenFolder, onUp = onFolderUp, onAction = onFolderTrack)
    }
}

/**
 * Следит за разрешением на музыку и сообщает его [onState] при каждом
 * возврате на экран; в первый раз сам показывает системный запрос.
 * Возвращает действие «спросить снова».
 */
@Composable
private fun rememberMediaPermission(onState: (PermissionState) -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val permission = remember { MediaPermission.name() }
    // Пришёл ли ответ на запрос: без него «нет объяснения» не отличить от «отказано навсегда».
    var requested by rememberSaveable { mutableStateOf(false) }

    fun currentState(): PermissionState {
        val showRationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) }
        return PermissionState.of(MediaPermission.isGranted(context), requested, showRationale == true)
    }

    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            requested = true
            onState(currentState())
        }
    LifecycleResumeEffect(Unit) {
        val current = currentState()
        onState(current)
        if (current == PermissionState.NotRequested) request.launch(permission)
        onPauseOrDispose {}
    }
    return { request.launch(permission) }
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
private fun EmptyLibrary(scanning: Boolean) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(if (scanning) R.string.library_scanning else R.string.library_empty),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}
