package io.github.puflik.plinth.audio

import io.github.puflik.plinth.audio.engine.AudioEngine
import io.github.puflik.plinth.audio.engine.PlaybackEvent
import io.github.puflik.plinth.audio.engine.PlaybackParams
import io.github.puflik.plinth.audio.engine.PlaybackProgress
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.audio.engine.TrackInfo
import io.github.puflik.plinth.di.ApplicationScope
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueAction
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.queue.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Фасад воспроизведения для UI (B2–B4, D) — единственная точка, через которую
 * экраны управляют звуком и очередью.
 *
 * Движок строг: команда без источника — исключение. Экрану такая строгость
 * ни к чему — кнопка, нажатая до выбора трека, не должна ронять приложение.
 * Фасад переводит намерения пользователя («переключить», «дальше») в
 * команды, допустимые в текущем состоянии, а недопустимые молча пропускает.
 *
 * Очередь ([PlaybackQueue]) живёт здесь: доигравший трек сменяется
 * следующим по событию движка, которое фасад слушает в [scope] всё время
 * жизни процесса.
 */
@Singleton
class PlaybackController
    @Inject
    constructor(
        private val engine: AudioEngine,
        @ApplicationScope scope: CoroutineScope,
    ) {
        val state: StateFlow<PlaybackState> get() = engine.state
        val progress: StateFlow<PlaybackProgress> get() = engine.progress
        val events: Flow<PlaybackEvent> get() = engine.events

        private val mutableQueue = MutableStateFlow(PlaybackQueue.EMPTY)

        /** Что играет, что дальше, shuffle и повтор. */
        val queue: StateFlow<PlaybackQueue> = mutableQueue.asStateFlow()

        init {
            scope.launch {
                engine.events.collect { event -> if (event == PlaybackEvent.TrackEnded) advance(auto = true) }
            }
        }

        /** Играть [items] контекстом [context] с трека [start]; вручную добавленное остаётся в очереди. */
        fun play(
            context: QueueContext,
            items: List<QueueItem>,
            start: Int,
        ) = start(queue.value.play(context, items, start))

        /** «Заменить очередь»: как [play], но без вручную добавленного. */
        fun replace(
            context: QueueContext,
            items: List<QueueItem>,
            start: Int,
        ) = start(queue.value.replace(context, items, start))

        /**
         * Вернуть сохранённую очередь на паузе на [position] (D2.2). Если
         * пользователь успел включить что-то сам — ничего не делает.
         */
        fun restore(
            saved: PlaybackQueue,
            position: Duration,
        ) {
            if (queue.value != PlaybackQueue.EMPTY) return
            mutableQueue.value = saved
            saved.current?.let { prepare(it, PlaybackParams(startPosition = position, autoPlay = false)) }
        }

        /** Добавить трек в ручной блок; если ничего не играло — он и заиграет. */
        fun perform(
            action: QueueAction,
            item: QueueItem,
        ) {
            val added = queue.value.perform(action, item)
            if (added.current == null) added.next()?.let(::start) else mutableQueue.value = added
        }

        fun next() = advance(auto = false)

        /**
         * Назад: в первые [RESTART_THRESHOLD] трека — к предыдущему, позже — к
         * началу этого же. С первого трека без повтора — тоже к началу.
         */
        fun previous() {
            val current = queue.value
            if (current.current == null) return
            val back = current.previous()
            if (progress.value.position > RESTART_THRESHOLD || back == current) restart() else start(back)
        }

        /**
         * Убрать [item] с места [index] среди следующих треков (E4). Если там
         * уже другой трек — очередь сдвинулась, пока палец был на экране, —
         * ничего не делает: убрать не тот трек хуже, чем не убрать никакого.
         */
        fun removeUpcoming(
            index: Int,
            item: QueueItem,
        ) = editUpcoming(index, item) { it.remove(index) }

        /** Переставить [item] с места [from] на место [to]; устаревший жест, как в [removeUpcoming], пропускается. */
        fun moveUpcoming(
            from: Int,
            to: Int,
            item: QueueItem,
        ) = editUpcoming(from, item) { queue -> if (to in queue.upcoming.indices) queue.move(from, to) else queue }

        fun toggleShuffle() = mutableQueue.update { it.withShuffle(!it.shuffle) }

        /** Повтор по кругу: выключен → всё → один трек → выключен. */
        fun cycleRepeat() =
            mutableQueue.update {
                it.withRepeat(
                    when (it.repeat) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    },
                )
            }

        /** Кнопка play/pause; после конца трека играет его заново. */
        fun togglePlayPause() {
            when (engine.state.value) {
                PlaybackState.Playing, PlaybackState.Buffering -> engine.pause()
                PlaybackState.Paused, PlaybackState.Ended -> engine.play()
                PlaybackState.Idle, is PlaybackState.Error -> Unit
            }
        }

        /** Перемотка; без открытого трека ничего не делает. */
        fun seekTo(position: Duration) {
            if (engine.state.value.hasSource) engine.seekTo(position.coerceAtLeast(Duration.ZERO))
        }

        /** Перемотка на [delta] от текущей позиции — не раньше начала и не дальше конца трека. */
        fun seekBy(delta: Duration) {
            val progress = progress.value
            val target = (progress.position + delta).coerceAtLeast(Duration.ZERO)
            seekTo(progress.duration?.let(target::coerceAtMost) ?: target)
        }

        private fun advance(auto: Boolean) {
            val current = queue.value
            if (current.current != null) current.next(auto)?.let(::start)
        }

        private fun restart() = seekTo(Duration.ZERO)

        /** Правка того, что сыграет дальше; текущий трек она не трогает, поэтому движок не нужен. */
        private fun editUpcoming(
            index: Int,
            item: QueueItem,
            edit: (PlaybackQueue) -> PlaybackQueue,
        ) = mutableQueue.update { queue -> if (queue.upcoming.getOrNull(index) == item) edit(queue) else queue }

        private fun start(queue: PlaybackQueue) {
            mutableQueue.value = queue
            queue.current?.let { prepare(it, PlaybackParams(autoPlay = true)) }
        }

        /** Подписи трека идут в движок: снаружи файл без тегов называется так же, как в приложении. */
        private fun prepare(
            item: QueueItem,
            params: PlaybackParams,
        ) = engine.prepare(item.source, params.copy(info = TrackInfo(item.title, item.artist, item.album)))

        private val PlaybackState.hasSource: Boolean
            get() = this != PlaybackState.Idle && this !is PlaybackState.Error

        companion object {
            /** Сколько должно сыграть, чтобы «назад» вернуло к началу трека, а не к предыдущему. */
            val RESTART_THRESHOLD: Duration = 3.seconds
        }
    }
