package io.github.puflik.plinth.ui.library.playlists

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ffi.Playlist
import kotlinx.coroutines.flow.Flow

/**
 * Вкладка «Плейлисты» (D4b): сверху «Любимое» и «Недавнее», ниже свои
 * плейлисты по имени. Новый плейлист — первой строкой раздела, переименовать и
 * удалить — из меню ⋮ плейлиста; всё — диалогами.
 *
 * Импорт M3U, M3U8 и PLS — второй строкой раздела, экспорт в M3U8 — из меню ⋮
 * (D4c); файл выбирает и создаёт системный выбор, итог — коротким сообщением.
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
    val pickers = rememberPlaylistFilePickers(viewModel, playlists)
    PlaylistNotices(viewModel.notices)
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
        item(key = IMPORT_KEY) {
            Entry(R.drawable.ic_import, stringResource(R.string.playlist_import), onClick = pickers.import)
        }
        items(playlists, key = { it.id.value }) { playlist ->
            Entry(R.drawable.ic_playlist, playlist.name, onClick = { onOpenPlaylist(playlist) }) {
                PlaylistMenu(
                    onRename = { dialog = PlaylistDialog.Rename(playlist) },
                    onExport = { pickers.export(playlist) },
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

/** Системные выборы файла плейлиста (D4c): открыть — для импорта, создать — для экспорта. */
private class PlaylistFilePickers(
    val import: () -> Unit,
    val export: (Playlist) -> Unit,
)

@Composable
private fun rememberPlaylistFilePickers(
    viewModel: PlaylistsViewModel,
    playlists: List<Playlist>,
): PlaylistFilePickers {
    val opener =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.import(uri.toString())
        }
    // Какой плейлист ждёт файла: системный выбор — отдельный экран, ответ приходит позже.
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val creator =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(M3U8_TYPE)) { uri ->
            val playlist = playlists.firstOrNull { it.id.value == exporting }
            exporting = null
            if (uri != null && playlist != null) viewModel.export(playlist, uri.toString())
        }
    return PlaylistFilePickers(
        import = { opener.launch(PLAYLIST_TYPES) },
        export = { playlist ->
            exporting = playlist.id.value
            creator.launch("${playlist.name}.m3u8")
        },
    )
}

/** Итог импорта или экспорта — коротким сообщением. */
@Composable
private fun PlaylistNotices(notices: Flow<PlaylistNotice>) {
    val context = LocalContext.current
    LaunchedEffect(notices) {
        notices.collect { notice -> Toast.makeText(context, notice.text(context), Toast.LENGTH_LONG).show() }
    }
}

private fun PlaylistNotice.text(context: Context): String =
    when (this) {
        is PlaylistNotice.Imported -> context.getString(R.string.playlist_imported, added, notFound)
        PlaylistNotice.ImportFailed -> context.getString(R.string.playlist_import_failed)
        is PlaylistNotice.Exported ->
            if (skipped == 0) {
                context.getString(R.string.playlist_exported, name)
            } else {
                context.getString(R.string.playlist_exported_partial, name, skipped)
            }
        PlaylistNotice.ExportFailed -> context.getString(R.string.playlist_export_failed)
    }

@Composable
private fun PlaylistMenu(
    onRename: () -> Unit,
    onExport: () -> Unit,
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
                text = { Text(stringResource(R.string.playlist_export)) },
                onClick = {
                    open = false
                    onExport()
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
private const val IMPORT_KEY = "import"

/**
 * Типы файлов плейлиста в системном выборе: M3U и M3U8 под разными именами
 * у разных провайдеров, PLS; `application/octet-stream` — тип, который
 * провайдер даёт файлу с незнакомым расширением.
 */
private val PLAYLIST_TYPES =
    arrayOf(
        "audio/x-mpegurl",
        "audio/mpegurl",
        "application/x-mpegurl",
        "application/vnd.apple.mpegurl",
        "audio/x-scpls",
        "application/octet-stream",
    )

private const val M3U8_TYPE = "audio/x-mpegurl"
