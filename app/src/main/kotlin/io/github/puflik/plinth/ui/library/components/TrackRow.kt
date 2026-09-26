package io.github.puflik.plinth.ui.library.components

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.ui.common.formatTime
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.playlists.PlaylistPicker

/**
 * Строка трека (C4.2): название, исполнитель и альбом, длительность — если
 * она известна.
 * Одна на все списки — треки, папки, альбом, поиск, плейлисты. Касание —
 * играть список с этого трека, долгое нажатие — меню очереди (D1.2), лайк
 * (D4a) и «В плейлист» (D4b). Плейлист выбирается здесь же, в диалоге.
 */
@Composable
fun TrackRow(
    track: LibraryTrack,
    onAction: (TrackAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val artist = track.artist ?: stringResource(R.string.library_unknown_artist)
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ListItem(
            headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    listOfNotNull(artist, track.album).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = track.duration?.let { duration -> { Text(formatTime(duration)) } },
            modifier =
                Modifier.combinedClickable(
                    onClick = { onAction(TrackAction.PLAY) },
                    onLongClick = { menu = true },
                ),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            (MENU + PLAYLIST_ITEM + likeItem(track.liked)).forEach { (action, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        menu = false
                        if (action == TrackAction.ADD_TO_PLAYLIST) picker = true else onAction(action)
                    },
                )
            }
        }
    }
    if (picker) PlaylistPicker(track, onDismiss = { picker = false })
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
