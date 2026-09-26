package io.github.puflik.plinth.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ui.player.QueueReorder

// Перестановка строк списка за ручку и удаление свайпом — у очереди (E4) и
// плейлиста (D4b). Место считает QueueReorder: строки одной высоты.

/** Какую строку держат и на сколько пикселей сдвинули. */
@Stable
class ReorderState {
    var dragged by mutableStateOf<Int?>(null)
        private set
    private var offset by mutableFloatStateOf(0f)

    fun start(index: Int) {
        dragged = index
        offset = 0f
    }

    fun drag(dy: Float) {
        offset += dy
    }

    /** Строку отпустили: откуда и куда она встала; `null` — никуда не тянули или вернули на место. */
    fun end(
        rowHeight: Float,
        count: Int,
    ): Pair<Int, Int>? {
        val from = dragged ?: return null
        val to = QueueReorder.target(from, offset, rowHeight, count)
        cancel()
        return (from to to).takeIf { from != to }
    }

    fun cancel() {
        dragged = null
        offset = 0f
    }

    /** Сдвиг строки [index] по вертикали: держат — за пальцем, иначе уступает место на строку. */
    fun translation(
        index: Int,
        rowHeight: Float,
        count: Int,
    ): Float {
        val from = dragged ?: return 0f
        val target = QueueReorder.target(from, offset, rowHeight, count)
        return if (index == from) offset else QueueReorder.shift(index, from, target) * rowHeight
    }
}

/** Жест ручки: начало, сдвиг пальца, конец и отмена. */
class RowDrag(
    val onStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * Ручка перетаскивания; вбок её свайп не ловит — остаётся строке. Жест живёт
 * дольше одной перерисовки (каждый сдвиг пальца перерисовывает список), поэтому
 * ключ у него постоянный, а команды берутся свежие.
 */
@Composable
fun DragHandle(
    drag: RowDrag,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(drag)
    Icon(
        painter = painterResource(R.drawable.ic_drag_handle),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .size(HANDLE_SIZE)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { current.onStart() },
                        onDragEnd = { current.onEnd() },
                        onDragCancel = { current.onCancel() },
                    ) { change, dy ->
                        change.consume()
                        current.onDrag(dy)
                    }
                }.padding(12.dp),
    )
}

/** Фон строки под свайпом: убрать. */
@Composable
fun RemoveBackground() {
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

/**
 * Для TalkBack: «выше», «ниже» и [removeLabel] вместо перетаскивания и
 * свайпа. [onMoveUp] и [onMoveDown] у крайних строк ничего не делают.
 */
fun Modifier.reorderActions(
    up: String,
    down: String,
    removeLabel: String,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
): Modifier =
    semantics {
        customActions =
            listOf(
                CustomAccessibilityAction(up) {
                    onMoveUp()
                    true
                },
                CustomAccessibilityAction(down) {
                    onMoveDown()
                    true
                },
                CustomAccessibilityAction(removeLabel) {
                    onRemove()
                    true
                },
            )
    }

private val HANDLE_SIZE = 48.dp
