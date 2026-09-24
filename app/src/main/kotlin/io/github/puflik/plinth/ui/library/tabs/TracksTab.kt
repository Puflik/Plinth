package io.github.puflik.plinth.ui.library.tabs

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.components.TrackRow

/** Все треки фонотеки (C4.1, C4.2); список ленивый — строятся только видимые строки. */
@Composable
fun TracksTab(
    tracks: List<LibraryTrack>,
    onAction: (LibraryTrack, TrackAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(tracks, key = LibraryTrack::id) { track -> TrackRow(track, onAction = { onAction(track, it) }) }
    }
}
