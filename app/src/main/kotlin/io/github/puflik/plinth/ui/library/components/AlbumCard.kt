package io.github.puflik.plinth.ui.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R
import io.github.puflik.plinth.artwork.ArtworkSize
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.ui.common.ArtworkImage

/** Карточка альбома (C4.2): обложка первого трека (E2), название, исполнитель, число треков. */
@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        ArtworkImage(
            uri = album.coverTrackUri,
            size = ArtworkSize.THUMBNAIL,
            placeholder = R.drawable.ic_album,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = album.artist ?: stringResource(R.string.library_unknown_artist),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.library_track_count, album.trackCount, album.trackCount),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
