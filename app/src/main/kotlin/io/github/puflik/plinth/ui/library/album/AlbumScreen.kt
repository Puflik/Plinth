package io.github.puflik.plinth.ui.library.album

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.ui.common.BackTopBar
import io.github.puflik.plinth.ui.library.components.TrackRow

/**
 * Минимальный экран альбома (C4.1): исполнитель, число треков и сами треки
 * по диску и номеру. Касание трека включает его и открывает плеер.
 */
@Composable
fun AlbumScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumViewModel = hiltViewModel(),
) {
    val tracks by viewModel.tracks.collectAsState()
    val album = viewModel.album
    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(title = album.title, onBack = onBack)
        LazyColumn {
            item(key = HEADER_KEY) {
                val artist = album.artist ?: stringResource(R.string.library_unknown_artist)
                val count = pluralStringResource(R.plurals.library_track_count, tracks.size, tracks.size)
                Text(
                    text = stringResource(R.string.library_artist_summary, artist, count),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(tracks, key = LibraryTrack::id) { track ->
                TrackRow(
                    track = track,
                    onClick = {
                        viewModel.play(track)
                        onOpenPlayer()
                    },
                )
            }
        }
    }
}

// Ключи треков — числа, со строкой заголовка не совпадут.
private const val HEADER_KEY = "header"
