package io.github.puflik.plinth.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.puflik.plinth.R

/**
 * Перечень экранов приложения (A2.1).
 *
 * Пока каждый экран совпадает с вкладкой нижней навигации. Когда появятся
 * экраны без вкладки (плеер, онбординг), они добавятся сюда же, а [tabs]
 * останется подмножеством.
 */
enum class Destination(
    val route: String,
    @field:StringRes val labelRes: Int,
    @field:DrawableRes val iconRes: Int,
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
    ;

    companion object {
        /** Вкладки нижней навигации в порядке отображения (A2.3). */
        val tabs: List<Destination> = listOf(Library, Search, Settings)

        /** Экран, с которого приложение открывается. */
        val START: Destination = Library
    }
}
