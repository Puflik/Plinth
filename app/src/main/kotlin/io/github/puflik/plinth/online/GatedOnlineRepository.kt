package io.github.puflik.plinth.online

import io.github.puflik.plinth.audio.engine.StreamLookup
import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.ffi.TrackId
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
 * приложение, поэтому ждёт оно: [search], [album] и [stream] задерживаются до первого [setEnabled],
 * но не дольше [waitLimit] — вышел срок, вызов идёт дальше, и ядро решает само. Каталог ([known],
 * [add]) источников не требует и не ждёт.
 *
 * Ворота открывает любой исход [setEnabled], и упавший тоже: ждать больше нечего.
 */
class GatedOnlineRepository(
    private val inner: OnlineRepository,
    private val waitLimit: Duration = 60.seconds,
) : OnlineRepository by inner {
    // Два входа к одним воротам: корутинам — без блокировки потока, загрузчику плеера — с ней.
    private val opened = CompletableDeferred<Unit>()
    private val openedForThreads = CountDownLatch(1)

    override suspend fun setEnabled(
        enabled: Boolean,
        disabled: Set<String>,
    ) {
        try {
            inner.setEnabled(enabled, disabled)
        } finally {
            opened.complete(Unit)
            openedForThreads.countDown()
        }
    }

    override suspend fun search(query: String): List<OnlineSection> {
        awaitSwitch()
        return inner.search(query)
    }

    override suspend fun album(
        provider: String,
        item: String,
    ): OnlineAlbum {
        awaitSwitch()
        return inner.album(provider, item)
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
