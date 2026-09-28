package io.github.puflik.plinth.online

import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.OnlineVariant
import kotlin.time.Duration.Companion.milliseconds

/**
 * Концерт из `OnlineRepositoryContractTest` — как его раскрыл бы Internet
 * Archive: треки по номерам, у каждого исходник FLAC и производный MP3.
 * Фейк берёт треки отсюда, сеть для ядра на эмуляторе строит отсюда ответы.
 */
object TestConcert {
    const val ITEM = OnlineRepositoryContractTest.CONCERT
    const val TITLE = "Test Concert"
    const val ARTIST = "Plinth Band"
    const val YEAR = 1999

    /** Файлы треков без расширения и их длительность. */
    val files = listOf("01 Opening" to 61_500L, "02 Closing" to 125_250L)

    val tracks: List<OnlineTrack> =
        files.mapIndexed { index, (file, millis) ->
            OnlineTrack(
                external = "$ITEM/$file.flac",
                title = file.substringAfter(' '),
                artist = ARTIST,
                album = TITLE,
                number = index + 1,
                year = YEAR,
                duration = millis.milliseconds,
                variants =
                    listOf(
                        OnlineVariant("$ITEM/$file.flac", AudioFormat.FLAC),
                        OnlineVariant("$ITEM/$file.mp3", AudioFormat.MP3, MP3_KBPS),
                    ),
            )
        }

    const val MP3_KBPS = 192
}
