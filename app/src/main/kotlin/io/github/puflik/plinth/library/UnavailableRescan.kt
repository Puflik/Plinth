package io.github.puflik.plinth.library

import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.core.FailedTrack
import io.github.puflik.plinth.core.TrackProblem
import io.github.puflik.plinth.diagnostics.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Трек библиотеки не открылся как недоступный (G3) — повод пересканировать.
 *
 * Помечает пропавшее сканер: источник правды — `MediaStore`, и только он
 * знает, удалён файл или доступ к нему временно закрыт. Скан ставится один
 * раз за запуск — очередь с повтором иначе дёргала бы его на каждом круге.
 * Файлы из «Открыть файл» сканер не видит, битый файл на месте — их это не
 * касается.
 */
class UnavailableRescan(
    private val errors: Flow<AppError>,
    private val scan: LibraryScan,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            errors.first { error -> error.failedTracks.any(::isGoneFromLibrary) }
            AppLog.i(TAG, "library track unavailable, rescanning")
            scan.start()
        }
    }

    private fun isGoneFromLibrary(track: FailedTrack) =
        track.problem == TrackProblem.UNAVAILABLE && track.file.startsWith(MEDIA_STORE)

    private val AppError.failedTracks: List<FailedTrack>
        get() =
            when (this) {
                is AppError.TracksSkipped -> tracks
                is AppError.PlaybackStopped -> skipped + track
            }

    private companion object {
        const val TAG = "Scan"

        /** Треки библиотеки — строки `MediaStore`, их адреса начинаются так. */
        const val MEDIA_STORE = "content://media/"
    }
}
