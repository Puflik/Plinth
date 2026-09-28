package io.github.puflik.plinth.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ui.library.album.AlbumViewModel.Companion.ARG_ARTIST
import io.github.puflik.plinth.ui.library.album.AlbumViewModel.Companion.ARG_TITLE
import io.github.puflik.plinth.ui.library.album.AlbumViewModel.Companion.ARG_TRACK_COUNT
import io.github.puflik.plinth.ui.library.artist.ArtistViewModel.Companion.ARG_NAME
import io.github.puflik.plinth.ui.library.playlists.AutoPlaylistViewModel.Companion.ARG_KIND
import io.github.puflik.plinth.ui.library.playlists.PlaylistViewModel.Companion.ARG_ID
import io.github.puflik.plinth.ui.online.OnlineAlbumViewModel.Companion.ARG_ITEM
import io.github.puflik.plinth.ui.online.OnlineAlbumViewModel.Companion.ARG_PROVIDER
import io.github.puflik.plinth.ui.online.OnlineAlbumViewModel.Companion.ARG_YEAR
import io.github.puflik.plinth.ui.library.playlists.PlaylistViewModel.Companion.ARG_NAME as ARG_PLAYLIST_NAME
import io.github.puflik.plinth.ui.online.OnlineAlbumViewModel.Companion.ARG_ARTIST as ARG_ONLINE_ARTIST
import io.github.puflik.plinth.ui.online.OnlineAlbumViewModel.Companion.ARG_TITLE as ARG_ONLINE_TITLE

/**
 * Перечень экранов приложения (A2.1).
 *
 * Вкладки нижней навигации — подмножество [tabs]; у них есть подпись и
 * значок. Экраны без вкладки (плеер, альбом, исполнитель, плейлисты) открываются поверх вкладок, их
 * [labelRes] и [iconRes] — `0`.
 */
enum class Destination(
    val route: String,
    @field:StringRes val labelRes: Int = 0,
    @field:DrawableRes val iconRes: Int = 0,
) {
    Library(
        route = "library",
        labelRes = R.string.nav_library,
        iconRes = R.drawable.ic_nav_library,
    ),
    Search(
        route = "search",
        labelRes = R.string.nav_search,
        iconRes = R.drawable.ic_nav_search,
    ),
    Settings(
        route = "settings",
        labelRes = R.string.nav_settings,
        iconRes = R.drawable.ic_nav_settings,
    ),
    Player(route = "player"),

    /** Шаблон маршрута; сам маршрут собирает навигация из аргументов альбома. */
    Album(route = "album?$ARG_TITLE={$ARG_TITLE}&$ARG_ARTIST={$ARG_ARTIST}&$ARG_TRACK_COUNT={$ARG_TRACK_COUNT}"),

    /** Шаблон маршрута экрана исполнителя (E5); имя кодирует навигация. */
    Artist(route = "artist?$ARG_NAME={$ARG_NAME}"),

    /** Шаблон маршрута своего плейлиста (D4b); имя кодирует навигация. */
    Playlist(route = "playlist?$ARG_ID={$ARG_ID}&$ARG_PLAYLIST_NAME={$ARG_PLAYLIST_NAME}"),

    /** Шаблон маршрута «Любимого» и «Недавнего» (D4b). */
    AutoPlaylist(route = "auto-playlist?$ARG_KIND={$ARG_KIND}"),

    /** Шаблон маршрута альбома провайдера (E3); строки кодирует навигация. */
    OnlineAlbum(
        route =
            "online-album?$ARG_PROVIDER={$ARG_PROVIDER}&$ARG_ITEM={$ARG_ITEM}&$ARG_ONLINE_TITLE={$ARG_ONLINE_TITLE}" +
                "&$ARG_ONLINE_ARTIST={$ARG_ONLINE_ARTIST}&$ARG_YEAR={$ARG_YEAR}",
    ),
    ;

    companion object {
        /** Вкладки нижней навигации в порядке отображения (A2.3). */
        val tabs: List<Destination> = listOf(Library, Search, Settings)

        /** Экран, с которого приложение открывается. */
        val START: Destination = Library
    }
}
