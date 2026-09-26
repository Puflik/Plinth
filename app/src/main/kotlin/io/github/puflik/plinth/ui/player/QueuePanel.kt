package io.github.puflik.plinth.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.puflik.plinth.R
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.ui.common.DragHandle
import io.github.puflik.plinth.ui.common.RemoveBackground
import io.github.puflik.plinth.ui.common.ReorderState
import io.github.puflik.plinth.ui.common.RowDrag
import io.github.puflik.plinth.ui.common.reorderActions
import kotlinx.coroutines.launch

/**
 * Панель «Очередь» (E4, D2): что сыграет дальше, следующий трек сверху.
 * Строку переставляют, потянув за ручку справа, и убирают свайпом вбок.
 * TalkBack не тянет и не свайпает — у строки есть действия «выше», «ниже»,
 * «убрать».
 *
 * Строка несёт с собой свой трек: если очередь сдвинулась, пока палец был на
 * экране, `PlaybackController` жест пропустит.
 */
@Composable
fun QueuePanel(
    upcoming: List<QueueItem>,
    edits: QueueEdits,
    modifier: Modifier = Modifier,
) {
    if (upcoming.isEmpty()) {
        PanelPlaceholder(R.string.player_queue_empty, modifier)
        return
    }
    val rowHeight = with(LocalDensity.current) { QUEUE_ROW.toPx() }
    val reorder = remember { ReorderState() }
    LazyColumn(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        // Один трек может стоять в очереди дважды — ключ по месту и треку.
        itemsIndexed(upcoming, key = { index, item -> "$index:${item.source.key}" }) { index, item ->
            QueueRow(
                item = item,
                actions =
                    RowActions(
                        onRemove = { edits.onRemove(index, item) },
                        onMoveUp = { if (index > 0) edits.onMove(index, index - 1, item) },
                        onMoveDown = { if (index < upcoming.lastIndex) edits.onMove(index, index + 1, item) },
                        drag =
                            RowDrag(
                                onStart = { reorder.start(index) },
                                onDrag = reorder::drag,
                                onEnd = {
                                    reorder.end(rowHeight, upcoming.size)?.let { (from, to) ->
                                        edits.onMove(from, to, item)
                                    }
                                },
                                onCancel = reorder::cancel,
                            ),
                    ),
                modifier =
                    Modifier
                        .zIndex(if (reorder.dragged == index) 1f else 0f)
                        .graphicsLayer { translationY = reorder.translation(index, rowHeight, upcoming.size) },
            )
        }
    }
}

/** Что можно сделать со строкой очереди. */
private class RowActions(
    val onRemove: () -> Unit,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
    val drag: RowDrag,
)

@Composable
private fun QueueRow(
    item: QueueItem,
    actions: RowActions,
    modifier: Modifier,
) {
    val dismiss = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = dismiss,
        backgroundContent = { RemoveBackground() },
        modifier = modifier,
        onDismiss = {
            actions.onRemove()
            // Жест пропущен (очередь сдвинулась) — строка возвращается на место.
            scope.launch { dismiss.reset() }
        },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(QUEUE_ROW)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 16.dp)
                    .reorderActions(
                        up = stringResource(R.string.player_queue_move_up),
                        down = stringResource(R.string.player_queue_move_down),
                        removeLabel = stringResource(R.string.player_queue_remove),
                        onMoveUp = actions.onMoveUp,
                        onMoveDown = actions.onMoveDown,
                        onRemove = actions.onRemove,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.artist?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DragHandle(actions.drag)
        }
    }
}

/** Высота строки: все строки одинаковые, по ней считается перетаскивание. */
private val QUEUE_ROW = 56.dp
