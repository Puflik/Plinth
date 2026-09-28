package io.github.puflik.plinth.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ui.common.BackTopBar
import io.github.puflik.plinth.ui.library.components.TrackRow
import io.github.puflik.plinth.ui.library.components.rememberTrackActionFeedback
import io.github.puflik.plinth.ui.library.playlists.PlaylistPicker

/**
 * Альбом провайдера (E3, ответ автора): как локальный — «Исполнитель · год ·
 * N треков», «Играть», треки строками с облаком; внизу — откуда он. Пока
 * сеть отвечает — индикатор, без сети — «Нет сети» и «Повторить».
 */
@Composable
fun OnlineAlbumScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnlineAlbumViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val feedback = rememberTrackActionFeedback(onOpenPlayer)
    val album = viewModel.album
    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(title = album.title, onBack = onBack)
        LazyColumn(modifier = Modifier.weight(1f)) {
            item(key = HEADER_KEY) {
                Header(album, state.tracks.size, playable = state.tracks.isNotEmpty()) {
                    viewModel.onPlay()
                    onOpenPlayer()
                }
            }
            when {
                state.loading -> item(key = STATUS_KEY) { Loading() }
                state.problem != null ->
                    item(key = STATUS_KEY) { Problem(checkNotNull(state.problem), viewModel::onRetry) }
            }
            itemsIndexed(state.tracks, key = { _, track -> track.track.external }) { index, track ->
                TrackRow(
                    label = track.label,
                    onAction = { action ->
                        viewModel.onTrack(index, action)
                        feedback(action)
                    },
                ) { dismiss ->
                    PlaylistPicker(
                        onAdd = { viewModel.onAddToPlaylist(index, it.id) },
                        onAddToNew = { viewModel.onAddToNewPlaylist(index, it) },
                        onDismiss = dismiss,
                    )
                }
            }
            item(key = SOURCE_KEY) {
                Text(
                    text = stringResource(R.string.online_album_source, providerName(album.provider)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(
    album: OnlineAlbumHeader,
    count: Int,
    playable: Boolean,
    onPlay: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val artist = album.artist ?: stringResource(R.string.library_unknown_artist)
        val tracks = pluralStringResource(R.plurals.library_track_count, count, count).takeIf { count > 0 }
        Text(
            text = listOfNotNull(artist, album.year?.toString(), tracks).joinToString(" · "),
            style = MaterialTheme.typography.titleSmall,
        )
        Button(onClick = onPlay, enabled = playable) { Text(stringResource(R.string.online_album_play)) }
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun Problem(
    problem: OnlineProblem,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text =
                stringResource(
                    if (problem ==
                        OnlineProblem.NO_NETWORK
                    ) {
                        R.string.online_no_network
                    } else {
                        R.string.online_album_unavailable
                    },
                ),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.online_retry)) }
    }
}

private const val HEADER_KEY = "header"
private const val STATUS_KEY = "status"
private const val SOURCE_KEY = "source"
