package io.github.puflik.plinth.history

import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackEvent
import io.github.puflik.plinth.audio.engine.PlaybackProgress
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.di.ApplicationScope
import io.github.puflik.plinth.ffi.NewPlay
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.UserDataRepository
import io.github.puflik.plinth.queue.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * История прослушиваний (D4a): каждое прослушивание трека фонотеки уходит в
 * журнал ядра, а засчитать ли его в счётчик, ядро решает само — по правилу
 * Last.fm (`PlayEvent::counts`).
 *
 * Сколько звучал трек, считает [ListenTracker] по сигналам
 * [PlaybackController]. Трек узнаётся по файлу: очередь фонотеку не знает.
 * Файл не из фонотеки (открыт через SAF) в историю не попадает. Прослушивание,
 * прерванное смертью процесса, теряется.
 */
@Singleton
class ListeningRecorder
    @Inject
    constructor(
        private val playback: PlaybackController,
        private val userData: UserDataRepository,
        @ApplicationScope private val scope: CoroutineScope,
        private val clock: Clock,
    ) {
        private var previous: TrackId? = null

        fun start() {
            scope.launch {
                val tracker = ListenTracker(clock::now)
                merge(
                    playback.queue.map { Signal.Current(it.playing, it.current) },
                    playback.state.map { Signal.Playing(it == PlaybackState.Playing) },
                    playback.progress.map(Signal::Progress),
                    playback.events.filterIsInstance<PlaybackEvent.TrackEnded>().map { Signal.Ended },
                ).collect { signal ->
                    when (signal) {
                        is Signal.Current -> tracker.current(signal.place, signal.item)?.let { record(it) }
                        is Signal.Playing -> tracker.playing(signal.playing)
                        is Signal.Progress -> tracker.progress(signal.progress)
                        Signal.Ended -> tracker.ended()?.let { record(it) }
                    }
                }
            }
        }

        private suspend fun record(listen: Listen) {
            val uri = (listen.item.source as? AudioSource.LocalFile)?.uri ?: return
            val track = userData.trackAt(uri) ?: return
            userData.recordPlay(
                NewPlay(
                    track = track,
                    startedAt = listen.startedAt,
                    utcOffsetMinutes = utcOffsetMinutes(listen.startedAt),
                    listened = listen.listened,
                    trackLength = listen.length,
                    skippedAt = listen.skippedAt,
                    previousTrack = previous,
                ),
            )
            previous = track
        }

        private fun utcOffsetMinutes(at: Instant): Int =
            TimeZone.getDefault().getOffset(at.toEpochMilliseconds()) / MILLIS_PER_MINUTE

        private sealed interface Signal {
            data class Current(
                val place: Any?,
                val item: QueueItem?,
            ) : Signal

            data class Playing(
                val playing: Boolean,
            ) : Signal

            data class Progress(
                val progress: PlaybackProgress,
            ) : Signal

            data object Ended : Signal
        }

        private companion object {
            const val MILLIS_PER_MINUTE = 60_000
        }
    }
