package io.github.puflik.plinth.ui.library.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.model.Artist

/** Исполнители по имени (C4.1); касание открывает экран исполнителя (E5). */
@Composable
fun ArtistsTab(
    artists: List<Artist>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(artists, key = Artist::name) { artist ->
            val albums = pluralStringResource(R.plurals.library_album_count, artist.albumCount, artist.albumCount)
            val tracks = pluralStringResource(R.plurals.library_track_count, artist.trackCount, artist.trackCount)
            ListItem(
                headlineContent = { Text(artist.name) },
                supportingContent = { Text(stringResource(R.string.library_artist_summary, albums, tracks)) },
                modifier = Modifier.clickable { onOpen(artist.name) },
            )
        }
    }
}
