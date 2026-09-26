package io.github.puflik.plinth.ui.library.playlists

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ffi.Playlist
import io.github.puflik.plinth.library.PlaylistRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * «В плейлист» из меню трека (D4b): свои плейлисты по имени и «Новый
 * плейлист» первым. Выбранное подтверждается коротко, как «добавлено в
 * очередь»; пишет [TrackActions].
 */
@Composable
fun PlaylistPicker(
    track: LibraryTrack,
    onDismiss: () -> Unit,
    viewModel: PlaylistPickerViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsState()
    var naming by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val added = { name: String ->
        Toast.makeText(context, context.getString(R.string.playlist_added, name), Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    if (naming) {
        PlaylistNameDialog(
            title = R.string.playlist_new,
            confirm = R.string.playlist_create,
            onConfirm = { name ->
                viewModel.addToNew(track, name)
                added(name)
            },
            onDismiss = onDismiss,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_pick_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = LIST_HEIGHT)) {
                item(key = NEW_KEY) {
                    PickerRow(R.drawable.ic_add, stringResource(R.string.playlist_new)) { naming = true }
                }
                items(playlists, key = { it.id.value }) { playlist ->
                    PickerRow(R.drawable.ic_playlist, playlist.name) {
                        viewModel.add(track, playlist)
                        added(playlist.name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PickerRow(
    @DrawableRes icon: Int,
    text: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Плейлисты для выбора; добавление — через [TrackActions], в фоне приложения. */
@HiltViewModel
class PlaylistPickerViewModel
    @Inject
    constructor(
        repository: PlaylistRepository,
        private val actions: TrackActions,
    ) : ViewModel() {
        val playlists: StateFlow<List<Playlist>> =
            repository
                .playlists()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        fun add(
            track: LibraryTrack,
            playlist: Playlist,
        ) = actions.addToPlaylist(track, playlist.id)

        fun addToNew(
            track: LibraryTrack,
            name: String,
        ) = actions.addToNewPlaylist(track, name)

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

private const val NEW_KEY = "new"
private val LIST_HEIGHT = 360.dp
