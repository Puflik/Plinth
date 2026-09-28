package io.github.puflik.plinth.ui.library.components

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.ui.common.formatTime
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.playlists.PlaylistPicker
import kotlin.time.Duration

/**
 * Строка трека (C4.2): название, исполнитель и альбом, длительность — если
 * она известна, облако — если трек сетевой (E3).
 * Одна на все списки — треки, папки, альбом, поиск, плейлисты. Касание —
 * играть список с этого трека, долгое нажатие — меню очереди (D1.2), лайк
 * (D4a) и «В плейлист» (D4b). Плейлист выбирается здесь же, в диалоге.
 */
@Composable
fun TrackRow(
    track: LibraryTrack,
    onAction: (TrackAction) -> Unit,
    modifier: Modifier = Modifier,
) = TrackRow(TrackLabel.of(track), onAction, modifier) { dismiss -> PlaylistPicker(track, onDismiss = dismiss) }

/**
 * Строка трека по его подписям [label] — и для трека, которого ещё нет в
 * фонотеке (альбом провайдера, E3): «В плейлист» открывает [playlistPicker].
 */
@Composable
fun TrackRow(
    label: TrackLabel,
    onAction: (TrackAction) -> Unit,
    modifier: Modifier = Modifier,
    playlistPicker: @Composable (onDismiss: () -> Unit) -> Unit,
) {
    val artist = label.artist ?: stringResource(R.string.library_unknown_artist)
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ListItem(
            headlineContent = { Text(label.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    listOfNotNull(artist, label.album).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = trailing(label),
            modifier =
                Modifier.combinedClickable(
                    onClick = { onAction(TrackAction.PLAY) },
                    onLongClick = { menu = true },
                ),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            (MENU + PLAYLIST_ITEM + likeItem(label.liked)).forEach { (action, text) ->
                DropdownMenuItem(
                    text = { Text(stringResource(text)) },
                    onClick = {
                        menu = false
                        if (action == TrackAction.ADD_TO_PLAYLIST) picker = true else onAction(action)
                    },
                )
            }
        }
    }
    if (picker) playlistPicker { picker = false }
}

/** Что показывает [TrackRow]: подписи, лайк и откуда звук. */
data class TrackLabel(
    val title: String,
    val artist: String?,
    val album: String?,
    val duration: Duration?,
    val liked: Boolean,
    val online: Boolean,
) {
    companion object {
        fun of(track: LibraryTrack) =
            TrackLabel(track.title, track.artist, track.album, track.duration, track.liked, track.online)
    }
}

/** Облако и длительность справа; ни того, ни другого — ничего. */
private fun trailing(label: TrackLabel): (@Composable () -> Unit)? {
    if (!label.online && label.duration == null) return null
    return {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (label.online) OnlineMark()
            label.duration?.let { Text(formatTime(it)) }
        }
    }
}

/**
 * Что показать после действия над треком: плеер — если трек заиграл,
 * короткое «добавлено» — если он встал в очередь. Лайк виден в самом меню.
 */
@Composable
fun rememberTrackActionFeedback(onOpenPlayer: () -> Unit): (TrackAction) -> Unit {
    val context = LocalContext.current
    return remember(context, onOpenPlayer) {
        { action ->
            when {
                action.startsPlayback -> onOpenPlayer()
                action.queues -> Toast.makeText(context, R.string.queue_added, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private val MENU =
    listOf(
        TrackAction.PLAY_NEXT to R.string.track_play_next,
        TrackAction.ADD_TO_QUEUE to R.string.track_add_to_queue,
        TrackAction.REPLACE_QUEUE to R.string.track_replace_queue,
    )

private val PLAYLIST_ITEM = TrackAction.ADD_TO_PLAYLIST to R.string.track_add_to_playlist

/** Лайк стоит — пункт снимает его, нет — ставит. */
private fun likeItem(liked: Boolean) =
    if (liked) TrackAction.UNLIKE to R.string.track_unlike else TrackAction.LIKE to R.string.track_like
