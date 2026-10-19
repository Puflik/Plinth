package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.StreamLookup
import io.github.puflik.plinth.ffi.TrackId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Р1.11г (`docs/work/r1-11d.md`, находки R1 №27 и v0.2 №33): на холодном старте поиск, альбом и поток
 * плеера идут в ядро одновременно с `OnlineSwitch`, который включает источники, и, кто первый,
 * тот и прав: поиск, пришедший раньше, получал «источники выключены» и молча пустой ответ, а
 * сохранённый сетевой трек очереди — `Unavailable` и пропуск. Репозиторий-вратарь ждёт первого
 * применения переключателя.
 *
 * `GatedOnlineRepository(inner, waitLimit)` — новый класс (сигнатура в файле задачи).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatedOnlineRepositoryTest {
    private val inner = FakeOnlineRepository()
    private val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)

    @Test
    fun `a search that comes before the switch waits for it and then finds`() =
        runTest {
            val search = async { gated.search("concert") }
            runCurrent()
            val waiting = !search.isCompleted
            val askedEarly = inner.searches.toList()

            gated.setEnabled(true)
            runCurrent()
            val atOnce = search.isCompleted

            assertThat(waiting).isTrue()
            assertThat(askedEarly).isEmpty()
            assertThat(atOnce).isTrue()
            assertThat(
                search
                    .await()
                    .single()
                    .results,
            ).isNotEmpty()
            assertThat(inner.searches).containsExactly("concert")
        }

    @Test
    fun `a switch that turns the sources off lets the search through at once`() =
        runTest {
            val search = async { gated.search("concert") }
            runCurrent()

            gated.setEnabled(false)
            runCurrent()
            val atOnce = search.isCompleted

            assertThat(atOnce).isTrue()
            assertThat(search.await()).isEmpty()
            assertThat(inner.searches).isEmpty()
        }

    @Test
    fun `a switch that fails still lets the search through`() =
        runTest {
            val failing =
                object : OnlineRepository by inner {
                    override suspend fun setEnabled(enabled: Boolean): Unit = error("the core did not open")
                }
            val gated = GatedOnlineRepository(failing, waitLimit = 5.seconds)
            val search = async { gated.search("concert") }
            runCurrent()

            runCatching { gated.setEnabled(true) }
            runCurrent()

            assertThat(search.isCompleted).isTrue()
        }

    @Test
    fun `a switch that never comes does not hold the search for ever`() =
        runTest {
            inner.setEnabled(true)
            val search = async { gated.search("concert") }
            runCurrent()

            advanceTimeBy(4_999)
            runCurrent()
            val stillWaiting = !search.isCompleted
            advanceTimeBy(2)
            runCurrent()

            assertThat(stillWaiting).isTrue()
            assertThat(
                search
                    .await()
                    .single()
                    .results,
            ).isNotEmpty()
        }

    @Test
    fun `a search cancelled while it waits never reaches the repository`() =
        runTest {
            val search = launch { gated.search("concert") }
            runCurrent()

            search.cancel()
            runCurrent()
            gated.setEnabled(true)
            runCurrent()

            assertThat(inner.searches).isEmpty()
        }

    @Test
    fun `an album waits for the switch like a search`() =
        runTest {
            val album = async { gated.album(FakeOnlineRepository.PROVIDER, TestConcert.ITEM) }
            runCurrent()
            val waiting = !album.isCompleted

            gated.setEnabled(true)
            runCurrent()
            val atOnce = album.isCompleted

            assertThat(waiting).isTrue()
            assertThat(atOnce).isTrue()
            assertThat(album.await()).isInstanceOf(OnlineAlbum.Tracks::class.java)
        }

    @Test
    fun `the catalog does not wait for the switch`() =
        runTest {
            val added = async { gated.add(FakeOnlineRepository.PROVIDER, TestConcert.tracks) }
            runCurrent()
            val addedAtOnce = added.isCompleted
            val known = async { gated.known(FakeOnlineRepository.PROVIDER, TestConcert.tracks) }
            runCurrent()
            val knownAtOnce = known.isCompleted

            assertThat(addedAtOnce).isTrue()
            assertThat(knownAtOnce).isTrue()
            assertThat(known.await()).containsExactlyElementsIn(checkNotNull(added.await()))
        }

    @Test
    fun `a stream asked before the switch waits for it`() {
        val track = readyTrack()
        val answer = CompletableFuture<StreamLookup>()
        val caller = thread(isDaemon = true, name = "loader") { answer.complete(gated.stream(track, metered = false)) }

        val blocked = waitUntilBlocked(caller)
        runBlocking { gated.setEnabled(true) }

        assertThat(blocked).isTrue()
        assertThat(answer.get(5, TimeUnit.SECONDS)).isInstanceOf(StreamLookup.Found::class.java)
    }

    @Test
    fun `a stream that waits can be interrupted`() {
        val track = readyTrack()
        val failure = CompletableFuture<Throwable>()
        val caller =
            thread(isDaemon = true, name = "loader") {
                try {
                    gated.stream(track, metered = false)
                    failure.complete(IllegalStateException("returned"))
                } catch (interrupted: InterruptedException) {
                    failure.complete(interrupted)
                }
            }

        val blocked = waitUntilBlocked(caller)
        caller.interrupt()

        assertThat(blocked).isTrue()
        assertThat(failure.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedException::class.java)
    }

    @Test
    fun `a switch that never comes does not hold the stream for ever`() {
        val track = readyTrack()
        val gated = GatedOnlineRepository(inner, waitLimit = 300.milliseconds)
        val answer = CompletableFuture<StreamLookup>()
        val started = System.nanoTime()
        thread(isDaemon = true, name = "loader") { answer.complete(gated.stream(track, metered = false)) }

        val lookup = answer.get(5, TimeUnit.SECONDS)

        val waited = (System.nanoTime() - started) / NANOS_PER_MILLI
        assertThat(lookup).isInstanceOf(StreamLookup.Found::class.java)
        assertThat(waited).isAtLeast(250L)
    }

    @Test
    fun `the switch of the application opens the gate when it starts`() =
        runTest {
            val search = async { gated.search("concert") }
            runCurrent()

            OnlineSwitch(FakeOnlineSettings(), gated, backgroundScope).start()
            runCurrent()
            val atOnce = search.isCompleted

            assertThat(atOnce).isTrue()
            assertThat(
                search
                    .await()
                    .single()
                    .results,
            ).isNotEmpty()
        }

    /** Трек в каталоге репозитория, источники которого включены: ждёт только вратарь. */
    private fun readyTrack(): TrackId =
        runBlocking {
            inner.setEnabled(true)
            checkNotNull(inner.add(FakeOnlineRepository.PROVIDER, TestConcert.tracks)).first()
        }

    /** Поток ждёт в вызове, а не кончил его; `false` — вызов вернулся, не дождавшись. */
    private fun waitUntilBlocked(thread: Thread): Boolean {
        val deadline = System.nanoTime() + BLOCK_WAIT_MS * NANOS_PER_MILLI
        while (System.nanoTime() < deadline && thread.state !in PARKED && thread.state != Thread.State.TERMINATED) {
            Thread.sleep(10)
        }
        return thread.state in PARKED
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val BLOCK_WAIT_MS = 3_000L
        val PARKED = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
    }
}
