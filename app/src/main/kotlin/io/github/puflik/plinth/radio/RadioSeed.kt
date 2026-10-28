package io.github.puflik.plinth.radio

import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.queue.QueueItem

/**
 * От чего начинать радио (Ю4в): [item] — трек, как он стоит в очереди (он же сыграет первым), [track] — его ID в
 * каталоге; у файла не из фонотеки и у готового адреса его нет.
 */
data class RadioSeed(
    val item: QueueItem,
    val track: TrackId?,
)

/** Пункт «Радио»: начать радио от [RadioSeed]. Не ждёт сети — ответ человеку идёт вестями (`RadioNotices`). */
fun interface RadioLauncher {
    fun start(seed: RadioSeed)

    companion object {
        /** Радио не запускает: для тестов и экранов, которым оно ни к чему. */
        val None = RadioLauncher { }
    }
}
