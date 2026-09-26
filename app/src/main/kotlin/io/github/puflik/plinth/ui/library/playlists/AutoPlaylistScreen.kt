package io.github.puflik.plinth.ui.library.playlists

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.ui.common.BackTopBar
import io.github.puflik.plinth.ui.library.components.TrackRow
import io.github.puflik.plinth.ui.library.components.rememberTrackActionFeedback

/**
 * «Любимое» или «Недавнее» (D4b): треки списка; касание включает список с
 * этого трека и открывает плеер.
 */
@Composable
fun AutoPlaylistScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AutoPlaylistViewModel = hiltViewModel(),
) {
    val loaded by viewModel.tracks.collectAsState()
    val feedback = rememberTrackActionFeedback(onOpenPlayer)
    val kind = viewModel.kind
    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(title = stringResource(kind.labelRes), onBack = onBack)
        val tracks = loaded ?: return@Column
        if (tracks.isEmpty()) {
            EmptyList(kind.emptyRes)
            return@Column
        }
        LazyColumn {
            items(tracks, key = { it.id.value }) { track ->
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

/** Пустой список: что здесь появится и откуда. */
@Composable
internal fun EmptyList(
    @StringRes text: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 24.dp, vertical = 16.dp),
    )
}
