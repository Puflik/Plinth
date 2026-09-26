package io.github.puflik.plinth.ui.library.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.model.PlaylistTrack
import io.github.puflik.plinth.ui.common.BackTopBar
import io.github.puflik.plinth.ui.common.DragHandle
import io.github.puflik.plinth.ui.common.RemoveBackground
import io.github.puflik.plinth.ui.common.ReorderState
import io.github.puflik.plinth.ui.common.RowDrag
import io.github.puflik.plinth.ui.common.reorderActions
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.components.TrackRow
import io.github.puflik.plinth.ui.library.components.rememberTrackActionFeedback
import kotlinx.coroutines.launch

/**
 * Свой плейлист (D4b): треки по порядку. Касание включает плейлист с этой
 * строки; строку переставляют, потянув за ручку справа, и убирают свайпом
 * вбок, как в очереди. TalkBack не тянет и не свайпает — у строки есть
 * действия «выше», «ниже», «убрать из плейлиста».
 */
@Composable
fun PlaylistScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val feedback = rememberTrackActionFeedback(onOpenPlayer)
    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(title = state.name, onBack = onBack)
        when {
            !state.loaded -> Unit
            state.tracks.isEmpty() -> EmptyList(R.string.playlist_empty)
            else ->
                PlaylistRows(
                    tracks = state.tracks,
                    onAction = { index, action ->
                        viewModel.onTrack(index, action)
                        feedback(action)
                    },
                    onMove = viewModel::move,
                    onRemove = viewModel::remove,
                )
        }
    }
}

/** Строки плейлиста; место при перетаскивании считает [ReorderState] по высоте строки. */
@Composable
private fun PlaylistRows(
    tracks: List<PlaylistTrack>,
    onAction: (Int, TrackAction) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val reorder = remember { ReorderState() }
    // Строки одной высоты: её знает любая измеренная строка.
    var rowHeight by remember { mutableFloatStateOf(1f) }
    LazyColumn {
        itemsIndexed(tracks, key = { _, row -> row.entry.value }) { index, row ->
            PlaylistRow(
                row = row,
                onAction = { onAction(index, it) },
                onRemove = { onRemove(index) },
                drag =
                    RowDrag(
                        onStart = { reorder.start(index) },
                        onDrag = reorder::drag,
                        onEnd = { reorder.end(rowHeight, tracks.size)?.let { (from, to) -> onMove(from, to) } },
                        onCancel = reorder::cancel,
                    ),
                accessibility =
                    Modifier.reorderActions(
                        up = stringResource(R.string.player_queue_move_up),
                        down = stringResource(R.string.player_queue_move_down),
                        removeLabel = stringResource(R.string.playlist_remove),
                        onMoveUp = { if (index > 0) onMove(index, index - 1) },
                        onMoveDown = { if (index < tracks.lastIndex) onMove(index, index + 1) },
                        onRemove = { onRemove(index) },
                    ),
                modifier =
                    Modifier
                        .onSizeChanged { rowHeight = it.height.toFloat().coerceAtLeast(1f) }
                        .zIndex(if (reorder.dragged == index) 1f else 0f)
                        .graphicsLayer { translationY = reorder.translation(index, rowHeight, tracks.size) },
            )
        }
    }
}

@Composable
private fun PlaylistRow(
    row: PlaylistTrack,
    onAction: (TrackAction) -> Unit,
    onRemove: () -> Unit,
    drag: RowDrag,
    accessibility: Modifier,
    modifier: Modifier,
) {
    val dismiss = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = dismiss,
        backgroundContent = { RemoveBackground() },
        modifier = modifier,
        onDismiss = {
            onRemove()
            // Строка ушла из списка — состояние свайпа уходит с ней; осталась — вернётся на место.
            scope.launch { dismiss.reset() }
        },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .then(accessibility),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackRow(track = row.track, onAction = onAction, modifier = Modifier.weight(1f))
            DragHandle(drag)
        }
    }
}
