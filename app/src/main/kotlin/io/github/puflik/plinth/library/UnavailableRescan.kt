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
 * Помечает пропавшее скан ядра: он обходит тома и знает, удалён файл или
 * том вынут. Скан ставится один раз за запуск — очередь с повтором иначе
 * дёргала бы его на каждом круге. Трек фонотеки узнаётся по пути к файлу
 * (D3c); файлы из «Открыть файл» (`content://`) скан не видит, битый файл
 * на месте — их это не касается.
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
        track.problem == TrackProblem.UNAVAILABLE && track.file.startsWith(LIBRARY_PATH)

    private val AppError.failedTracks: List<FailedTrack>
        get() =
            when (this) {
                is AppError.TracksSkipped -> tracks
                is AppError.PlaybackStopped -> skipped + track
                is AppError.CoreFailed -> emptyList()
            }

    private companion object {
        const val TAG = "Scan"

        /** Треки фонотеки ядра — пути к файлам, у адресов других источников есть схема. */
        const val LIBRARY_PATH = "/"
    }
}
