package io.github.puflik.plinth.online

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.NetAnswer
import io.github.puflik.plinth.ffi.NetRequest
import io.github.puflik.plinth.ffi.NetTransport
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.PlinthCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Р1.11а (`docs/work/r1-11a.md`, находка 10 ревью v0.2): `CoreOnlineRepository`
 * на настоящем ядре отвечает на отмену сразу, не ждёт сети и не доигрывает
 * повторы. Сеть здесь стоит на месте, пока её не отпустят, как «сеть есть, но
 * молчит»; ядро ждёт её на блокирующем вызове.
 */
class CoreOnlineRepositoryCancelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dataDir = File(context.cacheDir, "core-cancel-" + UUID.randomUUID())
    private val core = PlinthCore(LogLevel.INFO, dataDir, CoreErrors())
    private val network = StallingNetwork()
    private val repository = CoreOnlineRepository(core, network, Dispatchers.IO)

    @Before
    fun connect() {
        runBlocking { repository.setEnabled(true) }
    }

    @After
    fun close() {
        network.stalling = false
        core.close()
        dataDir.deleteRecursively()
    }

    /** Старый код: `join` ждёт, пока вернётся блокирующий вызов, то есть пока сеть не отпустят. */
    @Test
    fun cancelling_a_search_returns_at_once() {
        runBlocking {
            val search = launch(Dispatchers.Default) { repository.search("grateful dead") }
            assertThat(network.entered.tryAcquire(ENTER_S, TimeUnit.SECONDS)).isTrue()

            val joined = withTimeoutOrNull(2.seconds) { search.cancelAndJoin() }
            network.stalling = false // Красный тест не должен ждать минуту, пока старый код доиграет повторы.

            assertThat(joined).isNotNull()
        }
    }

    @Test
    fun a_cancelled_search_is_not_retried() {
        runBlocking {
            val search = launch(Dispatchers.Default) { repository.search("grateful dead") }
            assertThat(network.entered.tryAcquire(ENTER_S, TimeUnit.SECONDS)).isTrue()

            search.cancel()
            delay(200.milliseconds)
            network.stalling = false
            // Повторы ядра — через 0,5 и 1 с после неудачной попытки: за три секунды они бы были.
            delay(3.seconds)

            assertThat(network.calls.get()).isEqualTo(1)
        }
    }

    /**
     * Три отменённых поиска подряд не отправляют провайдера на отдых: четвёртый находит.
     * Старый код доигрывает все повторы отменённого поиска (0,5 и 1 с) и считает три отказа.
     */
    @Test
    fun cancelled_searches_do_not_bench_the_provider() {
        runBlocking {
            repeat(3) {
                network.stalling = true
                // Повторы старого кода оставляют лишние разрешения: тест не должен принять их за вход.
                network.entered.drainPermits()
                val search = launch(Dispatchers.Default) { repository.search("grateful dead") }
                assertThat(network.entered.tryAcquire(ENTER_S, TimeUnit.SECONDS)).isTrue()
                search.cancel()
                delay(200.milliseconds)
                network.stalling = false
                delay(2.seconds)
            }
            network.answer = SEARCH

            val sections = repository.search("grateful dead")

            assertThat(sections).hasSize(1)
            assertThat(sections[0].problem).isNull()
            assertThat(sections[0].results.map { it.title }).containsExactly(TestConcert.TITLE)
        }
    }

    /** Страж: поиск без отмены ходит через ту же границу и отвечает. */
    @Test
    fun a_search_nobody_cancels_still_answers() {
        runBlocking {
            network.stalling = false
            network.answer = SEARCH

            val sections = repository.search("grateful dead")

            assertThat(sections.single().results.map { it.title }).containsExactly(TestConcert.TITLE)
        }
    }

    /** Страж: без ответа и без отмены секция говорит «нет сети», а не пропадает. */
    @Test
    fun a_search_the_network_does_not_answer_is_a_problem_of_the_section() {
        runBlocking {
            network.stalling = false

            val sections = repository.search("grateful dead")

            assertThat(sections.single().problem).isEqualTo(OnlineProblem.NO_NETWORK)
        }
    }

    /**
     * Сеть, которая пока [stalling] стоит на месте (до 20 с), а потом отвечает
     * [answer] или «нет ответа». [entered] — сколько запросов вошло и ещё не взято тестом.
     */
    private class StallingNetwork : NetTransport {
        @Volatile var stalling = true

        @Volatile var answer: String? = null
        val entered = Semaphore(0)
        val calls = AtomicInteger()

        override fun get(request: NetRequest): NetAnswer {
            calls.incrementAndGet()
            entered.release()
            val until = System.nanoTime() + STALL_LIMIT_S * NANOS_PER_SECOND
            while (stalling && System.nanoTime() < until) Thread.sleep(POLL_MS)
            val body = answer ?: return NetAnswer.NoAnswer("SocketTimeoutException")
            return NetAnswer.Response(OK, body.toByteArray())
        }
    }

    private companion object {
        const val ENTER_S = 5L
        const val STALL_LIMIT_S = 20L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val POLL_MS = 10L
        const val OK = 200

        val SEARCH =
            """
            {"response": {"numFound": 1, "start": 0, "docs": [
              {"identifier": "${TestConcert.ITEM}", "title": "${TestConcert.TITLE}",
               "creator": "${TestConcert.ARTIST}", "year": ${TestConcert.YEAR}}]}}
            """.trimIndent()
    }
}
