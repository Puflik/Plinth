package io.github.puflik.plinth.ui.library.components

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.ui.common.formatTime
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.playlists.PlaylistPicker
import io.github.puflik.plinth.ui.online.providerName
import kotlin.time.Duration

/**
 * Строка трека (C4.2): название, исполнитель и альбом, длительность — если
 * она известна, облако — если трек сетевой (E3).
 * Одна на все списки — треки, папки, альбом, поиск, плейлисты. Касание —
 * играть список с этого трека; долгое нажатие или видимая кнопка ⋮ справа
 * (Р6) — меню очереди (D1.2), лайк (D4a) и «В плейлист» (D4b), одно на оба.
 * Плейлист выбирается здесь же, в диалоге.
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
    // Меню одно, а открывается у двух мест: у строки (долгое нажатие) и у кнопки ⋮.
    var rowMenu by remember { mutableStateOf(false) }
    var buttonMenu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val choose = { action: TrackAction ->
        rowMenu = false
        buttonMenu = false
        if (action == TrackAction.ADD_TO_PLAYLIST) picker = true else onAction(action)
    }
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
            trailingContent = {
                Trailing(label) {
                    MoreButton(onClick = { buttonMenu = true }) {
                        TrackMenu(buttonMenu, label.liked, onDismiss = { buttonMenu = false }, onChoose = choose)
                    }
                }
            },
            modifier =
                Modifier.combinedClickable(
                    onClick = { onAction(TrackAction.PLAY) },
                    onLongClick = { rowMenu = true },
                ),
        )
        TrackMenu(rowMenu, label.liked, onDismiss = { rowMenu = false }, onChoose = choose)
    }
    if (picker) playlistPicker { picker = false }
}

/** Пункты меню трека: очередь, «В плейлист…», лайк — одни для долгого нажатия и кнопки ⋮. */
@Composable
private fun TrackMenu(
    expanded: Boolean,
    liked: Boolean,
    onDismiss: () -> Unit,
    onChoose: (TrackAction) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        (MENU + PLAYLIST_ITEM + likeItem(liked)).forEach { (action, text) ->
            DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = { onChoose(action) })
        }
    }
}

/** Кнопка ⋮ у строки; [menu] — меню, которое она открывает, раскрывается у неё же. */
@Composable
private fun MoreButton(
    onClick: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Box {
        IconButton(onClick = onClick) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.library_more))
        }
        menu()
    }
}

/**
 * Что показывает [TrackRow]: подписи, лайк и откуда звук.
 *
 * @property provider чей сетевой трек (ID провайдера, как его знает ядро); `null` — экран этого не знает (списки
 *   фонотеки, очередь): облако тогда говорит просто «сетевой» (Ю5а).
 */
data class TrackLabel(
    val title: String,
    val artist: String?,
    val album: String?,
    val duration: Duration?,
    val liked: Boolean,
    val online: Boolean,
    val provider: String? = null,
) {
    companion object {
        fun of(track: LibraryTrack) =
            TrackLabel(track.title, track.artist, track.album, track.duration, track.liked, track.online)
    }
}

/** Справа: облако — если трек сетевой, длительность — если известна, и всегда [more] (кнопка ⋮). */
@Composable
private fun Trailing(
    label: TrackLabel,
    more: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.online) OnlineMark(source = label.provider?.let(::providerName))
        label.duration?.let { Text(formatTime(it)) }
        more()
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
        TrackAction.RADIO to R.string.track_radio,
    )

private val PLAYLIST_ITEM = TrackAction.ADD_TO_PLAYLIST to R.string.track_add_to_playlist

/** Лайк стоит — пункт снимает его, нет — ставит. */
private fun likeItem(liked: Boolean) =
    if (liked) TrackAction.UNLIKE to R.string.track_unlike else TrackAction.LIKE to R.string.track_like
