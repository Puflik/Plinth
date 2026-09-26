package io.github.puflik.plinth.ui.library.playlists

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ffi.Playlist

/**
 * Вкладка «Плейлисты» (D4b): сверху «Любимое» и «Недавнее», ниже свои
 * плейлисты по имени. Новый плейлист — первой строкой раздела, переименовать и
 * удалить — из меню ⋮ плейлиста; всё — диалогами.
 */
@Composable
fun PlaylistsTab(
    onOpenAuto: (AutoPlaylist) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsState()
    var dialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    LazyColumn(modifier = modifier) {
        items(AutoPlaylist.entries, key = { it.name }) { auto ->
            Entry(auto.iconRes, stringResource(auto.labelRes), onClick = { onOpenAuto(auto) })
        }
        item(key = MINE_KEY) {
            Text(
                text = stringResource(R.string.playlists_mine),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }
        item(key = NEW_KEY) {
            Entry(
                R.drawable.ic_add,
                stringResource(R.string.playlist_new),
                onClick = { dialog = PlaylistDialog.Create },
            )
        }
        items(playlists, key = { it.id.value }) { playlist ->
            Entry(R.drawable.ic_playlist, playlist.name, onClick = { onOpenPlaylist(playlist) }) {
                PlaylistMenu(
                    onRename = { dialog = PlaylistDialog.Rename(playlist) },
                    onDelete = { dialog = PlaylistDialog.Delete(playlist) },
                )
            }
        }
    }
    PlaylistDialogs(dialog, viewModel, onDone = { dialog = null })
}

/** Какой диалог открыт на вкладке. */
private sealed interface PlaylistDialog {
    data object Create : PlaylistDialog

    data class Rename(
        val playlist: Playlist,
    ) : PlaylistDialog

    data class Delete(
        val playlist: Playlist,
    ) : PlaylistDialog
}

@Composable
private fun PlaylistDialogs(
    dialog: PlaylistDialog?,
    viewModel: PlaylistsViewModel,
    onDone: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        PlaylistDialog.Create ->
            PlaylistNameDialog(
                title = R.string.playlist_new,
                confirm = R.string.playlist_create,
                onConfirm = { name ->
                    viewModel.create(name)
                    onDone()
                },
                onDismiss = onDone,
            )
        is PlaylistDialog.Rename ->
            PlaylistNameDialog(
                title = R.string.playlist_rename_title,
                confirm = R.string.playlist_save,
                initial = dialog.playlist.name,
                onConfirm = { name ->
                    viewModel.rename(dialog.playlist, name)
                    onDone()
                },
                onDismiss = onDone,
            )
        is PlaylistDialog.Delete ->
            DeletePlaylistDialog(
                name = dialog.playlist.name,
                onConfirm = {
                    viewModel.delete(dialog.playlist)
                    onDone()
                },
                onDismiss = onDone,
            )
    }
}

/** Строка вкладки: значок, название и, у своих плейлистов, меню ⋮. */
@Composable
private fun Entry(
    @DrawableRes icon: Int,
    title: String,
    onClick: () -> Unit,
    menu: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = menu,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PlaylistMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.playlist_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_rename)) },
                onClick = {
                    open = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_delete)) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

// Ключи плейлистов — UUID, с этими не совпадут.
private const val MINE_KEY = "mine"
private const val NEW_KEY = "new"
