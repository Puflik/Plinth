package io.github.puflik.plinth.ui.library.artist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.ui.common.BackTopBar
import io.github.puflik.plinth.ui.library.components.AlbumCard
import io.github.puflik.plinth.ui.library.components.TrackRow
import io.github.puflik.plinth.ui.library.components.rememberTrackActionFeedback

/**
 * Минимальный экран исполнителя (E5): лента его альбомов и его треки.
 * Касание трека включает треки исполнителя с него и открывает плеер.
 */
@Composable
fun ArtistScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val feedback = rememberTrackActionFeedback(onOpenPlayer)
    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(title = viewModel.artist, onBack = onBack)
        LazyColumn {
            if (state.albums.isNotEmpty()) {
                item(key = ALBUMS_KEY) {
                    SectionTitle(R.string.library_tab_albums)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.albums, key = { it.title to it.artist }) { album ->
                            AlbumCard(album, onClick = { onOpenAlbum(album) }, modifier = Modifier.width(ALBUM_WIDTH))
                        }
                    }
                }
            }
            item(key = TRACKS_KEY) { SectionTitle(R.string.library_tab_tracks) }
            items(state.tracks, key = { it.id.value }) { track ->
                TrackRow(
                    track = track,
                    onAction = { action ->
                        viewModel.onTrack(track, action)
                        feedback(action)
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

// Ключи треков — числа, со строками заголовков не совпадут.
private const val ALBUMS_KEY = "albums"
private const val TRACKS_KEY = "tracks"
private val ALBUM_WIDTH = 160.dp
