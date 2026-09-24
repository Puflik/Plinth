package io.github.puflik.plinth.audio

import io.github.puflik.plinth.di.ApplicationScope
import io.github.puflik.plinth.queue.QueueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Очередь переживает перезапуск (D1.5, D2.1, D2.2): при старте приложения
 * возвращает сохранённую очередь на паузе на той же секунде, дальше
 * сохраняет каждую смену очереди и позицию — не чаще раза в секунду.
 *
 * Позиция пишется и во время игры: процесс могут убить без предупреждения,
 * и тогда последняя записанная секунда — всё, что останется.
 */
@Singleton
class QueueKeeper
    @Inject
    constructor(
        private val playback: PlaybackController,
        private val store: QueueStore,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        fun start() {
            scope.launch {
                store.load()?.let { playback.restore(it.queue, it.position) }
                // Сохранять — только после восстановления: иначе пустая очередь затёрла бы сохранённую.
                launch { playback.queue.drop(1).collect(store::saveQueue) }
                launch {
                    playback.progress
                        .map { it.position.inWholeSeconds }
                        .distinctUntilChanged()
                        .collect { store.savePosition(it.seconds) }
                }
            }
        }
    }
