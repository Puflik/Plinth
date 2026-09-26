package io.github.puflik.plinth.ui.player

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import io.github.puflik.plinth.artwork.ArtworkSize
import io.github.puflik.plinth.audio.engine.PlaybackError
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.queue.RepeatMode
import io.github.puflik.plinth.ui.common.ArtworkImage
import io.github.puflik.plinth.ui.common.formatRemaining
import io.github.puflik.plinth.ui.common.formatTime
import kotlin.time.Duration

/**
 * Полноэкранный плеер, слой D1 (E1, раскладка 12.13 плана): свернуть,
 * обложка, название и исполнитель, прогресс, shuffle · ⏮ · ▶ · ⏭ · repeat.
 * На месте обложки открываются панели D2 (E4) — точками-табы под ней.
 * Жесты (E5) — по таблице [PlayerGesture]: что велит жест, решает
 * [GestureAction.command], а исполняет экран.
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var panel by rememberSaveable { mutableStateOf(PlayerPanel.COVER) }
    val gesture = { gesture: PlayerGesture, forward: Boolean ->
        viewModel.perform(gesture.default.command(forward, panel), showPanel = { panel = it }, collapse = onBack)
    }
    Column(
        modifier =
            modifier.fillMaxSize().playerSwipes { swipe ->
                when (swipe) {
                    Swipe.UP -> gesture(PlayerGesture.SWIPE_UP, true)
                    Swipe.DOWN -> gesture(PlayerGesture.SWIPE_DOWN, false)
                    Swipe.LEFT, Swipe.RIGHT -> Unit
                }
            },
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_collapse),
                    contentDescription = stringResource(R.string.player_collapse),
                )
            }
            PlayerMenu(state, onOpenAlbum, onOpenArtist)
        }
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
                    onHoldSkip = { forward -> gesture(PlayerGesture.HOLD_SKIP, forward) },
                    onLike = viewModel::onLike,
                ),
            panels =
                PanelControls(
                    panel = panel,
                    onPanel = { panel = it },
                    edits = QueueEdits(onRemove = viewModel::onRemoveUpcoming, onMove = viewModel::onMoveUpcoming),
                    cover =
                        CoverActions(
                            onTap = { gesture(PlayerGesture.COVER_TAP, true) },
                            onDoubleTap = { forward -> gesture(PlayerGesture.DOUBLE_TAP, forward) },
                            onSwipe = { forward -> gesture(PlayerGesture.COVER_SWIPE, forward) },
                        ),
                ),
            onOpenArtist = onOpenArtist,
        )
    }
}

/** Исполняет команду жеста: звук — через модель, панель и сворачивание — на экране. */
private fun PlayerViewModel.perform(
    command: PlayerCommand?,
    showPanel: (PlayerPanel) -> Unit,
    collapse: () -> Unit,
) {
    when (command) {
        PlayerCommand.Next -> onNext()
        PlayerCommand.Previous -> onPrevious()
        is PlayerCommand.Show -> showPanel(command.panel)
        PlayerCommand.Collapse -> collapse()
        PlayerCommand.PlayPause -> onPlayPause()
        is PlayerCommand.SeekBy -> onSeekBy(command.delta)
        null -> Unit
    }
}

/** Меню ⋮, слой D3 (E5): к альбому и к исполнителю — то, что известно о треке. */
@Composable
private fun PlayerMenu(
    state: PlayerUiState,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = state.album != null || state.artist != null) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.library_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.album?.let { album ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_go_album)) },
                    onClick = {
                        expanded = false
                        onOpenAlbum(album)
                    },
                )
            }
            state.artist?.let { artist ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_go_artist)) },
                    onClick = {
                        expanded = false
                        onOpenArtist(artist)
                    },
                )
            }
        }
    }
}

/** Открытая панель и всё, что с ней делают жесты и точки-табы. */
private class PanelControls(
    val panel: PlayerPanel,
    val onPanel: (PlayerPanel) -> Unit,
    val edits: QueueEdits,
    val cover: CoverActions,
)

/**
 * Обложка или открытая панель — в одной и той же квадратной рамке. Жесты
 * обложки работают и на заглушках панелей (касание возвращает обложку), но не
 * в очереди: там свои перетаскивание и свайп строки.
 */
@Composable
private fun PanelArea(
    panels: PanelControls,
    state: PlayerUiState,
    frame: Modifier,
) {
    val panel = panels.panel
    val modifier =
        if (panel == PlayerPanel.QUEUE) frame else frame.coverGestures(panel == PlayerPanel.COVER, panels.cover)
    when (panel) {
        PlayerPanel.COVER ->
            ArtworkImage(
                uri = state.artworkUri,
                size = ArtworkSize.FULL,
                placeholder = R.drawable.ic_music_note,
                modifier = modifier,
            )
        PlayerPanel.QUEUE -> QueuePanel(state.upcoming, panels.edits, modifier)
        PlayerPanel.LYRICS -> PanelPlaceholder(R.string.player_lyrics_later, modifier)
        PlayerPanel.SIMILAR -> PanelPlaceholder(R.string.player_similar_later, modifier)
        PlayerPanel.INFO -> PanelPlaceholder(R.string.player_info_later, modifier)
    }
}

/** Команды плеера одним значением: раскладке их нужно семь. */
private class PlayerControls(
    val onPlayPause: () -> Unit,
    val onSeek: (Duration) -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onShuffle: () -> Unit,
    val onRepeat: () -> Unit,
    val onHoldSkip: (forward: Boolean) -> Unit,
    val onLike: () -> Unit,
)

@Composable
private fun PlayerCore(
    state: PlayerUiState,
    controls: PlayerControls,
    panels: PanelControls,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerFrame(
        frame = { size -> PanelArea(panels = panels, state = state, frame = size.clip(RoundedCornerShape(12.dp))) },
        dots = { PanelDots(selected = panels.panel, onSelect = panels.onPanel) },
        modifier = modifier,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title ?: stringResource(R.string.player_nothing_open),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                state.artist?.let { artist ->
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        modifier =
                            Modifier.clickable(onClickLabel = stringResource(R.string.player_go_artist)) {
                                onOpenArtist(artist)
                            },
                    )
                }
            }
            state.liked?.let { liked -> LikeButton(liked, controls.onLike) }
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

/** Сердце рядом с названием (план 12, D1): лайк текущего трека. */
@Composable
private fun LikeButton(
    liked: Boolean,
    onLike: () -> Unit,
) {
    IconButton(onClick = onLike) {
        Icon(
            painterResource(if (liked) R.drawable.ic_liked else R.drawable.ic_like),
            contentDescription = stringResource(if (liked) R.string.track_unlike else R.string.track_like),
            tint = if (liked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
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
    val fraction = dragged ?: state.progressFraction
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
        SkipButton(
            icon = R.drawable.ic_skip_previous,
            label = R.string.player_previous,
            onClick = controls.onPrevious,
            onHold = { controls.onHoldSkip(false) },
        )
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
        SkipButton(
            icon = R.drawable.ic_skip_next,
            label = R.string.player_next,
            onClick = controls.onNext,
            onHold = { controls.onHoldSkip(true) },
        )
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

private val PlaybackError.messageRes: Int
    @StringRes get() =
        when (this) {
            is PlaybackError.SourceUnavailable -> R.string.player_error_unavailable
            is PlaybackError.UnsupportedFormat -> R.string.player_error_format
            is PlaybackError.Malformed -> R.string.player_error_malformed
            is PlaybackError.Network -> R.string.player_error_network
            is PlaybackError.Unknown -> R.string.player_error_unknown
        }

private const val NO_TIME = "--:--"
private val PLAY_SIZE = 64.dp
