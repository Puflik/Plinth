package io.github.puflik.plinth.ui.online

import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.model.LibraryTrack

/**
 * Трек провайдера как трек фонотеки: каталог дал ему [id], файла нет (E3). Так его строят экран альбома и песни
 * секции поиска (Ю5а) — действия над треком (`TrackActions`) знают только `LibraryTrack`.
 */
internal fun OnlineTrack.toLibraryTrack(id: TrackId) =
    LibraryTrack(
        id = id,
        uri = null,
        title = title,
        artist = artist,
        album = album,
        trackNumber = number?.takeIf { it > 0 },
        duration = duration,
        folder = "",
    )
