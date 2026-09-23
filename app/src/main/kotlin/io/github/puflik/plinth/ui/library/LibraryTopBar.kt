package io.github.puflik.plinth.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.TrackSort

/**
 * Верхняя строка библиотеки: меню сортировки открытой вкладки (C4.3) и
 * прочее — открыть файл через SAF, вернуться к плееру.
 *
 * @param showSort списки видны: без разрешения сортировать нечего.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTopBar(
    tab: LibraryTab,
    state: LibraryUiState,
    showSort: Boolean,
    onTrackSort: (TrackSort) -> Unit,
    onAlbumSort: (AlbumSort) -> Unit,
    onOpenFile: () -> Unit,
    onNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.nav_library)) },
        actions = {
            if (showSort) {
                when (tab) {
                    LibraryTab.TRACKS, LibraryTab.FOLDERS -> SortMenu(TRACK_SORTS, state.trackSort, onTrackSort)
                    LibraryTab.ALBUMS -> SortMenu(ALBUM_SORTS, state.albumSort, onAlbumSort)
                    LibraryTab.ARTISTS -> Unit
                }
            }
            MoreMenu(onOpenFile, onNowPlaying)
        },
        // Отступ от строки состояния уже дал Scaffold активности.
        windowInsets = WindowInsets(0),
        modifier = modifier,
    )
}

@Composable
private fun <T> SortMenu(
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_sort), contentDescription = stringResource(R.string.library_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { RadioButton(selected = option == selected, onClick = null) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun MoreMenu(
    onOpenFile: () -> Unit,
    onNowPlaying: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.library_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuItem(R.string.library_now_playing) {
                expanded = false
                onNowPlaying()
            }
            MenuItem(R.string.player_open_file) {
                expanded = false
                onOpenFile()
            }
        }
    }
}

@Composable
private fun MenuItem(
    @StringRes label: Int,
    onClick: () -> Unit,
) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

private val TRACK_SORTS =
    listOf(
        TrackSort.TITLE to R.string.library_sort_title,
        TrackSort.ARTIST to R.string.library_sort_artist,
        TrackSort.ALBUM to R.string.library_sort_album,
    )

private val ALBUM_SORTS =
    listOf(
        AlbumSort.TITLE to R.string.library_sort_title,
        AlbumSort.ARTIST to R.string.library_sort_artist,
    )
