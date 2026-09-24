package io.github.puflik.plinth.audio

import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.di.ApplicationScope
import io.github.puflik.plinth.queue.QueueStore
import io.github.puflik.plinth.startup.PlayHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Очередь переживает перезапуск (D1.5, D2.1, D2.2): при старте приложения
 * возвращает сохранённую очередь на паузе на той же секунде, дальше
 * сохраняет каждую смену очереди и позицию — не чаще раза в секунду.
 *
 * Позиция пишется и во время игры: процесс могут убить без предупреждения,
 * и тогда последняя записанная секунда — всё, что останется.
 *
 * Отсюда же время последней игры (F3, [PlayHistory]): отметка — когда звук
 * пошёл, раз в минуту, пока играет, и когда остановился. Восстановленная
 * на паузе очередь игрой не считается.
 */
@Singleton
class QueueKeeper
    @Inject
    constructor(
        private val playback: PlaybackController,
        private val store: QueueStore,
        @ApplicationScope private val scope: CoroutineScope,
        private val clock: Clock,
    ) : PlayHistory {
        // null — сохранённое ещё не прочитано; Played(null) — ни разу не играло.
        private val played = MutableStateFlow<Played?>(null)

        override val lastPlayed: Flow<Instant?> = played.filterNotNull().map { it.at }

        fun start() {
            scope.launch {
                val saved = store.load()
                saved?.let { playback.restore(it.queue, it.position) }
                played.value = Played(saved?.playedAt)
                // Сохранять — только после восстановления: иначе пустая очередь затёрла бы сохранённую.
                launch { playback.queue.drop(1).collect(store::saveQueue) }
                launch {
                    playback.progress
                        .map { it.position.inWholeSeconds }
                        .distinctUntilChanged()
                        .collect { store.savePosition(it.seconds) }
                }
                launch { markPlays() }
            }
        }

        private suspend fun markPlays() {
            playback.state
                .map { it == PlaybackState.Playing }
                .distinctUntilChanged()
                .dropWhile { playing -> !playing }
                .collectLatest { playing ->
                    markPlayed()
                    while (playing) {
                        delay(PLAYED_EVERY)
                        markPlayed()
                    }
                }
        }

        private suspend fun markPlayed() {
            val now = clock.now()
            played.value = Played(now)
            store.savePlayedAt(now)
        }

        private data class Played(
            val at: Instant?,
        )

        private companion object {
            // Процесс могут убить посреди игры: отметка не старше минуты.
            val PLAYED_EVERY = 1.minutes
        }
    }
