package io.github.puflik.plinth.ffi

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.online.CoreOnlineRepository
import io.github.puflik.plinth.online.OnlineProviderIds
import io.github.puflik.plinth.online.OnlineRadio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Радио за настоящей границей Rust ↔ Kotlin (Ю4а, `docs/work/yu4a.md`): ядро зовёт `OnlineProvider.radio` через внешний
 * `trait` и UniFFI, страница с ключом доходит до `CoreOnlineRepository`, а беда провайдера становится бедой страницы.
 * Здесь видно то, чего не видят тесты по отдельности: настоящие сгенерированные типы, `Option<String>` через границу,
 * исключение провайдера, источники трека в каталоге после `add`.
 *
 * Internet Archive выключен (его нет в этих тестах), сеть не нужна. `OnlineProvider.radio`, `CoreOnline.radio`,
 * `CoreOnline.origins` и `OnlineRepository.radio`/`origins` — новый API (сигнатуры в файле задачи).
 */
class OnlineRadioCoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dataDir = File(context.cacheDir, "yu4a-core-" + UUID.randomUUID())
    private val core = PlinthCore(LogLevel.INFO, dataDir, CoreErrors())
    private val radio = RadioProvider("radio.test")
    private val plain = PlainProvider("plain.test")
    private val repository =
        CoreOnlineRepository(
            core,
            NetTransport { NetAnswer.Response(404, ByteArray(0)) },
            Dispatchers.IO,
            listOf(radio, plain),
        )

    @After
    fun tearDown() {
        core.close()
        dataDir.deleteRecursively()
    }

    private suspend fun connect() = repository.setEnabled(true, disabled = setOf(OnlineProviderIds.ARCHIVE))

    @Test
    fun two_pages_come_through_the_core_with_their_keys_and_the_seed_and_the_key_reach_the_provider_as_they_are() {
        runBlocking {
            connect()
            radio.pages[null] =
                OnlineRadioPage(listOf(track("aaaaaaaaaaa", "One"), track("bbbbbbbbbbb", "Два")), next = "ключ 2/==")
            radio.pages["ключ 2/=="] = OnlineRadioPage(listOf(track("ccccccccccc", "Three")))

            val first = repository.radio("radio.test", "dQw4w9WgXcQ/251") as OnlineRadio.Page
            val second = repository.radio("radio.test", "dQw4w9WgXcQ/251", first.next) as OnlineRadio.Page

            assertThat(first.tracks.map { it.external to it.title })
                .containsExactly("aaaaaaaaaaa" to "One", "bbbbbbbbbbb" to "Два")
                .inOrder()
            assertThat(first.tracks[0].variants.map { it.external to it.format })
                .containsExactly("aaaaaaaaaaa/251" to AudioFormat.OPUS, "aaaaaaaaaaa/140" to AudioFormat.AAC)
                .inOrder()
            assertThat(first.next).isEqualTo("ключ 2/==")
            assertThat(second.tracks.map { it.external }).containsExactly("ccccccccccc")
            assertThat(second.next).isNull()
            assertThat(radio.asked)
                .containsExactly("dQw4w9WgXcQ/251" to null, "dQw4w9WgXcQ/251" to "ключ 2/==")
                .inOrder()
        }
    }

    @Test
    fun what_goes_wrong_in_the_provider_is_the_problem_of_the_page() {
        runBlocking {
            connect()
            val cases =
                listOf(
                    ProviderException.Network("UnknownHostException") to OnlineProblem.NO_NETWORK,
                    ProviderException.Network("timed out") to OnlineProblem.PROVIDER_DOWN,
                    ProviderException.Network("http 429, retry after 30 s") to OnlineProblem.RATE_LIMITED,
                    ProviderException.Parse("no usable track") to OnlineProblem.BROKEN,
                    ProviderException.Unavailable("page") to OnlineProblem.PROVIDER_DOWN,
                    IllegalStateException("https://secret.example/?q=my library") to OnlineProblem.BROKEN,
                )
            for ((failure, problem) in cases) {
                radio.failure = failure

                val answer = repository.radio("radio.test", "dQw4w9WgXcQ")

                assertThat(answer).isEqualTo(OnlineRadio.Failed(problem))
            }
        }
    }

    @Test
    fun a_provider_without_radio_and_a_stranger_are_down_for_it_and_so_are_the_sources_when_off() {
        runBlocking {
            connect()

            val withoutRadio = repository.radio("plain.test", "dQw4w9WgXcQ")
            val stranger = repository.radio("stranger.test", "dQw4w9WgXcQ")
            repository.setEnabled(false)
            val off = repository.radio("radio.test", "dQw4w9WgXcQ")

            val down = OnlineRadio.Failed(OnlineProblem.PROVIDER_DOWN)
            assertThat(withoutRadio).isEqualTo(down)
            assertThat(stranger).isEqualTo(down)
            assertThat(off).isEqualTo(down)
            assertThat(radio.asked).isEmpty()
        }
    }

    @Test
    fun the_origins_of_a_track_are_its_sources_after_add_and_radio_adds_nothing() {
        runBlocking {
            connect()
            radio.pages[null] = OnlineRadioPage(listOf(track("aaaaaaaaaaa", "One")))
            val page = repository.radio("radio.test", "dQw4w9WgXcQ") as OnlineRadio.Page
            val knownBefore = repository.known("radio.test", page.tracks)

            val ids = repository.add("radio.test", page.tracks)!!
            val origins = repository.origins(ids.single())

            assertThat(knownBefore).containsExactly(null)
            assertThat(origins)
                .containsExactly(
                    OnlineOrigin("radio.test", "aaaaaaaaaaa/251"),
                    OnlineOrigin("radio.test", "aaaaaaaaaaa/140"),
                )
            assertThat(repository.origins(TrackId("nobody"))).isEmpty()
        }
    }

    private fun track(
        video: String,
        title: String,
    ) = OnlineTrack(
        external = video,
        title = title,
        artist = "Artist",
        duration = 200.seconds,
        variants =
            listOf(
                OnlineVariant("$video/251", AudioFormat.OPUS, 160),
                OnlineVariant("$video/140", AudioFormat.AAC, 128),
            ),
    )

    /** Провайдер с радио: страницы по ключу (`null` — первая); не нашёл страницу — «такого нет». */
    private class RadioProvider(
        override val id: String,
    ) : OnlineProvider {
        override val finds = setOf(OnlineKind.TRACK)
        override val bestQuality = OnlineQuality.STANDARD
        val pages = mutableMapOf<String?, OnlineRadioPage>()
        val asked = mutableListOf<Pair<String, String?>>()
        var failure: Exception? = null

        override fun search(
            query: String,
            limit: Int,
        ): List<OnlineResult> = emptyList()

        override fun resolve(item: String): List<OnlineTrack> = emptyList()

        override fun stream(source: String): StreamAddress = throw ProviderException.Unavailable("none")

        override fun radio(
            seed: String,
            page: String?,
        ): OnlineRadioPage {
            asked += seed to page
            failure?.let { throw it }
            return pages[page] ?: throw ProviderException.Unavailable("page")
        }
    }

    /** Провайдер без радио: метод `radio` — по умолчанию. */
    private class PlainProvider(
        override val id: String,
    ) : OnlineProvider {
        override val finds = setOf(OnlineKind.TRACK)
        override val bestQuality = OnlineQuality.STANDARD

        override fun search(
            query: String,
            limit: Int,
        ): List<OnlineResult> = emptyList()

        override fun resolve(item: String): List<OnlineTrack> = emptyList()

        override fun stream(source: String): StreamAddress = throw ProviderException.Unavailable("none")
    }
}
