package io.github.puflik.plinth.diagnostics.vendor

import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.diagnostics.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Метка «звук идёт» на диске (G2.1): стоит, пока играет. Осталась при
 * старте процесса — прошлый процесс умер посреди игры.
 *
 * В метке — номер загрузки системы ([boot], `Settings.Global.BOOT_COUNT`):
 * другой номер при старте значит, что телефон перезагрузился или сел, и
 * прошивка тут ни при чём (ревью №9). `null` — номер неизвестен.
 */
class KillMarker(
    private val directory: File,
    private val boot: () -> Int? = { null },
) {
    private val file = File(directory, "playing")

    fun isSet(): Boolean = file.exists()

    /** Метка поставлена в прошлую загрузку системы; неизвестный номер — не перезагрузка. */
    fun rebootedSinceSet(): Boolean {
        val marked =
            try {
                file.readText().trim().toIntOrNull()
            } catch (expected: IOException) {
                null
            }
        val now = boot()
        return marked != null && now != null && marked != now
    }

    /** Метку не поставить (нет места) — не повод ронять игру; эту смерть процесса просто не заметим. */
    fun set() {
        try {
            directory.mkdirs()
            file.writeText(boot()?.toString().orEmpty())
        } catch (e: IOException) {
            AppLog.w(TAG, "playing marker not set", e)
        }
    }

    fun clear() {
        file.delete()
    }

    private companion object {
        const val TAG = "Vendor"
    }
}

/** Ставит и снимает [KillMarker] по состоянию воспроизведения — только на переходах. */
class KillWatch(
    private val playback: PlaybackController,
    private val marker: KillMarker,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            playback.state
                .map { it == PlaybackState.Playing }
                .distinctUntilChanged()
                .collect { playing -> if (playing) marker.set() else marker.clear() }
        }
    }
}
