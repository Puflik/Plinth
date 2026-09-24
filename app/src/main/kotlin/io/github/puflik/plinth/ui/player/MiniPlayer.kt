package io.github.puflik.plinth.ui.player

import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R
import io.github.puflik.plinth.artwork.ArtworkSize
import io.github.puflik.plinth.ui.common.ArtworkImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Мини-плеер, слой D0 (E3, 12.10): обложка, название и исполнитель,
 * play/pause, тонкая полоса прогресса. Касание и свайп вверх открывают плеер,
 * свайп вбок — по таблице жестов ([PlayerGesture.MINI_PLAYER_SWIPE], умолчание —
 * смена трека); пока палец ведёт, карточка идёт
 * за ним и возвращается на место, когда его отпустили. TalkBack свайпов не
 * делает — «следующий» и «предыдущий» у него в действиях элемента.
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    actions: MiniPlayerActions,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = ELEVATION, modifier = modifier.fillMaxWidth()) {
        Column {
            LinearProgressIndicator(
                progress = { state.progressFraction },
                modifier = Modifier.fillMaxWidth().height(PROGRESS_HEIGHT),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .miniPlayerGestures(actions)
                        .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackSummary(state, Modifier.weight(1f))
                IconButton(onClick = actions.onPlayPause, enabled = state.canControl) {
                    Icon(
                        painter = painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription =
                            stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                    )
                }
            }
        }
    }
}

/** Обложка, название и исполнитель. */
@Composable
private fun TrackSummary(
    state: PlayerUiState,
    modifier: Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtworkImage(
            uri = state.artworkUri,
            size = ArtworkSize.THUMBNAIL,
            placeholder = R.drawable.ic_music_note,
            modifier = Modifier.size(ARTWORK_SIZE).clip(RoundedCornerShape(6.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            state.artist?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Касание, свайпы и действия TalkBack. Сдвиг вбок виден сразу — строка едет
 * за пальцем, — а решение принимается, когда палец отпущен.
 */
@Composable
private fun Modifier.miniPlayerGestures(actions: MiniPlayerActions): Modifier {
    val current by rememberUpdatedState(actions)
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    val scope = rememberCoroutineScope()
    var shift by remember { mutableFloatStateOf(0f) }
    val expandLabel = stringResource(R.string.player_expand)
    val nextLabel = stringResource(R.string.player_next)
    val previousLabel = stringResource(R.string.player_previous)
    val settle = { scope.launch { animate(shift, 0f) { value, _ -> shift = value } } }
    return this
        .clickable(onClickLabel = expandLabel) { current.onExpand() }
        .pointerInput(threshold) {
            var dx = 0f
            var dy = 0f
            detectDragGestures(
                onDragStart = {
                    dx = 0f
                    dy = 0f
                },
                onDragEnd = {
                    when (val swipe = Swipe.of(dx, dy, threshold)) {
                        Swipe.UP -> current.onExpand()
                        Swipe.LEFT, Swipe.RIGHT -> {
                            val action = PlayerGesture.MINI_PLAYER_SWIPE.default
                            when (action.command(swipe.forward, PlayerPanel.COVER)) {
                                PlayerCommand.Next -> current.onNext()
                                PlayerCommand.Previous -> current.onPrevious()
                                else -> Unit
                            }
                        }
                        Swipe.DOWN, null -> Unit
                    }
                    settle()
                },
                onDragCancel = { settle() },
            ) { change, drag ->
                change.consume()
                dx += drag.x
                dy += drag.y
                shift = dx
            }
        }.semantics {
            customActions =
                listOf(
                    CustomAccessibilityAction(nextLabel) {
                        current.onNext()
                        true
                    },
                    CustomAccessibilityAction(previousLabel) {
                        current.onPrevious()
                        true
                    },
                )
        }.offset { IntOffset(shift.roundToInt(), 0) }
}

private val ELEVATION = 3.dp
private val PROGRESS_HEIGHT = 2.dp
private val ROW_HEIGHT = 64.dp
private val ARTWORK_SIZE = 48.dp

/** Сдвиг, после которого движение пальца — свайп, а не неточное касание. */
private val SWIPE_THRESHOLD = 56.dp
