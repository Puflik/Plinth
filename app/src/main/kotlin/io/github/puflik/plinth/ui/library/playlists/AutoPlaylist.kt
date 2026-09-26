package io.github.puflik.plinth.ui.library.playlists

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.puflik.plinth.R

/**
 * Списки, которые складываются сами (D4b), — сверху вкладки «Плейлисты».
 *
 * @property emptyRes что сказать, пока список пуст.
 */
enum class AutoPlaylist(
    @field:StringRes val labelRes: Int,
    @field:DrawableRes val iconRes: Int,
    @field:StringRes val emptyRes: Int,
) {
    /** Треки с лайком, по названию. */
    LIKED(R.string.playlists_liked, R.drawable.ic_liked, R.string.playlist_liked_empty),

    /** Дослушанные треки, последний — первым. */
    RECENT(R.string.playlists_recent, R.drawable.ic_history, R.string.playlist_recent_empty),
}
