package io.github.puflik.plinth.online.youtube

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.online.youtube.Recorded.LUCKY
import org.junit.Assert.assertThrows
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Что бросает экстрактор и загрузчик → одна из трёх бед источника (`Network`, `Parse`, `Unavailable`) с текстом, в
 * котором нет ни запроса, ни адреса, ни названий: он идёт в сообщение `CoreError` и в журнал.
 */
class YoutubeErrorsTest {
    private val replay = ReplayDownloader()
    private val table = FakeStatusTable()
    private val source = NewPipeYoutubeSource(replay, table)

    private fun failing(error: () -> Throwable): YoutubeException {
        replay.fail("search.songs.ram", error)
        return assertThrows(YoutubeException::class.java) { source.search("Daft Punk Random Access Memories", 20) }
    }

    @Test
    fun `a refusal by status keeps the text of the table as it is`() {
        val network = failing { HttpRefused(HttpVerdict.Network("http 503, retry after 7 s")) }
        val gone = failing { HttpRefused(HttpVerdict.Unavailable("http 404")) }

        assertThat(network).isInstanceOf(YoutubeException.Network::class.java)
        assertThat(network.text).isEqualTo("http 503, retry after 7 s")
        assertThat(gone).isInstanceOf(YoutubeException.Unavailable::class.java)
        assertThat(gone.text).isEqualTo("http 404")
    }

    @Test
    fun `a timeout is a timeout and does not name the address`() {
        val timeout = failing { SocketTimeoutException("Read timed out: https://music.youtube.com/?q=SECRET") }

        assertThat(timeout).isInstanceOf(YoutubeException.Network::class.java)
        assertThat(timeout.text).isEqualTo("timed out")
    }

    @Test
    fun `no connection names only the class of the failure`() {
        val cases =
            listOf<() -> Throwable>(
                { UnknownHostException("music.youtube.com") },
                { ConnectException("Connection refused: https://music.youtube.com/?q=SECRET") },
                { SSLHandshakeException("handshake_failure") },
                { IOException("unexpected end of stream on https://music.youtube.com/?q=SECRET") },
            )

        val failures = cases.map { failing(it) }

        assertThat(failures.map { it.text })
            .containsExactly("UnknownHostException", "ConnectException", "SSLHandshakeException", "IOException")
            .inOrder()
        failures.forEach { assertThat(it).isInstanceOf(YoutubeException.Network::class.java) }
    }

    @Test
    fun `a network failure that the extractor wrapped is still a network failure`() {
        val wrapped =
            failing { ParsingException("Could not get the search page", UnknownHostException("music.youtube.com")) }
        val refused =
            failing {
                ParsingException(
                    "Could not get",
                    HttpRefused(HttpVerdict.Network("http 429, retry after 30 s")),
                )
            }

        assertThat(wrapped).isInstanceOf(YoutubeException.Network::class.java)
        assertThat(wrapped.text).isEqualTo("UnknownHostException")
        assertThat(refused.text).isEqualTo("http 429, retry after 30 s")
    }

    @Test
    fun `a captcha asks to wait the way 429 does, by the table`() {
        val captcha = failing { ReCaptchaException("reCaptcha Challenge requested", "https://www.youtube.com/") }

        assertThat(captcha).isInstanceOf(YoutubeException.Network::class.java)
        assertThat(captcha.text).isEqualTo("http 429")
        assertThat(table.calls).containsExactly(429 to null)
    }

    @Test
    fun `anything else that goes wrong is a parse failure named by its class`() {
        val parse = failing { ParsingException("Could not parse https://music.youtube.com/?q=SECRET") }
        val cast = failing { ClassCastException("class A cannot be cast to class B (SECRET)") }
        val state = failing { IllegalStateException("SECRET") }

        assertThat(listOf(parse, cast, state).map { it.text })
            .containsExactly("ParsingException", "ClassCastException", "IllegalStateException")
            .inOrder()
        listOf(parse, cast, state).forEach { assertThat(it).isInstanceOf(YoutubeException.Parse::class.java) }
    }

    @Test
    fun `no text of an error carries the query, the address or the name`() {
        val errors =
            listOf<() -> Throwable>(
                { IOException("https://music.youtube.com/?q=SECRET") },
                { ParsingException("SECRET title") },
                { SocketTimeoutException("SECRET") },
                { ReCaptchaException("SECRET", "https://www.youtube.com/SECRET") },
            )

        for (error in errors) {
            val failure = failing(error)

            assertThat(failure.text).doesNotContain("SECRET")
            assertThat(failure.message).doesNotContain("SECRET")
            assertThat(failure.text).doesNotContain("youtube.com")
        }
    }

    @Test
    fun `an interrupted call stays interrupted`() {
        try {
            val interrupted = failing { InterruptedException("SECRET") }

            assertThat(interrupted).isInstanceOf(YoutubeException.Network::class.java)
            assertThat(interrupted.text).isEqualTo("interrupted")
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a call that takes longer than its budget ends as a timeout, and the next call has its own budget`() {
        val slow = ReplayDownloader().delayEachRequest(150)
        val tight =
            NewPipeYoutubeSource(slow, table, YoutubeBudgets(search = 300.milliseconds, extract = 300.milliseconds))
        val started = System.nanoTime()

        val failure = assertThrows(YoutubeException.Network::class.java) { tight.resolve(LUCKY) }

        assertThat(failure.text).isEqualTo("timed out")
        // Извлечение — не меньше семи запросов: из-за срока их было меньше, и вышли быстрее, чем они все.
        assertThat(slow.seen.size).isLessThan(7)
        assertThat((System.nanoTime() - started) / NANOS_PER_MILLI).isLessThan(1_500L)
        assertThat(CallBudget.leftMillis()).isNull()
        val ready = NewPipeYoutubeSource(ReplayDownloader(), table, YoutubeBudgets(extract = 5_000.milliseconds))
        assertThat(ready.resolve(LUCKY)).hasSize(1)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
