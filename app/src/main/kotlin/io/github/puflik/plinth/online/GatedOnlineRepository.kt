package io.github.puflik.plinth.online

import io.github.puflik.plinth.audio.engine.StreamLookup
import io.github.puflik.plinth.core.CoreProblem
import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.CoreFailure
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.ffi.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Онлайн-источники, которые ждут первого применения переключателя (R1 №27, v0.2 №33).
 *
 * На холодном старте `OnlineSwitch` включает источники ([setEnabled]), а поиск, альбом и
 * загрузчик плеера (сохранённый сетевой трек очереди) идут в ядро одновременно с ним: кто первый,
 * тот и прав. Ядро не различает «выключены» и «ещё не включены» — получить «источники
 * выключены» значило молча пустой поиск или `Unavailable` и пропуск трека. Порядок знает только
 * приложение, поэтому ждёт оно: [search], [searchIn], [album], [radio] и [stream] задерживаются до
 * первого [setEnabled], но не дольше [waitLimit] — вышел срок, вызов идёт дальше, и ядро решает само.
 * Каталог ([known], [add], [origins]) источников не требует и не ждёт.
 *
 * Ворота открывает любой исход [setEnabled], и упавший тоже: ждать больше нечего.
 */
class GatedOnlineRepository(
    private val inner: OnlineRepository,
    private val waitLimit: Duration = 60.seconds,
) : OnlineRepository by inner,
    OnlineReadiness {
    // Два входа к одним воротам: корутинам — без блокировки потока, загрузчику плеера — с ней.
    private val opened = CompletableDeferred<Unit>()
    private val openedForThreads = CountDownLatch(1)

    // Этот монитор защищает только память; физическое применение всегда вне него.
    private val lifecycleLock = Any()
    private var revision = 0L
    private var current: Attempt? = null
    private var applyTail = CompletableDeferred(Unit)
    private val waiters = mutableSetOf<Waiter>()

    override suspend fun setEnabled(
        enabled: Boolean,
        disabled: Set<String>,
    ) {
        val attempt = begin(OnlineConfiguration(enabled, disabled).normalized())
        try {
            attempt.previous.await()
            inner.setEnabled(attempt.configuration.enabled, attempt.configuration.disabledProviders)
            finish(attempt, OnlineReady.Applied(attempt.revision))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val problem =
                when ((failure as? CoreFailure)?.error?.problem) {
                    CoreProblem.NETWORK -> OnlineProblem.NO_NETWORK
                    CoreProblem.UNAVAILABLE -> OnlineProblem.PROVIDER_DOWN
                    else -> OnlineProblem.BROKEN
                }
            finish(attempt, OnlineReady.Failed(problem))
            throw failure
        } finally {
            // Отменённая ожидающая попытка не выпускает следующую раньше своей предшественницы.
            attempt.previous.invokeOnCompletion { attempt.finished.complete(Unit) }
            opened.complete(Unit)
            openedForThreads.countDown()
        }
    }

    override suspend fun awaitConfiguration(configuration: OnlineConfiguration): OnlineReady =
        withTimeoutOrNull(waitLimit) {
            val waiter =
                synchronized(lifecycleLock) {
                    val normalized = configuration.normalized()
                    val attempt = current?.takeIf { it.configuration == normalized }
                    Waiter(normalized, attempt?.revision).also { waiter ->
                        val ready = attempt?.ready
                        if (ready != null) waiter.result.complete(ready) else waiters.add(waiter)
                    }
                }
            try {
                val ready = waiter.result.await()
                synchronized(lifecycleLock) {
                    // За время возобновления waiter могла начаться новая попытка.
                    if (waiter.revision == current?.revision) ready else OnlineReady.Superseded
                }
            } finally {
                synchronized(lifecycleLock) { waiters.remove(waiter) }
            }
        } ?: OnlineReady.TimedOut

    private fun begin(configuration: OnlineConfiguration): Attempt =
        synchronized(lifecycleLock) {
            val attempt = Attempt(configuration, ++revision, applyTail)
            applyTail = attempt.finished
            current = attempt
            val superseded = waiters.filter { it.revision != null }
            waiters.removeAll(superseded.toSet())
            waiters.forEach { waiter ->
                if (waiter.configuration == configuration) waiter.revision = attempt.revision
            }
            // Сначала меняем всю память, затем будим продолжения, включая Unconfined.
            superseded.forEach { it.result.complete(OnlineReady.Superseded) }
            attempt
        }

    private fun finish(
        attempt: Attempt,
        ready: OnlineReady,
    ) {
        synchronized(lifecycleLock) {
            if (current !== attempt) return
            attempt.ready = ready
            val completed = waiters.filter { it.revision == attempt.revision }
            waiters.removeAll(completed.toSet())
            completed.forEach { it.result.complete(ready) }
        }
    }

    private class Attempt(
        val configuration: OnlineConfiguration,
        val revision: Long,
        val previous: CompletableDeferred<Unit>,
    ) {
        val finished = CompletableDeferred<Unit>()
        var ready: OnlineReady? = null
    }

    private class Waiter(
        val configuration: OnlineConfiguration,
        var revision: Long?,
    ) {
        val result = CompletableDeferred<OnlineReady>()
    }

    override suspend fun search(query: String): List<OnlineSection> {
        awaitSwitch()
        return inner.search(query)
    }

    override suspend fun searchIn(
        provider: String,
        query: String,
    ): OnlineSection? {
        awaitSwitch()
        return inner.searchIn(provider, query)
    }

    override suspend fun album(
        provider: String,
        item: String,
    ): OnlineAlbum {
        awaitSwitch()
        return inner.album(provider, item)
    }

    override suspend fun radio(
        provider: String,
        seed: String,
        page: String?,
    ): OnlineRadio {
        awaitSwitch()
        return inner.radio(provider, seed, page)
    }

    /** Блокирует поток загрузчика; [InterruptedException] идёт наружу — загрузчик прервали. */
    override fun stream(
        track: TrackId,
        metered: Boolean,
        undecodable: Set<AudioFormat>,
    ): StreamLookup {
        openedForThreads.await(waitLimit.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        return inner.stream(track, metered, undecodable)
    }

    /** Отмена корутины — обычная; вышел срок — идём дальше. */
    private suspend fun awaitSwitch() {
        withTimeoutOrNull(waitLimit) { opened.await() }
    }
}
