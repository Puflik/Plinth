package io.github.puflik.plinth.online.youtube

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.online.youtube.Recorded.KROVI
import io.github.puflik.plinth.online.youtube.Recorded.LUCKY
import org.junit.Assert.assertThrows
import org.junit.Test
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * `stream` на записанных ответах: адрес варианта с заголовками плеера, выбор Opus 251 и AAC 140 и ничего больше,
 * свежая ссылка на каждый вызов и единственное исключение — заготовка потоков, оставшаяся от `resolve`.
 *
 * Как `stream` переживает отказ первой ссылки (телефон, `docs/work/yu3a.md`, п. 10): повторный вызов извлекает заново и
 * получает другую ссылку, другой формат — другой вариант (ядро идёт к следующему варианту само).
 */
class YoutubeStreamTest {
    private val replay = ReplayDownloader()
    private val clock = AtomicLong(0L)
    private val source = NewPipeYoutubeSource(replay, FakeStatusTable(), nanoTime = clock::get)

    private val visionUserAgent = YoutubeParsingHelper.getVisionOsUserAgent(Localization.DEFAULT)

    @Test
    fun `the Opus variant is the 251 stream with the headers that the player needs`() {
        val stream = source.stream("$LUCKY/251")

        assertThat(stream.url).matches(linkOf(OPUS_URL_A))
        assertThat(stream.format).isEqualTo(YoutubeFormat.OPUS)
        assertThat(stream.bitrateKbps).isEqualTo(160)
        // User-Agent того клиента, что выдал ссылку, и Range: без него первый запрос идёт со скоростью 10 КиБ/с.
        assertThat(stream.headers).containsExactly("User-Agent" to visionUserAgent, "Range" to "bytes=0-").inOrder()
        assertThat(visionUserAgent).contains("visionos")
    }

    @Test
    fun `the AAC variant is the 140 stream`() {
        val stream = source.stream("$LUCKY/140")

        assertThat(stream.url).matches(linkOf(AAC_URL_A))
        assertThat(stream.format).isEqualTo(YoutubeFormat.AAC)
        assertThat(stream.bitrateKbps).isEqualTo(128)
        assertThat(stream.headers).contains("Range" to "bytes=0-")
    }

    @Test
    fun `a link that another client issued gets no agent of ours, only Range`() {
        val other = ReplayDownloader().serve("player.lucky", "stream/player-lucky-other-client.json")

        val stream = NewPipeYoutubeSource(other, FakeStatusTable()).stream("$LUCKY/251")

        assertThat(stream.url).contains("c=ANDROID_VR")
        assertThat(stream.headers).containsExactly("Range" to "bytes=0-")
    }

    @Test
    fun `only 251 and 140 are variants, whatever else the video has`() {
        for (variant in listOf(
            "$LUCKY/250",
            "$LUCKY/249",
            "$LUCKY/139",
            "$LUCKY/141",
            "$LUCKY/18",
            "$LUCKY/0",
            "$LUCKY/2510",
        )) {
            assertThrows(variant, YoutubeException.Unavailable::class.java) { source.stream(variant) }
        }
        for (broken in listOf("", LUCKY, "$LUCKY/", "/251", "$LUCKY/251/1", "x/251", "../$LUCKY/251", "$LUCKY/ 251")) {
            assertThrows(broken, YoutubeException.Unavailable::class.java) { source.stream(broken) }
        }

        assertThat(replay.seen).isEmpty()
    }

    @Test
    fun `a variant that the video lacks is unavailable, and the other one then needs no second extraction`() {
        val noOpus = ReplayDownloader().serve("player.lucky", "stream/player-lucky-no-opus.json")
        val sourceWithoutOpus = NewPipeYoutubeSource(noOpus, FakeStatusTable(), nanoTime = clock::get)

        assertThrows(YoutubeException.Unavailable::class.java) { sourceWithoutOpus.stream("$LUCKY/251") }
        val aac = sourceWithoutOpus.stream("$LUCKY/140")

        assertThat(aac.format).isEqualTo(YoutubeFormat.AAC)
        // Реестр ядра идёт к следующему варианту сразу: второй вариант не стоит ещё 2–3 с извлечения.
        assertThat(noOpus.count("player.lucky")).isEqualTo(1)
    }

    @Test
    fun `every call gets a fresh link, so a retry after a refusal does not get the refused one`() {
        val first = source.stream("$LUCKY/251")
        val second = source.stream("$LUCKY/251")

        assertThat(first.url).matches(linkOf(OPUS_URL_A))
        assertThat(second.url).matches(linkOf(OPUS_URL_B))
        assertThat(replay.count("player.lucky")).isEqualTo(2)
    }

    @Test
    fun `the other format after a refusal is a fresh extraction too`() {
        val opus = source.stream("$LUCKY/251")
        val aac = source.stream("$LUCKY/140")

        assertThat(opus.url).matches(linkOf(OPUS_URL_A))
        assertThat(aac.url).matches(linkOf(AAC_URL_B))
        assertThat(replay.count("player.lucky")).isEqualTo(2)
    }

    @Test
    fun `the streams that resolve found go to the first stream call and only to it`() {
        source.resolve(LUCKY)

        val first = source.stream("$LUCKY/251")
        val second = source.stream("$LUCKY/251")

        assertThat(first.url).matches(linkOf(OPUS_URL_A))
        assertThat(second.url).matches(linkOf(OPUS_URL_B))
        assertThat(replay.count("player.lucky")).isEqualTo(2)
    }

    @Test
    fun `what resolve found is kept for a video that stays unplayed, and expires`() {
        source.resolve(LUCKY)
        clock.addAndGet(119.seconds.inWholeNanoseconds)
        source.stream("$LUCKY/251")
        assertThat(replay.count("player.lucky")).isEqualTo(1)

        source.resolve(LUCKY)
        clock.addAndGet(3.minutes.inWholeNanoseconds)
        source.stream("$LUCKY/251")

        // Два resolve — два извлечения, и ещё одно у stream: заготовка, которой больше двух минут, не живёт.
        assertThat(replay.count("player.lucky")).isEqualTo(3)
    }

    @Test
    fun `the streams of one video are not given to another`() {
        source.resolve(LUCKY)

        val krovi = source.stream("$KROVI/251")

        assertThat(replay.count("player.krovi")).isEqualTo(1)
        assertThat(krovi.url).contains("clen=4396891")
    }

    @Test
    fun `a variant that resolve did not find is unavailable without network and keeps the others`() {
        val noOpus = ReplayDownloader().serve("player.lucky", "stream/player-lucky-no-opus.json")
        val sourceWithoutOpus = NewPipeYoutubeSource(noOpus, FakeStatusTable(), nanoTime = clock::get)
        assertThat(
            sourceWithoutOpus
                .resolve(LUCKY)
                .single()
                .variants
                .map(YoutubeVariant::id),
        ).containsExactly("$LUCKY/140")

        assertThrows(YoutubeException.Unavailable::class.java) { sourceWithoutOpus.stream("$LUCKY/251") }
        val aac = sourceWithoutOpus.stream("$LUCKY/140")

        assertThat(aac.format).isEqualTo(YoutubeFormat.AAC)
        assertThat(noOpus.count("player.lucky")).isEqualTo(1)
    }

    private companion object {
        /** Экстрактор дописывает к ссылке VisionOS случайный `cpn` (16 знаков): ссылка — запись плюс он. */
        fun linkOf(recorded: String) = Pattern.compile(Pattern.quote(recorded) + "&cpn=[A-Za-z0-9_-]{16}")

        const val STREAM_URL =
            "https://rr1---sn-test.googlevideo.com/videoplayback?expire=1999999999&ip=203.0.113.7&id=o-TEST&itag=%s" +
                "&source=youtube&requiressl=yes&mime=%s&clen=%s&dur=%s&c=VISIONOS&sig=%s"
        val OPUS_URL_A = STREAM_URL.format("251", "audio%2Fwebm", "6214234", "369.641", "SIGA")
        val OPUS_URL_B = STREAM_URL.format("251", "audio%2Fwebm", "6214234", "369.641", "SIGB")
        val AAC_URL_A = STREAM_URL.format("140", "audio%2Fmp4", "5983881", "369.626", "SIGA")
        val AAC_URL_B = STREAM_URL.format("140", "audio%2Fmp4", "5983881", "369.626", "SIGB")
    }
}
