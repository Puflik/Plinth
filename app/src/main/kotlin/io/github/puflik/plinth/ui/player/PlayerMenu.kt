package io.github.puflik.plinth.ui.player

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.ui.library.playlists.PlaylistPicker

/**
 * Меню ⋮ плеера (E5, Р6): «Нравится» и «В плейлист…» — для трека фонотеки
 * (`state.liked != null`; у файла не из фонотеки лайка и плейлиста нет, как нет
 * и сердца), потом «К альбому» и «К исполнителю» — то, что известно о треке.
 * Кнопка гаснет, только когда пунктов нет вовсе. Выбор пункта закрывает меню;
 * «В плейлист…» после этого показывает [playlistPicker], пока тот не вызвал
 * `onDismiss`. Сердце рядом с названием остаётся: меню его дублирует.
 */
@Composable
internal fun PlayerMenu(
    state: PlayerUiState,
    onLike: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (String) -> Unit,
    playlistPicker: @Composable (onDismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val liked = state.liked
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = liked != null || state.album != null || state.artist != null,
        ) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.library_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (liked != null) {
                MenuItem(if (liked) R.string.track_unlike else R.string.track_like) {
                    expanded = false
                    onLike()
                }
                MenuItem(R.string.track_add_to_playlist) {
                    expanded = false
                    picker = true
                }
            }
            state.album?.let { album ->
                MenuItem(R.string.player_go_album) {
                    expanded = false
                    onOpenAlbum(album)
                }
            }
            state.artist?.let { artist ->
                MenuItem(R.string.player_go_artist) {
                    expanded = false
                    onOpenArtist(artist)
                }
            }
        }
    }
    if (picker) playlistPicker { picker = false }
}

/**
 * Меню плеера, собранное из модели: лайк и выбор плейлиста идут в
 * [PlayerViewModel]. Вынесено из `PlayerScreen`, чтобы экран не рос. Пока выбор
 * плейлиста открыт, модель помнит трек, для которого его открыли: очередь может
 * уйти дальше, а добавится тот, что был на экране.
 */
@Composable
internal fun PlayerScreenMenu(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (String) -> Unit,
) = PlayerMenu(
    state = state,
    onLike = viewModel::onLike,
    onOpenAlbum = onOpenAlbum,
    onOpenArtist = onOpenArtist,
    playlistPicker = { dismiss ->
        DisposableEffect(Unit) {
            viewModel.onPlaylistPickerShown()
            onDispose { viewModel.onPlaylistPickerClosed() }
        }
        PlaylistPicker(
            onAdd = { viewModel.onAddToPlaylist(it.id) },
            onAddToNew = viewModel::onAddToNewPlaylist,
            onDismiss = dismiss,
        )
    },
)

@Composable
private fun MenuItem(
    @StringRes text: Int,
    onClick: () -> Unit,
) = DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = onClick)
