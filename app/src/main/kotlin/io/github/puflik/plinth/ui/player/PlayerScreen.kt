package io.github.puflik.plinth.ui.player

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.audio.engine.PlaybackError
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.queue.RepeatMode
import io.github.puflik.plinth.ui.common.formatRemaining
import io.github.puflik.plinth.ui.common.formatTime
import kotlin.time.Duration

/**
 * Полноэкранный плеер, слой D1 (E1, раскладка 12.13 плана): свернуть,
 * обложка, название и исполнитель, прогресс, shuffle · ⏮ · ▶ · ⏭ · repeat.
 * Под ним — что дальше, пока у очереди нет своей панели (E3.2).
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "top") {
            IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                Icon(
                    painterResource(R.drawable.ic_collapse),
                    contentDescription = stringResource(R.string.player_collapse),
                )
            }
        }
        item(key = "core") {
            PlayerCore(
                state = state,
                controls =
                    PlayerControls(
                        onPlayPause = viewModel::onPlayPause,
                        onSeek = viewModel::onSeek,
                        onPrevious = viewModel::onPrevious,
                        onNext = viewModel::onNext,
                        onShuffle = viewModel::onShuffle,
                        onRepeat = viewModel::onRepeat,
                    ),
            )
        }
        upNext(state.upcoming)
    }
}

/** Команды плеера одним значением: раскладке их нужно шесть. */
private class PlayerControls(
    val onPlayPause: () -> Unit,
    val onSeek: (Duration) -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onShuffle: () -> Unit,
    val onRepeat: () -> Unit,
)

@Composable
private fun PlayerCore(
    state: PlayerUiState,
    controls: PlayerControls,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Место под обложку (E2) — пока плашка.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(ARTWORK_WIDTH)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = state.title ?: stringResource(R.string.player_nothing_open),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            state.artist?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge, maxLines = 1) }
        }
        state.error?.let {
            Text(
                text = stringResource(it.messageRes),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SeekBar(state, controls.onSeek)
        Transport(state, controls)
    }
}

/**
 * Полоса перемотки: пока палец на ней — показывает его позицию, отпустил —
 * перематывает. Касание по времени переключает «прошло ⇄ осталось» справа.
 */
@Composable
private fun SeekBar(
    state: PlayerUiState,
    onSeek: (Duration) -> Unit,
) {
    val duration = state.duration?.takeIf { it.isPositive() }
    var dragged by remember { mutableStateOf<Float?>(null) }
    var remaining by rememberSaveable { mutableStateOf(false) }
    val fraction = dragged ?: duration?.let { (state.position / it).toFloat().coerceIn(0f, 1f) } ?: 0f
    val shown = duration?.times(fraction.toDouble()) ?: state.position
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                val target = dragged
                dragged = null
                if (target != null && duration != null) onSeek(duration * target.toDouble())
            },
            enabled = state.canControl && duration != null,
        )
        val toggleLabel = stringResource(R.string.player_time_toggle)
        val toggle = Modifier.clickable(onClickLabel = toggleLabel) { remaining = !remaining }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shown), modifier = toggle)
            Text(
                text =
                    when {
                        duration == null -> NO_TIME
                        remaining -> formatRemaining(shown, duration)
                        else -> formatTime(duration)
                    },
                modifier = toggle,
            )
        }
    }
}

@Composable
private fun Transport(
    state: PlayerUiState,
    controls: PlayerControls,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shuffleLabel = if (state.shuffle) R.string.player_shuffle_on else R.string.player_shuffle_off
        TransportButton(R.drawable.ic_shuffle, shuffleLabel, active = state.shuffle, onClick = controls.onShuffle)
        TransportButton(R.drawable.ic_skip_previous, R.string.player_previous, onClick = controls.onPrevious)
        FilledIconButton(
            onClick = controls.onPlayPause,
            enabled = state.canControl,
            modifier = Modifier.size(PLAY_SIZE),
        ) {
            Icon(
                painter = painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription =
                    stringResource(
                        if (state.isPlaying) R.string.player_pause else R.string.player_play,
                    ),
            )
        }
        TransportButton(R.drawable.ic_skip_next, R.string.player_next, onClick = controls.onNext)
        TransportButton(
            icon = if (state.repeat == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
            label =
                when (state.repeat) {
                    RepeatMode.OFF -> R.string.player_repeat_off
                    RepeatMode.ALL -> R.string.player_repeat_all
                    RepeatMode.ONE -> R.string.player_repeat_one
                },
            active = state.repeat != RepeatMode.OFF,
            onClick = controls.onRepeat,
        )
    }
}

/** Кнопка транспорта; включённый режим (shuffle, повтор) — цветом акцента. */
@Composable
private fun TransportButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(label),
            tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

/** Что сыграет дальше (E3.2 без правки): ручной блок, затем остаток контекста. */
private fun LazyListScope.upNext(upcoming: List<QueueItem>) {
    if (upcoming.isEmpty()) return
    item(key = "up-next") {
        Text(
            text = stringResource(R.string.player_up_next),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
        )
    }
    // Один трек может стоять в очереди дважды — ключ по месту, а не по треку.
    itemsIndexed(upcoming) { _, item ->
        ListItem(
            headlineContent = { Text(item.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = item.artist?.let { artist -> { Text(artist, maxLines = 1) } },
        )
    }
}

private val PlaybackError.messageRes: Int
    @StringRes get() =
        when (this) {
            is PlaybackError.SourceUnavailable -> R.string.player_error_unavailable
            is PlaybackError.UnsupportedFormat -> R.string.player_error_format
            is PlaybackError.Network -> R.string.player_error_network
            is PlaybackError.Unknown -> R.string.player_error_unknown
        }

private const val NO_TIME = "--:--"
private const val ARTWORK_WIDTH = 0.8f
private val PLAY_SIZE = 64.dp
