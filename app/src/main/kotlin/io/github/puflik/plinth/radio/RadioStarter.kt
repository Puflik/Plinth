package io.github.puflik.plinth.radio

import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.core.RadioProblem
import io.github.puflik.plinth.di.ApplicationScope
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.online.OnlineProviderIds
import io.github.puflik.plinth.online.OnlineRadio
import io.github.puflik.plinth.online.OnlineRepository
import io.github.puflik.plinth.online.OnlineSettings
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пункт «Радио» (Ю4в): находит затравку у провайдера, берёт первую страницу и только тогда заменяет контекст
 * очереди — до этого очередь остаётся прежней. Затравка — источник провайдера у трека из каталога, а у файла с
 * диска и трека другого провайдера — первая песня из поиска по «исполнитель название» (по действию человека; поиск
 * идёт только у провайдера радио, остальным запрос не уходит). Ответ человеку — вести [RadioNotices]; новое нажатие
 * отменяет прежнее.
 */
@Singleton
class RadioStarter
    @Inject
    constructor(
        private val playback: PlaybackController,
        private val online: OnlineRepository,
        private val settings: OnlineSettings,
        private val feeder: RadioFeeder,
        private val notices: RadioNotices,
        @ApplicationScope private val scope: CoroutineScope,
    ) : RadioLauncher {
        private var job: Job? = null

        override fun start(seed: RadioSeed) {
            job?.cancel()
            job = scope.launch { open(seed) }
        }

        private suspend fun open(seed: RadioSeed) {
            val title = seed.item.title
            val provider = provider()
            val step =
                when {
                    title == null -> Step.Stopped(RadioProblem.NOT_FOUND)
                    provider == null -> Step.Stopped(RadioProblem.NEEDS_YOUTUBE)
                    else -> find(seed, provider, title).andThen { source -> opening(seed, provider, source, title) }
                }
            when (step) {
                is Step.Done -> begin(step.value)
                is Step.Stopped -> notices.tell(step.problem)
            }
        }

        /** Провайдер радио, что включён в настройках; `null` — все выключены. */
        private suspend fun provider(): String? {
            val off = settings.disabledProviders.first()
            return OnlineProviderIds.RADIO.firstOrNull { it !in off }.takeIf { settings.enabled.first() }
        }

        /** Затравка у [provider]: источник трека из каталога или, если его нет, первая песня из поиска. */
        private suspend fun find(
            seed: RadioSeed,
            provider: String,
            title: String,
        ): Step<String> {
            val origin = seed.track?.let { online.origins(it) }?.firstOrNull { it.provider == provider }
            return if (origin != null) Step.Done(origin.source) else search(seed, provider, title)
        }

        private suspend fun search(
            seed: RadioSeed,
            provider: String,
            title: String,
        ): Step<String> {
            val query = listOfNotNull(seed.item.artist?.takeIf(String::isNotBlank), title).joinToString(" ")
            val section = online.searchIn(provider, query)
            val found = section?.results?.firstOrNull { it.kind == OnlineKind.TRACK }
            return if (found != null) Step.Done(found.external) else Step.Stopped(unanswered(section?.problem))
        }

        /** Первая страница радио от [source] и очередь, какой она станет. */
        private suspend fun opening(
            seed: RadioSeed,
            provider: String,
            source: String,
            title: String,
        ): Step<Opening> =
            when (val first = online.radio(provider, source, null)) {
                is OnlineRadio.Failed -> Step.Stopped(unanswered(first.problem))
                is OnlineRadio.Page -> items(seed.item, provider, QueueContext.Radio(provider, source, title), first)
            }

        /** Новые треки первой страницы — в каталог; затравка встаёт первой как есть. */
        private suspend fun items(
            first: QueueItem,
            provider: String,
            context: QueueContext.Radio,
            page: OnlineRadio.Page,
        ): Step<Opening> {
            val alone = PlaybackQueue.EMPTY.play(context, listOf(first), start = 0)
            val known = online.known(provider, page.tracks)
            val chosen = freshIndices(page.tracks, known, alone).take(RadioPolicy.LIMIT - 1).map(page.tracks::get)
            val ids = chosen.takeIf { it.isNotEmpty() }?.let { online.add(provider, it) }
            return if (ids == null || ids.size != chosen.size) {
                Step.Stopped(RadioProblem.NOT_FOUND)
            } else {
                val items = listOf(first) + chosen.zip(ids) { track, id -> track.toRadioItem(id) }
                Step.Done(Opening(context, items, page.next))
            }
        }

        /** Очередь меняется одним шагом; ключ второй страницы — подгрузке до `play`. */
        private fun begin(opening: Opening) {
            feeder.expect(opening.context, opening.next)
            playback.play(opening.context, opening.items, start = 0)
        }

        private fun unanswered(problem: OnlineProblem?) =
            if (problem == OnlineProblem.NO_NETWORK) RadioProblem.NO_NETWORK else RadioProblem.NOT_FOUND

        /** Что нужно, чтобы начать: контекст, элементы очереди (затравка первой) и ключ второй страницы. */
        private class Opening(
            val context: QueueContext.Radio,
            val items: List<QueueItem>,
            val next: String?,
        )

        /** Шаг запуска: либо есть что дальше, либо радио не начать — и человеку скажут почему. */
        private sealed interface Step<out T> {
            data class Done<T>(
                val value: T,
            ) : Step<T>

            data class Stopped(
                val problem: RadioProblem,
            ) : Step<Nothing>
        }

        private inline fun <T, R> Step<T>.andThen(next: (T) -> Step<R>): Step<R> =
            when (this) {
                is Step.Done -> next(value)
                is Step.Stopped -> this
            }
    }
