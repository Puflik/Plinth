package io.github.puflik.plinth.ui.library.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.ui.common.formatTime

/**
 * Строка трека (C4.2): название, исполнитель и альбом, длительность.
 * Одна на все списки — треки, папки, альбом, а потом и поиск.
 */
@Composable
fun TrackRow(
    track: LibraryTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val artist = track.artist ?: stringResource(R.string.library_unknown_artist)
    ListItem(
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOfNotNull(artist, track.album).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = { Text(formatTime(track.duration)) },
        modifier = modifier.clickable(onClick = onClick),
    )
}
