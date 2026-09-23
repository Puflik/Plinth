package io.github.puflik.plinth.ui.library

import androidx.annotation.StringRes
import io.github.puflik.plinth.R

/** Вкладки библиотеки (C4.1) в порядке показа. */
enum class LibraryTab(
    @field:StringRes val labelRes: Int,
) {
    TRACKS(R.string.library_tab_tracks),
    ALBUMS(R.string.library_tab_albums),
    ARTISTS(R.string.library_tab_artists),
    FOLDERS(R.string.library_tab_folders),
}
