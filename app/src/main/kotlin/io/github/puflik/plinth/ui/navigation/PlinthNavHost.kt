package io.github.puflik.plinth.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.ui.library.LibraryScreen
import io.github.puflik.plinth.ui.library.album.AlbumScreen
import io.github.puflik.plinth.ui.library.album.AlbumViewModel
import io.github.puflik.plinth.ui.player.PlayerScreen
import io.github.puflik.plinth.ui.search.SearchScreen
import io.github.puflik.plinth.ui.settings.FolderSettingsScreen

/**
 * Граф навигации (A2.1).
 *
 * Библиотека — стартовый экран; плеер и альбом открываются поверх вкладок.
 * Из настроек пока есть только папки фонотеки (C2.5), остальное придёт с F.
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
            SearchScreen(onOpenPlayer = openPlayer)
        }
        composable(Destination.Settings.route) {
            FolderSettingsScreen()
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
