package io.github.puflik.plinth.diagnostics.vendor

import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Метка «звук идёт» на диске (G2.1): стоит, пока играет. Осталась при
 * старте процесса — прошлый процесс умер посреди игры.
 */
class KillMarker(
    private val directory: File,
) {
    private val file = File(directory, "playing")

    fun isSet(): Boolean = file.exists()

    fun set() {
        directory.mkdirs()
        file.createNewFile()
    }

    fun clear() {
        file.delete()
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
