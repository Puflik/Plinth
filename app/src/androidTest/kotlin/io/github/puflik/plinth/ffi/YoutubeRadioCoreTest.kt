package io.github.puflik.plinth.ffi

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.online.FakeYoutubeSource
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
import io.github.puflik.plinth.online.YoutubeProvider
import io.github.puflik.plinth.online.youtube.YoutubeException
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Радио YouTube за настоящей границей Rust ↔ Kotlin (Ю4б, `docs/work/yu4b.md`): ядро зовёт `YoutubeProvider.radio`
 * через внешний `trait` и UniFFI, источник под ним — `FakeYoutubeSource`. Видно то, чего не видят тесты по отдельности:
 * ключ следующей страницы проходит проверку ядра (`ExternalId`), затравка-вариант и ключ доходят дословно, треки радио
 * заводятся в каталог, а их источники снова открывают радио; беды источника становятся бедами страницы.
 *
 * Internet Archive не нужен: ему всегда отвечают «нет». `YoutubeSource.radio` и `YoutubeProvider.radio` — новый API.
 */
class YoutubeRadioCoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dataDir = File(context.cacheDir, "yu4b-core-" + UUID.randomUUID())
    private val core = PlinthCore(LogLevel.INFO, dataDir, CoreErrors())
    private val youtube = FakeYoutubeSource()
    private val online = core.online
    private val archive = NetTransport { NetAnswer.Response(404, ByteArray(0)) }

    @After
    fun tearDown() {
        core.close()
        dataDir.deleteRecursively()
    }

    private fun connect() = online.connect(archive, listOf(YoutubeProvider(youtube)))

    @Test
    fun the_pages_of_the_mix_come_through_the_core_with_their_keys_up_to_the_last() {
        connect()

        val first = online.radio("youtube", SONG)
        val second = online.radio("youtube", SONG, first.page.next)
        val third = online.radio("youtube", SONG, second.page.next)

        assertThat(listOf(first.problem, second.problem, third.problem)).containsExactly(null, null, null)
        assertThat(first.page.tracks.map { it.external }).containsExactly(RADIO_1, RADIO_2, RADIO_3).inOrder()
        assertThat(first.page.next).isEqualTo(RADIO_KEY_2)
        assertThat(second.page.tracks.map { it.external }).containsExactly(RADIO_4, RADIO_5, RADIO_6).inOrder()
        assertThat(second.page.next).isEqualTo(RADIO_KEY_3)
        assertThat(third.page.tracks.map { it.external }).containsExactly(RADIO_7)
        assertThat(third.page.next).isNull()
        assertThat(youtube.calls)
            .containsExactly("radio:$SONG:null", "radio:$SONG:$RADIO_KEY_2", "radio:$SONG:$RADIO_KEY_3")
            .inOrder()
    }

    @Test
    fun a_radio_track_arrives_with_both_variants_and_no_album() {
        connect()

        val track = online.radio("youtube", SONG).page.tracks[1]

        assertThat(track.title).isEqualTo("Radio 2")
        assertThat(track.artist).isEqualTo("Radio Artist")
        assertThat(track.album).isNull()
        assertThat(track.number).isNull()
        assertThat(track.variants.map { it.external to it.format })
            .containsExactly("$RADIO_2/251" to AudioFormat.OPUS, "$RADIO_2/140" to AudioFormat.AAC)
            .inOrder()
    }

    @Test
    fun a_radio_track_in_the_catalog_names_its_sources_and_they_open_the_radio_again_as_they_are() {
        connect()
        val page = online.radio("youtube", SONG).page

        val ids = online.add("youtube", page.tracks)
        val origins = online.origins(ids[0])
        val again = online.radio(origins[0].provider, origins[0].source)

        assertThat(origins.map { it.provider to it.source })
            .containsExactly("youtube" to "$RADIO_1/251", "youtube" to "$RADIO_1/140")
            .inOrder()
        assertThat(again.problem).isNull()
        // Затравка — вариант трека радио; самого трека в его подборке нет.
        assertThat(again.page.tracks.map { it.external }).containsExactly(RADIO_2, RADIO_3).inOrder()
        assertThat(youtube.calls.last()).isEqualTo("radio:$RADIO_1/251:null")
        assertThat(online.stream(ids[0], metered = false).url).endsWith("source=$RADIO_1/251")
    }

    @Test
    fun what_goes_wrong_in_the_source_is_the_problem_of_the_page() {
        connect()
        val cases =
            listOf(
                YoutubeException.Network("UnknownHostException") to OnlineProblem.NO_NETWORK,
                YoutubeException.Network("timed out") to OnlineProblem.PROVIDER_DOWN,
                YoutubeException.Network("http 429, retry after 30 s") to OnlineProblem.RATE_LIMITED,
                YoutubeException.Parse("ParsingException") to OnlineProblem.BROKEN,
                YoutubeException.Unavailable("page") to OnlineProblem.PROVIDER_DOWN,
            )

        for ((failure, problem) in cases) {
            youtube.failure = failure

            val reply = online.radio("youtube", SONG, RADIO_KEY_2)

            assertThat(reply.problem).isEqualTo(problem)
            assertThat(reply.page.tracks).isEmpty()
            assertThat(reply.page.next).isNull()
        }
    }

    @Test
    fun a_key_and_a_seed_that_the_source_does_not_know_are_a_provider_down_page_and_not_an_exception() {
        connect()

        val key = online.radio("youtube", SONG, "no-such-key")
        val seed = online.radio("youtube", "nobody-here")
        val fine = online.radio("youtube", SONG)

        assertThat(key.problem).isEqualTo(OnlineProblem.PROVIDER_DOWN)
        assertThat(seed.problem).isEqualTo(OnlineProblem.PROVIDER_DOWN)
        assertThat(fine.problem).isNull()
    }
}
