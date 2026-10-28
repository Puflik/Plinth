package io.github.puflik.plinth.radio

import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.core.RadioProblem
import io.github.puflik.plinth.di.ApplicationScope
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.online.OnlineRadio
import io.github.puflik.plinth.online.OnlineRepository
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Подгрузка радио (Ю4в): пока играет радио и впереди осталось не больше [RadioPolicy.AHEAD] треков контекста,
 * просит у провайдера следующую страницу, заводит её новые треки в каталог и дописывает в конец контекста — текущий
 * трек не прерывается.
 *
 * Работает на одном потоке и одним запросом за раз: пока идёт сеть, изменения очереди склеиваются, и после ответа
 * разбирается последнее. Ключ страницы живёт здесь, в памяти: после запуска приложения его нет, и первая же
 * подгрузка открывает радио заново от затравки, а повторы выбрасывает дедуп. Подгрузка идёт, только пока звук идёт
 * или кончился загруженный конец. Не на паузе: восстановленная очередь радио ждёт первого `play`. И не при
 * `Buffering`: возвращая очередь на паузе, движок готовит трек и проходит через него.
 */
@Singleton
class RadioFeeder
    @Inject
    constructor(
        private val playback: PlaybackController,
        private val online: OnlineRepository,
        private val notices: RadioNotices,
        @ApplicationScope private val scope: CoroutineScope,
        private val clock: Clock,
    ) {
        private var session: Session? = null

        // Конец загруженного уже объявлен: пока он тянется, второй раз не говорим.
        private var toldEnd = false

        fun start() {
            scope.launch {
                combine(playback.queue, playback.state) { queue, state -> queue to state }
                    .conflate()
                    .collect { (queue, state) -> step(queue, state) }
            }
        }

        /**
         * Радио [radio] только что открыто: [next] — ключ его второй страницы, `null` — подборка кончилась. Зовёт
         * [RadioStarter] до `play`; прежняя память об этом радио забывается.
         */
        fun expect(
            radio: QueueContext.Radio,
            next: String?,
        ) {
            session = Session(radio, next, finished = next == null)
        }

        private suspend fun step(
            queue: PlaybackQueue,
            state: PlaybackState,
        ) {
            if (state != PlaybackState.Ended) toldEnd = false
            val radio = queue.context as? QueueContext.Radio
            if (radio != null && hungry(queue, state)) {
                val current = session?.takeIf { it.radio == radio } ?: Session(radio, next = null, finished = false)
                session = current
                val fed = canLoad(current, queue) && load(current, queue)
                if (!fed && queue.endedWith(state)) tellEnd()
            }
        }

        /** Звук идёт (или кончился загруженный конец) и впереди не больше [RadioPolicy.AHEAD] треков. */
        private fun hungry(
            queue: PlaybackQueue,
            state: PlaybackState,
        ): Boolean {
            val ahead = queue.order.size - 1 - queue.position
            return state in LOADING && (ahead <= RadioPolicy.AHEAD || queue.endedWith(state))
        }

        /** Конец загруженного: звук кончился, впереди ничего. */
        private fun PlaybackQueue.endedWith(state: PlaybackState) = state == PlaybackState.Ended && upcoming.isEmpty()

        private fun canLoad(
            session: Session,
            queue: PlaybackQueue,
        ): Boolean =
            !session.finished &&
                queue.contextItems.size < RadioPolicy.LIMIT &&
                session.retryAt?.let { clock.now() >= it } != false

        /** Говорит «Радио остановилось», если конец всё ещё конец (пока шла сеть, мог начаться новый трек). */
        private fun tellEnd() {
            if (!toldEnd && playback.queue.value.endedWith(playback.state.value)) {
                toldEnd = true
                notices.tell(RadioProblem.STOPPED)
            }
        }

        /** Одна подгрузка: страницы до первой с новыми треками. `true` — в очередь что-то дописано. */
        private suspend fun load(
            session: Session,
            queue: PlaybackQueue,
        ): Boolean {
            val base = queue.contextItems.size
            var key = session.next
            var reopened = false
            var repeats = 0
            var fed: Boolean? = null
            while (fed == null) {
                when (val outcome = fetch(session.radio, base, key)) {
                    Outcome.Dropped -> fed = false
                    Outcome.Stuck -> fed = pause(session)
                    Outcome.Failed ->
                        // Для приложения протухший ключ неотличим от поломки: один раз открываем радио заново.
                        if (key != null && !reopened) {
                            key = null
                            reopened = true
                        } else {
                            fed = pause(session)
                        }
                    is Outcome.Fetched -> {
                        session.next = outcome.next
                        repeats = if (outcome.added) 0 else repeats + 1
                        session.finished = outcome.next == null || repeats >= RadioPolicy.REPEAT_PAGES
                        key = outcome.next
                        fed = outcome.added.takeIf { it || session.finished }
                    }
                }
            }
            return fed == true
        }

        /** Не раньше чем через [RadioPolicy.RETRY_AFTER] просить снова; всегда `false` — «ничего не дописано». */
        private fun pause(session: Session): Boolean {
            session.retryAt = clock.now() + RadioPolicy.RETRY_AFTER
            return false
        }

        /** Просит страницу [key] и, если очередь за это время не ушла, дописывает её новое. */
        private suspend fun fetch(
            radio: QueueContext.Radio,
            base: Int,
            key: String?,
        ): Outcome =
            when (val answer = online.radio(radio.provider, radio.seed, key)) {
                is OnlineRadio.Failed -> Outcome.Failed
                is OnlineRadio.Page -> {
                    // Пока шла сеть, человек мог уйти из радио или поправить очередь: страница уже не нужна.
                    val now = playback.queue.value
                    if (now.context == radio && now.contextItems.size == base) {
                        append(radio, now, answer.tracks)?.let { Outcome.Fetched(it, answer.next) } ?: Outcome.Stuck
                    } else {
                        Outcome.Dropped
                    }
                }
            }

        /** Дописывает новое со страницы: `true` — дописано, `false` — на странице одни повторы, `null` — не вышло. */
        private suspend fun append(
            radio: QueueContext.Radio,
            queue: PlaybackQueue,
            tracks: List<OnlineTrack>,
        ): Boolean? {
            val known = online.known(radio.provider, tracks)
            val fresh = freshIndices(tracks, known, queue).take(RadioPolicy.LIMIT - queue.contextItems.size)
            return if (fresh.isEmpty()) false else extend(radio, queue, fresh.map(tracks::get))
        }

        /** Заводит [chosen] в каталог и дописывает в очередь: `true` — дописано, `null` — не вышло. */
        private suspend fun extend(
            radio: QueueContext.Radio,
            queue: PlaybackQueue,
            chosen: List<OnlineTrack>,
        ): Boolean? {
            val ids = online.add(radio.provider, chosen)?.takeIf { it.size == chosen.size }
            val items = ids?.let { chosen.zip(it) { track, id -> track.toRadioItem(id) } }
            // Конец загруженного мог случиться, пока шла сеть: тогда играем дальше, нажимать человеку нечего.
            val stalled = playback.queue.value.endedWith(playback.state.value)
            val extended = items != null && playback.extend(radio, queue.contextItems.size, items)
            if (extended && stalled) playback.next()
            return true.takeIf { extended }
        }

        /** Чем кончилась попытка получить страницу. */
        private sealed interface Outcome {
            data class Fetched(
                val added: Boolean,
                val next: String?,
            ) : Outcome

            /** Провайдер не ответил. */
            data object Failed : Outcome

            /** Ответил, но страницу не удалось завести в каталог и очередь. */
            data object Stuck : Outcome

            /** Ответил, но очередь за это время ушла: страница не нужна. */
            data object Dropped : Outcome
        }

        private class Session(
            val radio: QueueContext.Radio,
            var next: String?,
            var finished: Boolean,
        ) {
            var retryAt: Instant? = null
        }

        private companion object {
            val LOADING = setOf(PlaybackState.Playing, PlaybackState.Ended)
        }
    }
