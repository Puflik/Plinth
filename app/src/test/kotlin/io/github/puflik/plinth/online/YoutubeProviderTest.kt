package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineQuality
import io.github.puflik.plinth.ffi.ProviderException
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.ALBUM
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.ALBUM_TRACK_2
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.QUIET
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.SONG
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.SONG_NO_OPUS
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.UA
import io.github.puflik.plinth.online.youtube.YoutubeException
import io.github.puflik.plinth.online.youtube.YoutubeFormat
import io.github.puflik.plinth.online.youtube.YoutubeResult
import io.github.puflik.plinth.online.youtube.YoutubeSource
import io.github.puflik.plinth.online.youtube.YoutubeStream
import io.github.puflik.plinth.online.youtube.YoutubeTrack
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * YouTube как провайдер ядра (Ю3в): `YoutubeSource` на языке `OnlineProvider`. Имя и формат идентификаторов уходят
 * в каталог и журнал пользователя, поэтому тест держит их дословно: имя `youtube`, трек — `<видео>`, вариант —
 * `<видео>/<itag>`, и ни один вариант не принадлежит чужому видео.
 */
class YoutubeProviderTest {
    private val source = FakeYoutubeSource()
    private var now = NOW_MILLIS
    private val provider = YoutubeProvider(source) { now }

    @Test
    fun `it is youtube, finds songs and albums and promises standard quality`() {
        assertThat(provider.id).isEqualTo("youtube")
        assertThat(provider.finds).containsExactly(OnlineKind.TRACK, OnlineKind.ALBUM)
        assertThat(provider.bestQuality).isEqualTo(OnlineQuality.STANDARD)
    }

    @Test
    fun `search hands the text and the limit to the source as they are`() {
        provider.search("piano", 7)
        provider.search("Фортепиано  ", 100)

        assertThat(source.calls).containsExactly("search:piano:7", "search:Фортепиано  :100").inOrder()
    }

    @Test
    fun `search results keep their order kind id title artist and length`() {
        val found = provider.search("piano", 20)

        assertThat(found.map { it.external to it.kind })
            .containsExactly(SONG to OnlineKind.TRACK, ALBUM to OnlineKind.ALBUM)
            .inOrder()
        assertThat(found[0].title).isEqualTo("Piano Man")
        assertThat(found[0].artist).isEqualTo("Billy Joel")
        assertThat(found[0].duration).isEqualTo(338.seconds)
        assertThat(found[0].year).isNull()
        assertThat(found[1].title).isEqualTo("Piano Album")
        assertThat(found[1].duration).isNull()
    }

    @Test
    fun `a video resolves to one track with the variants it has, named after itself`() {
        val tracks = provider.resolve(SONG_NO_OPUS)

        assertThat(tracks).hasSize(1)
        val track = tracks.single()
        assertThat(track.external).isEqualTo(SONG_NO_OPUS)
        assertThat(track.title).isEqualTo("Фортепианный концерт")
        assertThat(track.artist).isEqualTo("Группа КИНО")
        assertThat(track.duration).isEqualTo(240.seconds)
        assertThat(track.album).isNull()
        assertThat(track.number).isNull()
        assertThat(track.mbid).isNull()
        assertThat(track.variants.map { Triple(it.external, it.format, it.bitrateKbps) })
            .containsExactly(Triple("-a_B9xYz01q/140", AudioFormat.AAC, 128))
    }

    @Test
    fun `an album resolves to numbered tracks that name both formats, Opus first`() {
        val tracks = provider.resolve(ALBUM)

        assertThat(tracks.map { it.external }).containsExactly(SONG, ALBUM_TRACK_2, "trkDDDDDDD4").inOrder()
        assertThat(tracks.map { it.number }).containsExactly(1, 2, 3).inOrder()
        assertThat(tracks.map { it.album }.distinct()).containsExactly("Piano Album")
        assertThat(tracks[1].variants.map { Triple(it.external, it.format, it.bitrateKbps) })
            .containsExactly(
                Triple("trkCCCCCCC3/251", AudioFormat.OPUS, 160),
                Triple("trkCCCCCCC3/140", AudioFormat.AAC, 128),
            ).inOrder()
    }

    /** Из этого каталог решает, один это трек или два: чужой идентификатор в варианте склеил бы два трека. */
    @Test
    fun `every variant id is the id of its own track and an itag`() {
        val tracks = listOf(SONG, SONG_NO_OPUS, ALBUM, QUIET).flatMap(provider::resolve)

        assertThat(tracks).isNotEmpty()
        for (track in tracks) {
            assertThat(track.variants).isNotEmpty()
            for (variant in track.variants) {
                assertThat(variant.external).isIn(listOf("${track.external}/251", "${track.external}/140"))
            }
        }
    }

    @Test
    fun `a stream keeps the address and the headers as the source gave them`() {
        val stream = provider.stream("dQw4w9WgXcQ/251")

        assertThat(stream.url).startsWith("https://rr1---sn-test.googlevideo.com/videoplayback?")
        assertThat(stream.url).endsWith("source=dQw4w9WgXcQ/251")
        assertThat(stream.headers).containsExactly("User-Agent" to UA, "Range" to "bytes=0-").inOrder()
    }

    @Test
    fun `a stream lives until its link expires less a margin, but not longer than two hours`() {
        val cases =
            mapOf(
                // Типичная ссылка живёт около шести часов: держим не больше двух.
                21_540L to 2.hours,
                8_400L to 2.hours,
                // Ровно два часа и запас: ещё два часа; секундой меньше — на секунду меньше.
                7_800L to 2.hours,
                7_799L to (7_799L - 600L).seconds,
                3_600L to 50.minutes,
                // Запас десять минут: оставшееся меньше запаса — срока нет.
                601L to 1.seconds,
                600L to Duration.ZERO,
                300L to Duration.ZERO,
                0L to Duration.ZERO,
                -100L to Duration.ZERO,
            )
        for ((left, expected) in cases) {
            source.expireAtSeconds = NOW_SECONDS + left

            assertThat(provider.stream("dQw4w9WgXcQ/251").validFor).isEqualTo(expected)
        }
    }

    @Test
    fun `the clock of the app decides how much is left`() {
        source.expireAtSeconds = NOW_SECONDS + 3_600

        now += 30.minutes.inWholeMilliseconds

        assertThat(provider.stream("dQw4w9WgXcQ/251").validFor).isEqualTo(20.minutes)
    }

    @Test
    fun `a link without a usable expiry lives half an hour`() {
        val links =
            listOf(
                "https://rr1.test/videoplayback?n=1&source=x",
                "https://rr1.test/videoplayback?expire=&n=1",
                "https://rr1.test/videoplayback?expire=abc&n=1",
                "https://rr1.test/videoplayback?expire=12abc&n=1",
                "https://rr1.test/videoplayback?expire=99999999999999999999999&n=1",
                "https://rr1.test/videoplayback?notexpire=${NOW_SECONDS + 3_600}&xexpire=${NOW_SECONDS + 3_600}",
            )
        for (link in links) {
            val withLink = YoutubeProvider(streamOf(link)) { now }

            assertThat(withLink.stream("dQw4w9WgXcQ/251").validFor).isEqualTo(30.minutes)
        }
    }

    @Test
    fun `the expiry is found wherever it stands in the query`() {
        val soon = NOW_SECONDS + 3_600
        val links =
            listOf(
                "https://rr1.test/videoplayback?expire=$soon&n=1&source=x",
                "https://rr1.test/videoplayback?n=1&expire=$soon&source=x",
                "https://rr1.test/videoplayback?n=1&source=x&expire=$soon",
            )
        for (link in links) {
            val withLink = YoutubeProvider(streamOf(link)) { now }

            assertThat(withLink.stream("dQw4w9WgXcQ/251").validFor).isEqualTo(50.minutes)
        }
    }

    @Test
    fun `what the source says went wrong comes out with its kind and its text`() {
        val network = YoutubeException.Network("http 429, retry after 30 s")
        val parse = YoutubeException.Parse("no audio streams")
        val unavailable = YoutubeException.Unavailable("variant")
        val calls: List<() -> Any> =
            listOf(
                { provider.search("piano", 5) },
                { provider.resolve(SONG) },
                { provider.stream("dQw4w9WgXcQ/251") },
            )

        for (call in calls) {
            source.failure = network
            val gotNetwork = assertThrows(ProviderException.Network::class.java) { call() }
            source.failure = parse
            val gotParse = assertThrows(ProviderException.Parse::class.java) { call() }
            source.failure = unavailable
            val gotUnavailable = assertThrows(ProviderException.Unavailable::class.java) { call() }

            assertThat(gotNetwork.text).isEqualTo("http 429, retry after 30 s")
            assertThat(gotNetwork.cause).isSameInstanceAs(network)
            assertThat(gotParse.text).isEqualTo("no audio streams")
            assertThat(gotUnavailable.text).isEqualTo("variant")
        }
    }

    @Test
    fun `a failure that is not the sources comes out as it is`() {
        source.failure = IllegalStateException("https://secret.example/?q=my library")

        assertThrows(IllegalStateException::class.java) { provider.search("piano", 5) }
        assertThrows(IllegalStateException::class.java) { provider.resolve(SONG) }
        assertThrows(IllegalStateException::class.java) { provider.stream("dQw4w9WgXcQ/251") }
    }

    private fun streamOf(link: String): YoutubeSource =
        object : YoutubeSource {
            override fun search(
                query: String,
                limit: Int,
            ) = emptyList<YoutubeResult>()

            override fun resolve(id: String) = emptyList<YoutubeTrack>()

            override fun stream(variant: String) =
                YoutubeStream(link, listOf("User-Agent" to UA), YoutubeFormat.OPUS, 160)
        }

    private companion object {
        const val NOW_SECONDS = 1_760_000_000L
        const val NOW_MILLIS = NOW_SECONDS * 1000L
    }
}
