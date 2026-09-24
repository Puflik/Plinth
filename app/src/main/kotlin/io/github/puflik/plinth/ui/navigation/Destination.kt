package io.github.puflik.plinth.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ui.library.album.AlbumViewModel.Companion.ARG_ARTIST
import io.github.puflik.plinth.ui.library.album.AlbumViewModel.Companion.ARG_TITLE
import io.github.puflik.plinth.ui.library.album.AlbumViewModel.Companion.ARG_TRACK_COUNT
import io.github.puflik.plinth.ui.library.artist.ArtistViewModel.Companion.ARG_NAME

/**
 * Перечень экранов приложения (A2.1).
 *
 * Вкладки нижней навигации — подмножество [tabs]; у них есть подпись и
 * значок. Экраны без вкладки (плеер, альбом, исполнитель) открываются поверх вкладок, их
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
    ;

    companion object {
        /** Вкладки нижней навигации в порядке отображения (A2.3). */
        val tabs: List<Destination> = listOf(Library, Search, Settings)

        /** Экран, с которого приложение открывается. */
        val START: Destination = Library
    }
}
