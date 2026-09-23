package io.github.puflik.plinth.ui.navigation

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.ui.library.LibraryScreen
import io.github.puflik.plinth.ui.library.album.AlbumScreen
import io.github.puflik.plinth.ui.library.album.AlbumViewModel
import io.github.puflik.plinth.ui.player.PlayerScreen

/**
 * Граф навигации (A2.1).
 *
 * Библиотека — стартовый экран; плеер и альбом открываются поверх вкладок.
 * Поиск приходит с C4.4, настройки с F — до тех пор заглушки.
 */
@Composable
fun PlinthNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Destination.START.route,
        modifier = modifier,
    ) {
        val openPlayer = { navController.navigate(Destination.Player.route) { launchSingleTop = true } }
        val back: () -> Unit = { navController.popBackStack() }
        composable(Destination.Library.route) {
            LibraryScreen(onOpenPlayer = openPlayer, onOpenAlbum = { navController.navigate(albumRoute(it)) })
        }
        composable(Destination.Player.route) {
            PlayerScreen(onBack = back)
        }
        composable(Destination.Album.route, arguments = ALBUM_ARGUMENTS) {
            AlbumScreen(onBack = back, onOpenPlayer = openPlayer)
        }
        composable(Destination.Search.route) {
            PlaceholderScreen(R.string.placeholder_search)
        }
        composable(Destination.Settings.route) {
            PlaceholderScreen(R.string.placeholder_settings)
        }
    }
}

private val ALBUM_ARGUMENTS =
    listOf(
        navArgument(AlbumViewModel.ARG_TITLE) { type = NavType.StringType },
        navArgument(AlbumViewModel.ARG_ARTIST) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        },
        navArgument(AlbumViewModel.ARG_TRACK_COUNT) {
            type = NavType.IntType
            defaultValue = 0
        },
    )

/**
 * Маршрут экрана альбома. Название и исполнитель — любые строки, с `&`, `?`
 * и `/` в том числе, поэтому кодируются; альбом без исполнителя идёт без него.
 */
private fun albumRoute(album: Album): String =
    AlbumViewModel
        .arguments(album)
        .filterValues { it != null }
        .entries
        .joinToString("&", prefix = "album?") { (name, value) -> "$name=${Uri.encode(value.toString())}" }

@Composable
private fun PlaceholderScreen(
    @StringRes textRes: Int,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}
