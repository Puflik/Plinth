package io.github.puflik.plinth.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.audio.engine.PlaybackError
import kotlin.time.Duration

/**
 * Тонкий плеер первой вертикали (шаг 4): выбрать файл, слушать, ставить на
 * паузу, перематывать. Стоит на месте библиотеки, пока её нет (эпик C).
 */
@Composable
fun FilePlayerScreen(
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                keepAccess(context, uri)
                viewModel.onFilePicked(uri.toString(), displayName(context, uri))
            }
        }
    FilePlayerContent(
        state = state,
        onOpen = { picker.launch(arrayOf(AUDIO_MIME)) },
        onPlayPause = viewModel::onPlayPause,
        onSeek = viewModel::onSeek,
        modifier = modifier,
    )
}

@Composable
private fun FilePlayerContent(
    state: PlayerUiState,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Duration) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
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
        OutlinedButton(onClick = onOpen) {
            Text(stringResource(R.string.player_open_file))
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

private fun formatTime(time: Duration): String {
    val seconds = time.inWholeSeconds
    return "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

/**
 * Сохраняет доступ к файлу за пределами жизни процесса: без этого после
 * перезапуска `content://` станет недоступен, и восстановление позиции
 * упрётся в `SourceUnavailable`. Не каждый провайдер SAF даёт постоянный
 * доступ — тогда играем с временным, пока жив процесс.
 */
private fun keepAccess(
    context: Context,
    uri: Uri,
) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun displayName(
    context: Context,
    uri: Uri,
): String? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

private const val AUDIO_MIME = "audio/*"
private const val NO_TIME = "--:--"
private const val SECONDS_PER_MINUTE = 60
