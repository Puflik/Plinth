package io.github.puflik.plinth.ffi

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.online.FakeYoutubeSource
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.ALBUM
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.QUIET
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.SONG
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.SONG_NO_OPUS
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.UA
import io.github.puflik.plinth.online.YoutubeProvider
import io.github.puflik.plinth.online.youtube.YoutubeException
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Провайдер приложения за настоящей границей Rust ↔ Kotlin (Ю3в): ядро зовёт `YoutubeProvider` через внешний `trait`
 * и UniFFI, а источник под ним — `FakeYoutubeSource`. Здесь видно то, чего не видят тесты по отдельности: имена и
 * идентификаторы доходят до каталога и обратно без изменений, беды источника становятся бедами секции, исключение
 * не рушит процесс, а срок и заголовки потока приходят к плееру.
 *
 * Internet Archive отвечает пустым поиском: он в реестре всегда, а этим тестам нужен только YouTube рядом.
 * `OnlineProvider` и второй параметр `CoreOnline.connect` — новый API (сигнатуры в `docs/work/yu3v.md`).
 */
class ExternalProviderCoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dataDir = File(context.cacheDir, "yu3v-core-" + UUID.randomUUID())
    private val core = PlinthCore(LogLevel.INFO, dataDir, CoreErrors())
    private val youtube = FakeYoutubeSource()
    private val online = core.online

    private val archive =
        NetTransport { request ->
            if (request.url.startsWith("https://archive.org/advancedsearch.php?")) {
                NetAnswer.Response(200, EMPTY_SEARCH.toByteArray())
            } else {
                NetAnswer.Response(404, ByteArray(0))
            }
        }

    @After
    fun tearDown() {
        core.close()
        dataDir.deleteRecursively()
    }

    private fun connect() = online.connect(archive, listOf(YoutubeProvider(youtube)))

    @Test
    fun youtube_is_a_section_after_archive_org_with_its_results_as_the_source_gave_them() {
        connect()

        val sections = online.search("piano", 20)

        assertThat(sections.map { it.provider }).containsExactly("archive.org", "youtube").inOrder()
        val youtubeSection = sections[1]
        assertThat(youtubeSection.problem).isNull()
        assertThat(youtubeSection.results.map { Triple(it.external, it.kind, it.title) })
            .containsExactly(
                Triple(SONG, OnlineKind.TRACK, "Piano Man"),
                Triple(ALBUM, OnlineKind.ALBUM, "Piano Album"),
            ).inOrder()
        assertThat(youtubeSection.results[0].artist).isEqualTo("Billy Joel")
        assertThat(youtubeSection.results[0].duration).isEqualTo(338.seconds)
        assertThat(youtube.calls).containsExactly("search:piano:20")
    }

    @Test
    fun an_album_arrives_with_numbers_and_variants_and_its_tracks_play_with_the_headers_and_the_time_of_the_link() {
        connect()

        val tracks = (online.album("youtube", ALBUM)).tracks
        val ids = online.add("youtube", tracks)
        val stream = online.stream(ids[1], metered = false)

        assertThat(tracks.map { it.number }).containsExactly(1, 2, 3).inOrder()
        assertThat(tracks[1].variants.map { it.external to it.format })
            .containsExactly("trkCCCCCCC3/251" to AudioFormat.OPUS, "trkCCCCCCC3/140" to AudioFormat.AAC)
            .inOrder()
        assertThat(stream.url).endsWith("source=trkCCCCCCC3/251")
        assertThat(stream.headers).containsExactly("User-Agent" to UA, "Range" to "bytes=0-").inOrder()
        assertThat(stream.validFor).isEqualTo(2.hours)
        assertThat(youtube.calls.filter { it.startsWith("stream:") }).containsExactly("stream:trkCCCCCCC3/251")
    }

    @Test
    fun a_track_whose_video_has_no_opus_falls_through_to_aac_and_a_second_add_finds_the_same_track() {
        connect()
        val album = online.album("youtube", QUIET).tracks

        val ids = online.add("youtube", album)
        val again = online.add("youtube", album)
        val stream = online.stream(ids[0], metered = false)

        assertThat(again).isEqualTo(ids)
        assertThat(online.known("youtube", album)).isEqualTo(ids)
        assertThat(stream.url).endsWith("source=trkBBBBBBB2/140")
        assertThat(youtube.calls.filter { it.startsWith("stream:") })
            .containsExactly("stream:trkBBBBBBB2/251", "stream:trkBBBBBBB2/140")
            .inOrder()
    }

    @Test
    fun the_same_video_is_one_track_whether_it_came_alone_or_in_an_album() {
        connect()
        val alone = online.add("youtube", online.album("youtube", SONG_NO_OPUS).tracks)

        val inAlbum = online.known("youtube", online.album("youtube", QUIET).tracks)

        assertThat(inAlbum).containsExactly(null, alone.single()).inOrder()
    }

    @Test
    fun what_goes_wrong_in_the_source_is_the_problem_of_its_section_only() {
        val cases =
            listOf(
                YoutubeException.Network("UnknownHostException") to OnlineProblem.NO_NETWORK,
                YoutubeException.Network("timed out") to OnlineProblem.PROVIDER_DOWN,
                YoutubeException.Network("http 503") to OnlineProblem.PROVIDER_DOWN,
                YoutubeException.Network("http 429, retry after 30 s") to OnlineProblem.RATE_LIMITED,
                YoutubeException.Parse("no audio streams") to OnlineProblem.BROKEN,
                YoutubeException.Unavailable("variant") to OnlineProblem.PROVIDER_DOWN,
                IllegalStateException("https://secret.example/?q=my library") to OnlineProblem.BROKEN,
            )
        for ((failure, problem) in cases) {
            youtube.failure = failure
            connect()

            val sections = online.search("piano", 20)

            assertThat(sections.map { it.provider }).containsExactly("archive.org", "youtube").inOrder()
            assertThat(sections[1].problem).isEqualTo(problem)
            assertThat(sections[1].results).isEmpty()
            assertThat(sections[0].problem).isNull()
        }
    }

    @Test
    fun an_error_that_is_not_an_exception_of_the_source_does_not_end_the_search_for_the_others() {
        youtube.failure = IllegalStateException("boom")
        connect()

        val sections = online.search("piano", 20)
        youtube.failure = null
        val next = online.search("piano", 20)

        assertThat(sections[1].problem).isEqualTo(OnlineProblem.BROKEN)
        assertThat(next[1].problem).isNull()
        assertThat(next[1].results).isNotEmpty()
    }

    @Test
    fun connecting_without_providers_is_archive_org_alone() {
        online.connect(archive)

        assertThat(online.search("piano", 20).map { it.provider }).containsExactly("archive.org")
    }

    @Test
    fun a_provider_with_a_name_the_core_cannot_keep_fails_the_connection() {
        val nameless =
            object : OnlineProvider by YoutubeProvider(youtube) {
                override val id: String = "YouTube"
            }

        assertThrows(CoreFailure::class.java) { online.connect(archive, listOf(nameless)) }
    }

    @Test
    fun a_provider_that_throws_when_asked_its_name_fails_the_connection_not_the_process() {
        val broken =
            object : OnlineProvider by YoutubeProvider(youtube) {
                override val id: String get() = error("no name")
            }

        assertThrows(CoreFailure::class.java) { online.connect(archive, listOf(broken)) }
    }

    private companion object {
        const val EMPTY_SEARCH = """{"response":{"numFound":0,"start":0,"docs":[]}}"""
    }
}
