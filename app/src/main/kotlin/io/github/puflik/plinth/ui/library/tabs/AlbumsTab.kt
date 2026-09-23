package io.github.puflik.plinth.ui.library.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.ui.library.components.AlbumCard

/** Сетка альбомов (C4.1). Альбом узнаётся по названию и владельцу — это и ключ. */
@Composable
fun AlbumsTab(
    albums: List<Album>,
    onOpen: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 152.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        items(albums, key = { it.title to it.artist }) { album -> AlbumCard(album, onClick = { onOpen(album) }) }
    }
}
