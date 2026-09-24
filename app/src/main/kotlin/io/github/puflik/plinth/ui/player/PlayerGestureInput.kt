package io.github.puflik.plinth.ui.player

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Свайп вверх и вниз по экрану плеера (E5, 12.11). Прокрутка очереди,
 * ползунок и ручки строк забирают свой жест сами — сюда доходит только
 * свободный свайп.
 */
@Composable
fun Modifier.playerSwipes(onSwipe: (Swipe) -> Unit): Modifier {
    val current by rememberUpdatedState(onSwipe)
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    return pointerInput(threshold) {
        var dy = 0f
        detectVerticalDragGestures(
            onDragStart = { dy = 0f },
            onDragEnd = { Swipe.of(0f, dy, threshold)?.let(current) },
        ) { _, drag -> dy += drag }
    }
}

/** Что обложка делает на касание, двойной тап по половине и свайп вбок. */
class CoverActions(
    val onTap: () -> Unit,
    val onDoubleTap: (forward: Boolean) -> Unit,
    val onSwipe: (forward: Boolean) -> Unit,
)

/**
 * Жесты обложки (E5, 12.11): касание, двойной тап — по левой половине назад,
 * по правой вперёд, — и свайп вбок, если [swipes]. Одиночное касание ждёт,
 * не будет ли второго, — так двойной тап не переключает панель. TalkBack
 * получает то же действиями элемента.
 */
@Composable
fun Modifier.coverGestures(
    swipes: Boolean,
    actions: CoverActions,
): Modifier {
    val current by rememberUpdatedState(actions)
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    val tapLabel = stringResource(R.string.player_cover_tap)
    val backLabel = stringResource(R.string.player_seek_back)
    val forwardLabel = stringResource(R.string.player_seek_forward)
    val sideways =
        if (swipes) {
            Modifier.pointerInput(threshold) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = { Swipe.of(dx, 0f, threshold)?.let { current.onSwipe(it.forward) } },
                ) { change, drag ->
                    change.consume()
                    dx += drag
                }
            }
        } else {
            Modifier
        }
    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { current.onTap() },
                onDoubleTap = { offset -> current.onDoubleTap(offset.x >= size.width / 2) },
            )
        }.then(sideways)
        .semantics {
            onClick(tapLabel) {
                current.onTap()
                true
            }
            customActions =
                listOf(
                    CustomAccessibilityAction(backLabel) {
                        current.onDoubleTap(false)
                        true
                    },
                    CustomAccessibilityAction(forwardLabel) {
                        current.onDoubleTap(true)
                        true
                    },
                )
        }
}

/**
 * Кнопка ⏮ или ⏭: касание — смена трека, удержание — [onHold] раз в такт,
 * пока палец на кнопке (12.11: быстрая перемотка).
 */
@Composable
fun SkipButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    onClick: () -> Unit,
    onHold: () -> Unit,
) {
    val hold by rememberUpdatedState(onHold)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(holding, pressed) {
        if (!pressed) {
            holding = false
        } else if (holding) {
            while (true) {
                hold()
                delay(HOLD_TICK)
            }
        }
    }
    Box(
        modifier =
            Modifier
                .size(BUTTON_SIZE)
                .clip(CircleShape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    role = Role.Button,
                    onLongClick = { holding = true },
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(icon), contentDescription = stringResource(label))
    }
}

/** Сдвиг, после которого движение пальца — свайп, а не неточное касание. */
private val SWIPE_THRESHOLD = 56.dp
private val BUTTON_SIZE = 48.dp

/** Такт быстрой перемотки: шаг `PlayerCommand.FAST_SEEK_STEP` за такт — около ×30. */
private val HOLD_TICK = 150.milliseconds
