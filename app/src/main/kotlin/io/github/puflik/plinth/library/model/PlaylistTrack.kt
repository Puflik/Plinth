package io.github.puflik.plinth.library.model

import io.github.puflik.plinth.ffi.PlaylistEntryId

/**
 * Трек плейлиста (D4b) — строка экрана плейлиста.
 *
 * @property entry запись плейлиста: один трек может стоять в нём дважды, и
 *   переставляют и убирают запись, а не трек.
 */
data class PlaylistTrack(
    val entry: PlaylistEntryId,
    val track: LibraryTrack,
)
