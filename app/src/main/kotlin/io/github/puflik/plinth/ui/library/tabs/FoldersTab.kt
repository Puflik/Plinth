package io.github.puflik.plinth.ui.library.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.model.LibraryFolder
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.components.TrackRow

/**
 * Папки как они лежат на диске (C4.1): сначала подпапки, потом треки
 * открытой папки. Вверх — первой строкой или системной кнопкой «Назад».
 */
@Composable
fun FoldersTab(
    folder: LibraryFolder,
    onOpenFolder: (String) -> Unit,
    onUp: () -> Unit,
    onAction: (LibraryTrack, TrackAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        folder.parentPath?.let { parent ->
            item(key = UP_KEY) { UpRow(parent, onUp) }
        }
        items(folder.folders, key = LibraryFolder::path) { child ->
            ListItem(
                headlineContent = { Text(child.name) },
                supportingContent = {
                    Text(pluralStringResource(R.plurals.library_track_count, child.trackCount, child.trackCount))
                },
                leadingContent = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                modifier = Modifier.clickable { onOpenFolder(child.path) },
            )
        }
        items(folder.tracks, key = LibraryTrack::id) { track -> TrackRow(track, onAction = { onAction(track, it) }) }
    }
}

@Composable
private fun UpRow(
    parentPath: String,
    onUp: () -> Unit,
) {
    val parentName = parentPath.trimEnd('/').substringAfterLast('/')
    val label =
        if (parentName.isEmpty()) {
            stringResource(R.string.library_folder_root)
        } else {
            stringResource(R.string.library_folder_up, parentName)
        }
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onUp),
    )
}

// Пути папок оканчиваются на «/», так что с ключом строки «вверх» не совпадут.
private const val UP_KEY = ".."
