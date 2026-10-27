package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.ProviderException
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_1
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_2
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_3
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_4
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_5
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_6
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_7
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_KEY_2
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_KEY_3
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.SONG
import io.github.puflik.plinth.online.youtube.YoutubeException
import io.github.puflik.plinth.online.youtube.YoutubeFormat
import io.github.puflik.plinth.online.youtube.YoutubeRadioPage
import io.github.puflik.plinth.online.youtube.YoutubeResult
import io.github.puflik.plinth.online.youtube.YoutubeSource
import io.github.puflik.plinth.online.youtube.YoutubeStream
import io.github.puflik.plinth.online.youtube.YoutubeTrack
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Радио YouTube на языке провайдера ядра (Ю4б, `docs/work/yu4b.md`): `YoutubeSource.radio` → `OnlineProvider.radio`.
 * Идентификаторы идут туда и обратно дословно: затравка и ключ — к источнику, ключ следующей страницы и треки — к ядру
 * и в каталог пользователя; у трека радио — те же `<видео>` и `<видео>/<itag>`, что у трека альбома.
 */
class YoutubeProviderRadioTest {
    private val source = FakeYoutubeSource()
    private val provider = YoutubeProvider(source)

    @Test
    fun `radio hands the seed and the page key to the source as they are`() {
        provider.radio(SONG, null)
        provider.radio("$SONG/251", RADIO_KEY_2)
        provider.radio("$SONG/140", RADIO_KEY_3)

        assertThat(source.calls)
            .containsExactly("radio:$SONG:null", "radio:$SONG/251:$RADIO_KEY_2", "radio:$SONG/140:$RADIO_KEY_3")
            .inOrder()
    }

    @Test
    fun `a page keeps its order and each track its id title artist length and both variants, Opus first`() {
        val page = provider.radio(SONG, null)

        assertThat(page.tracks.map { it.external }).containsExactly(RADIO_1, RADIO_2, RADIO_3).inOrder()
        assertThat(page.tracks.map { it.title }).containsExactly("Radio 1", "Radio 2", "Radio 3").inOrder()
        val first = page.tracks.first()
        assertThat(first.artist).isEqualTo("Radio Artist")
        assertThat(first.duration).isEqualTo(200.seconds)
        assertThat(first.album).isNull()
        assertThat(first.number).isNull()
        assertThat(first.year).isNull()
        assertThat(first.mbid).isNull()
        assertThat(first.variants.map { Triple(it.external, it.format, it.bitrateKbps) })
            .containsExactly(
                Triple("$RADIO_1/251", AudioFormat.OPUS, 160),
                Triple("$RADIO_1/140", AudioFormat.AAC, 128),
            ).inOrder()
    }

    @Test
    fun `a page has the key of the next one, and the last page has none`() {
        val first = provider.radio(SONG, null)
        val second = provider.radio(SONG, first.next)
        val third = provider.radio(SONG, second.next)

        assertThat(first.next).isEqualTo(RADIO_KEY_2)
        assertThat(second.next).isEqualTo(RADIO_KEY_3)
        assertThat(third.next).isNull()
        assertThat(second.tracks.map { it.external }).containsExactly(RADIO_4, RADIO_5, RADIO_6).inOrder()
        assertThat(third.tracks.map { it.external }).containsExactly(RADIO_7)
    }

    /** Каждая вариантная ссылка радио называет своё видео: чужой идентификатор в варианте склеил бы два трека каталога. */
    @Test
    fun `every variant id of a radio track is the id of its own track and an itag`() {
        val tracks =
            listOf(null, RADIO_KEY_2, RADIO_KEY_3).flatMap { provider.radio(SONG, it).tracks }

        assertThat(tracks).hasSize(7)
        for (track in tracks) {
            assertThat(track.variants.map { it.external }).containsExactly("${track.external}/251", "${track.external}/140")
        }
    }

    @Test
    fun `the three failures of the source come out as the providers own, with their text and cause`() {
        val network = YoutubeException.Network("http 429, retry after 30 s")
        val parse = YoutubeException.Parse("ParsingException")
        val unavailable = YoutubeException.Unavailable("page")

        source.failure = network
        val gotNetwork = assertThrows(ProviderException.Network::class.java) { provider.radio(SONG, null) }
        source.failure = parse
        val gotParse = assertThrows(ProviderException.Parse::class.java) { provider.radio(SONG, null) }
        source.failure = unavailable
        val gotUnavailable = assertThrows(ProviderException.Unavailable::class.java) { provider.radio(SONG, RADIO_KEY_2) }

        assertThat(gotNetwork.text).isEqualTo("http 429, retry after 30 s")
        assertThat(gotNetwork.cause).isSameInstanceAs(network)
        assertThat(gotParse.text).isEqualTo("ParsingException")
        assertThat(gotParse.cause).isSameInstanceAs(parse)
        assertThat(gotUnavailable.text).isEqualTo("page")
        assertThat(gotUnavailable.cause).isSameInstanceAs(unavailable)
    }

    @Test
    fun `a failure that is not the sources comes out as it is`() {
        source.failure = IllegalStateException("https://secret.example/?q=my library")

        assertThrows(IllegalStateException::class.java) { provider.radio(SONG, null) }
    }

    @Test
    fun `a seed the source does not know and a key it does not know are unavailable`() {
        val seed = assertThrows(ProviderException.Unavailable::class.java) { provider.radio("nobody-here", null) }
        val key = assertThrows(ProviderException.Unavailable::class.java) { provider.radio(SONG, "no-such-key") }

        assertThat(seed.text).isEqualTo("id")
        assertThat(key.text).isEqualTo("page")
    }

    /** Источник, который радио не умеет (ни одного метода `radio` он не писал), для провайдера — «такого нет». */
    @Test
    fun `a source without radio is unavailable radio`() {
        val plain =
            object : YoutubeSource {
                override fun search(
                    query: String,
                    limit: Int,
                ) = emptyList<YoutubeResult>()

                override fun resolve(id: String) = emptyList<YoutubeTrack>()

                override fun stream(variant: String) =
                    YoutubeStream("https://rr1---sn-test.googlevideo.com/", emptyList(), YoutubeFormat.OPUS, 160)
            }

        val failure = assertThrows(ProviderException.Unavailable::class.java) { YoutubeProvider(plain).radio(SONG, null) }
        val direct = assertThrows(YoutubeException.Unavailable::class.java) { plain.radio(SONG, null) }

        assertThat(failure.text).isEqualTo("radio")
        assertThat(direct.text).isEqualTo("radio")
    }

    @Test
    fun `radio does not change what the provider says about itself or the other calls`() {
        provider.radio(SONG, null)

        assertThat(provider.id).isEqualTo("youtube")
        assertThat(provider.search("piano", 20).map { it.external }).containsExactly(SONG, FakeYoutubeSource.ALBUM)
        assertThat(provider.resolve(SONG).single().external).isEqualTo(SONG)
        assertThat(provider.stream("$RADIO_1/251").url).endsWith("source=$RADIO_1/251")
    }

    @Test
    fun `an empty page with a key is still a page`() {
        source.radioPages = mapOf(null to YoutubeRadioPage(emptyList(), "more"), "more" to YoutubeRadioPage(emptyList(), null))

        val first = provider.radio(SONG, null)
        val last = provider.radio(SONG, first.next)

        assertThat(first.tracks).isEmpty()
        assertThat(first.next).isEqualTo("more")
        assertThat(last.tracks).isEmpty()
        assertThat(last.next).isNull()
    }
}
