package io.github.puflik.plinth.library

import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.ffi.CoreFailure
import io.github.puflik.plinth.ffi.CoreTrack
import io.github.puflik.plinth.library.model.LibraryTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

// Общее у фасадов поверх ядра (D3b, D4): перевод строк ядра в модель и
// вызовы, отказ которых уже ушёл в `CoreErrors` и здесь только пишется в лог.

/** Играть можно только файл: строки без пути (сетевые источники — эпик E) не показываются. */
internal fun CoreTrack.toLibraryTrack(): LibraryTrack? {
    val path = uri?.takeIf(String::isNotBlank) ?: return null
    return LibraryTrack(
        id = id,
        uri = path,
        title = title.ifBlank { path.substringAfterLast('/') },
        artist = artistCredit.ifEmpty { null },
        album = albumTitle,
        albumArtist = albumArtist,
        discNumber = disc?.takeIf { it > 0 },
        trackNumber = number?.takeIf { it > 0 },
        duration = duration,
        folder = folder.orEmpty(),
        liked = liked,
    )
}

internal fun List<CoreTrack>.toLibraryTracks(): List<LibraryTrack> = mapNotNull(CoreTrack::toLibraryTrack)

/** Вызов ядра [call]; отказ пишется в лог под [tag] как «[what] failed», вместо результата — `null`. */
internal fun <T : Any> quietly(
    tag: String,
    what: String,
    call: () -> T?,
): T? =
    try {
        call()
    } catch (failure: CoreFailure) {
        AppLog.w(tag, "$what failed", failure)
        null
    }

/** [quietly] в [io]: вызовы ядра блокирующие. */
internal suspend fun <T : Any> attempt(
    io: CoroutineDispatcher,
    tag: String,
    what: String,
    call: () -> T?,
): T? = withContext(io) { quietly(tag, what, call) }
