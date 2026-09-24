package io.github.puflik.plinth.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.puflik.plinth.R
import io.github.puflik.plinth.queue.QueueItem
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
    var dragged by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val target = dragged?.let { QueueReorder.target(it, offset, rowHeight, upcoming.size) }
    LazyColumn(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        // Один трек может стоять в очереди дважды — ключ по месту и треку.
        itemsIndexed(upcoming, key = { index, item -> "$index:${item.source.key}" }) { index, item ->
            val held = dragged == index
            val shift = dragged?.let { from -> QueueReorder.shift(index, from, checkNotNull(target)) } ?: 0
            QueueRow(
                item = item,
                actions =
                    RowActions(
                        onRemove = { edits.onRemove(index, item) },
                        onMoveUp = { if (index > 0) edits.onMove(index, index - 1, item) },
                        onMoveDown = { if (index < upcoming.lastIndex) edits.onMove(index, index + 1, item) },
                        onDragStart = {
                            dragged = index
                            offset = 0f
                        },
                        onDrag = { offset += it },
                        onDragEnd = {
                            val to = QueueReorder.target(index, offset, rowHeight, upcoming.size)
                            dragged = null
                            offset = 0f
                            if (to != index) edits.onMove(index, to, item)
                        },
                        onDragCancel = {
                            dragged = null
                            offset = 0f
                        },
                    ),
                modifier =
                    Modifier
                        .zIndex(if (held) 1f else 0f)
                        .graphicsLayer { translationY = if (held) offset else shift * rowHeight },
            )
        }
    }
}

/** Что можно сделать со строкой очереди. */
private class RowActions(
    val onRemove: () -> Unit,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
    val onDragStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onDragEnd: () -> Unit,
    val onDragCancel: () -> Unit,
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
                    .accessibilityActions(actions),
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
            DragHandle(actions)
        }
    }
}

/** Для TalkBack: «выше», «ниже», «убрать» вместо перетаскивания и свайпа. */
@Composable
private fun Modifier.accessibilityActions(actions: RowActions): Modifier {
    val up = stringResource(R.string.player_queue_move_up)
    val down = stringResource(R.string.player_queue_move_down)
    val remove = stringResource(R.string.player_queue_remove)
    return semantics {
        customActions =
            listOf(
                CustomAccessibilityAction(up) {
                    actions.onMoveUp()
                    true
                },
                CustomAccessibilityAction(down) {
                    actions.onMoveDown()
                    true
                },
                CustomAccessibilityAction(remove) {
                    actions.onRemove()
                    true
                },
            )
    }
}

/**
 * Ручка перетаскивания; вбок её свайп не ловит — остаётся строке. Жест живёт
 * дольше одной перерисовки (каждый сдвиг пальца перерисовывает список), поэтому
 * ключ у него постоянный, а команды берутся свежие.
 */
@Composable
private fun DragHandle(actions: RowActions) {
    val current by rememberUpdatedState(actions)
    Icon(
        painter = painterResource(R.drawable.ic_drag_handle),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .size(HANDLE_SIZE)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { current.onDragStart() },
                        onDragEnd = { current.onDragEnd() },
                        onDragCancel = { current.onDragCancel() },
                    ) { change, dy ->
                        change.consume()
                        current.onDrag(dy)
                    }
                }.padding(12.dp),
    )
}

@Composable
private fun RemoveBackground() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            repeat(2) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/** Высота строки: все строки одинаковые, по ней считается перетаскивание. */
private val QUEUE_ROW = 56.dp
private val HANDLE_SIZE = 48.dp
