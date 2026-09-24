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
import io.github.puflik.plinth.ui.library.artist.ArtistScreen
import io.github.puflik.plinth.ui.library.artist.ArtistViewModel
import io.github.puflik.plinth.ui.player.PlayerScreen
import io.github.puflik.plinth.ui.search.SearchScreen
import io.github.puflik.plinth.ui.settings.SettingsScreen

/**
 * Граф навигации (A2.1).
 *
 * Библиотека — стартовый экран; плеер, альбом и исполнитель открываются
 * поверх вкладок. Из плеера к альбому и исполнителю — вместо плеера: он
 * сворачивается в мини-плеер, «назад» ведёт туда, откуда плеер открыли.
 * В настройках — стартовый экран (F3) и папки фонотеки (C2.5). Куда
 * открыться при запуске, решает `MainActivity`: плеер — поверх библиотеки.
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
        val openAlbum = { album: Album -> navController.navigate(albumRoute(album)) }
        val openArtist = { artist: String -> navController.navigate(artistRoute(artist)) }
        composable(Destination.Library.route) {
            LibraryScreen(onOpenPlayer = openPlayer, onOpenAlbum = openAlbum, onOpenArtist = openArtist)
        }
        composable(Destination.Player.route) {
            PlayerScreen(
                onBack = back,
                onOpenAlbum = { album ->
                    navController.popBackStack()
                    openAlbum(album)
                },
                onOpenArtist = { artist ->
                    navController.popBackStack()
                    openArtist(artist)
                },
            )
        }
        composable(Destination.Album.route, arguments = ALBUM_ARGUMENTS) {
            AlbumScreen(onBack = back, onOpenPlayer = openPlayer)
        }
        composable(Destination.Artist.route, arguments = ARTIST_ARGUMENTS) {
            ArtistScreen(onBack = back, onOpenPlayer = openPlayer, onOpenAlbum = openAlbum)
        }
        composable(Destination.Search.route) {
            SearchScreen(onOpenPlayer = openPlayer)
        }
        composable(Destination.Settings.route) {
            SettingsScreen()
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

private val ARTIST_ARGUMENTS = listOf(navArgument(ArtistViewModel.ARG_NAME) { type = NavType.StringType })

/** Маршрут экрана исполнителя: имя — любая строка, поэтому кодируется. */
private fun artistRoute(artist: String): String = "artist?${ArtistViewModel.ARG_NAME}=${Uri.encode(artist)}"

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
