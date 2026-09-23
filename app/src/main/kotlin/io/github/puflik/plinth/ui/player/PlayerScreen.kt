package io.github.puflik.plinth.ui.player

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.audio.engine.PlaybackError
import io.github.puflik.plinth.ui.common.BackTopBar
import io.github.puflik.plinth.ui.common.formatTime
import kotlin.time.Duration

/**
 * Плеер (C4.1): то, что открыли в библиотеке или через SAF, — название,
 * пауза, перемотка. Экран открывается поверх вкладок, назад — в библиотеку.
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(title = stringResource(R.string.player_title), onBack = onBack)
        PlayerContent(
            state = state,
            onPlayPause = viewModel::onPlayPause,
            onSeek = viewModel::onSeek,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlayerContent(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onSeek: (Duration) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.title ?: stringResource(R.string.player_nothing_open),
            style = MaterialTheme.typography.titleMedium,
        )
        state.error?.let {
            Text(
                text = stringResource(it.messageRes),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.canControl) {
            SeekBar(state, onSeek)
            Button(onClick = onPlayPause) {
                Text(stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play))
            }
        }
    }
}

/** Полоса перемотки: пока палец на ней — показывает его позицию, отпустил — перематывает. */
@Composable
private fun SeekBar(
    state: PlayerUiState,
    onSeek: (Duration) -> Unit,
) {
    val duration = state.duration?.takeIf { it.isPositive() }
    var dragged by remember { mutableStateOf<Float?>(null) }
    val fraction = dragged ?: duration?.let { (state.position / it).toFloat().coerceIn(0f, 1f) } ?: 0f
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                val target = dragged
                dragged = null
                if (target != null && duration != null) onSeek(duration * target.toDouble())
            },
            enabled = duration != null,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(duration?.times(fraction.toDouble()) ?: state.position))
            Text(duration?.let(::formatTime) ?: NO_TIME)
        }
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
