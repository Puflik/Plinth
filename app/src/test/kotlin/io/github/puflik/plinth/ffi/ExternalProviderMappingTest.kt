package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import io.github.puflik.plinth.ffi.generated.ExternalFailure as RustFailure
import io.github.puflik.plinth.ffi.generated.ExternalProvider as RustProvider
import io.github.puflik.plinth.ffi.generated.ExternalResolve as RustResolve
import io.github.puflik.plinth.ffi.generated.ExternalSearch as RustSearch
import io.github.puflik.plinth.ffi.generated.ExternalStream as RustStream
import io.github.puflik.plinth.ffi.generated.Format as RustFormat
import io.github.puflik.plinth.ffi.generated.NetHeader as RustNetHeader
import io.github.puflik.plinth.ffi.generated.OnlineKind as RustOnlineKind
import io.github.puflik.plinth.ffi.generated.OnlineResult as RustOnlineResult
import io.github.puflik.plinth.ffi.generated.OnlineTrackInfo as RustOnlineTrackInfo
import io.github.puflik.plinth.ffi.generated.OnlineVariant as RustOnlineVariant
import io.github.puflik.plinth.ffi.generated.QualityTier as RustQuality
import io.github.puflik.plinth.ffi.generated.StreamAddress as RustStreamAddress

/**
 * Провайдер приложения глазами ядра (Ю3в): `OnlineProvider` → внешний `trait`, который зовёт Rust. Исключение через
 * границу не идёт: что бы ни случилось в провайдере, ядро получает ответ — найдено или беда с видом и текстом. Текст
 * беды — только то, что провайдер сам назвал или имя класса: сообщение чужого исключения (адрес, запрос) в ядро и в
 * его журнал не попадает.
 */
class ExternalProviderMappingTest {
    private val provider = ScriptedProvider()
    private val rust: RustProvider = provider.toRust()

    @Test
    fun `the core is told the name, what the provider finds and how good it is`() {
        val named = ScriptedProvider(id = "youtube", finds = setOf(OnlineKind.TRACK, OnlineKind.ALBUM)).toRust()
        val albums = ScriptedProvider(finds = setOf(OnlineKind.ALBUM), quality = OnlineQuality.LOSSLESS).toRust()

        assertThat(named.id()).isEqualTo("youtube")
        assertThat(named.capabilities().finds).containsExactly(RustOnlineKind.TRACK, RustOnlineKind.ALBUM)
        assertThat(named.capabilities().bestQuality).isEqualTo(RustQuality.STANDARD)
        assertThat(albums.capabilities().finds).containsExactly(RustOnlineKind.ALBUM)
        assertThat(albums.capabilities().bestQuality).isEqualTo(RustQuality.LOSSLESS)
        assertThat(
            ScriptedProvider(quality = OnlineQuality.HIGH_LOSSY).toRust().capabilities().bestQuality,
        ).isEqualTo(RustQuality.HIGH_LOSSY)
    }

    @Test
    fun `a provider that cannot even tell its name does not throw into the core`() {
        val broken =
            object : OnlineProvider by ScriptedProvider() {
                override val id: String get() = error("https://secret.example/?q=my library")
                override val finds: Set<OnlineKind> get() = error("https://secret.example/?q=my library")
            }.toRust()

        assertThat(broken.id()).doesNotContain("secret")
        assertThat(broken.capabilities().finds).isEmpty()
    }

    @Test
    fun `a search reaches the provider as it was asked and comes back as the core wants it`() {
        provider.onSearch = { _, _ ->
            listOf(
                OnlineResult("dQw4w9WgXcQ", OnlineKind.TRACK, "Piano Man", "Billy Joel", 1973, 338_000.milliseconds),
                OnlineResult("OLAK5uy_x", OnlineKind.ALBUM, "Фортепиано", null),
            )
        }

        val answer = rust.search("piano", 20u)

        assertThat(provider.searches).containsExactly("piano" to 20)
        assertThat(answer)
            .isEqualTo(
                RustSearch.Found(
                    listOf(
                        RustOnlineResult(
                            "dQw4w9WgXcQ",
                            RustOnlineKind.TRACK,
                            "Piano Man",
                            "Billy Joel",
                            1973u,
                            338_000u,
                        ),
                        RustOnlineResult("OLAK5uy_x", RustOnlineKind.ALBUM, "Фортепиано", null, null, null),
                    ),
                ),
            )
    }

    @Test
    fun `a resolved track keeps every field and every variant in order`() {
        provider.onResolve = {
            listOf(
                OnlineTrack(
                    external = "dQw4w9WgXcQ",
                    title = "Piano Man",
                    artist = "Billy Joel",
                    album = "Piano Album",
                    number = 2,
                    year = 1973,
                    duration = 338_000.milliseconds,
                    mbid = null,
                    variants =
                        listOf(
                            OnlineVariant("dQw4w9WgXcQ/251", AudioFormat.OPUS, 160),
                            OnlineVariant("dQw4w9WgXcQ/140", AudioFormat.AAC, 128),
                        ),
                ),
            )
        }

        val answer = rust.resolve("OLAK5uy_x")

        assertThat(provider.resolves).containsExactly("OLAK5uy_x")
        assertThat(answer)
            .isEqualTo(
                RustResolve.Found(
                    listOf(
                        RustOnlineTrackInfo(
                            external = "dQw4w9WgXcQ",
                            title = "Piano Man",
                            artist = "Billy Joel",
                            album = "Piano Album",
                            number = 2u,
                            year = 1973u,
                            durationMs = 338_000u,
                            mbid = null,
                            variants =
                                listOf(
                                    RustOnlineVariant("dQw4w9WgXcQ/251", RustFormat.OPUS, 160u),
                                    RustOnlineVariant("dQw4w9WgXcQ/140", RustFormat.AAC, 128u),
                                ),
                        ),
                    ),
                ),
            )
    }

    @Test
    fun `a stream keeps the address, the headers in order and the time the address lives`() {
        provider.onStream = {
            StreamAddress(
                "https://rr1.test/videoplayback?n=1",
                listOf("User-Agent" to "com.google.visionos.youtube/1.02", "Range" to "bytes=0-"),
                2.hours,
            )
        }

        val answer = rust.stream("dQw4w9WgXcQ/251")

        assertThat(provider.streams).containsExactly("dQw4w9WgXcQ/251")
        assertThat(answer)
            .isEqualTo(
                RustStream.Found(
                    RustStreamAddress(
                        "https://rr1.test/videoplayback?n=1",
                        listOf(
                            RustNetHeader("User-Agent", "com.google.visionos.youtube/1.02"),
                            RustNetHeader("Range", "bytes=0-"),
                        ),
                        7_200_000u,
                    ),
                ),
            )
    }

    @Test
    fun `an address without a time to live says so`() {
        provider.onStream = { StreamAddress("https://archive.org/download/x/01.mp3") }

        val answer = rust.stream("x") as RustStream.Found

        assertThat(answer.address.validForMs).isNull()
        assertThat(answer.address.headers).isEmpty()
    }

    @Test
    fun `the address the core gives to the player keeps its headers and the time it lives`() {
        val timed =
            RustStreamAddress(
                "https://rr1.test/a",
                listOf(RustNetHeader("User-Agent", "yt"), RustNetHeader("Range", "bytes=0-")),
                7_200_000u,
            ).toApp()
        val untimed = RustStreamAddress("https://archive.org/x.mp3", emptyList(), null).toApp()
        val spent = RustStreamAddress("https://rr1.test/a", emptyList(), 0u).toApp()

        val headers = listOf("User-Agent" to "yt", "Range" to "bytes=0-")
        assertThat(timed).isEqualTo(StreamAddress("https://rr1.test/a", headers, 2.hours))
        assertThat(untimed).isEqualTo(StreamAddress("https://archive.org/x.mp3"))
        assertThat(spent.validFor).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `an exhausted time to live is zero, not nothing`() {
        provider.onStream = { StreamAddress("https://rr1.test/a", validFor = Duration.ZERO) }

        val answer = rust.stream("x") as RustStream.Found

        assertThat(answer.address.validForMs).isEqualTo(0uL)
    }

    @Test
    fun `every failure of the provider is an answer with its kind and its text`() {
        val wait = "http 429, retry after 30 s"
        val failures =
            listOf(
                ProviderException.Network(wait) to RustFailure.Network(wait),
                ProviderException.Parse("no audio streams") to RustFailure.Parse("no audio streams"),
                ProviderException.Unavailable("variant") to RustFailure.Unavailable("variant"),
            )
        for ((thrown, told) in failures) {
            provider.failure = thrown

            assertThat(rust.search("piano", 5u)).isEqualTo(RustSearch.Failed(told))
            assertThat(rust.resolve("x")).isEqualTo(RustResolve.Failed(told))
            assertThat(rust.stream("x/251")).isEqualTo(RustStream.Failed(told))
        }
    }

    @Test
    fun `a failure that is not the providers is internal, named by its class and nothing else`() {
        val strangers =
            listOf(
                IllegalStateException("https://secret.example/?q=my library") to "IllegalStateException",
                IllegalArgumentException("SECRET") to "IllegalArgumentException",
                object : RuntimeException("SECRET") {} to "",
            )
        for ((thrown, name) in strangers) {
            provider.failure = thrown

            val answers = listOf(rust.search("piano", 5u), rust.resolve("x"), rust.stream("x/251"))

            for (answer in answers) {
                val failure = failureOf(answer) as RustFailure.Internal
                assertThat(failure.text).doesNotContain("SECRET")
                assertThat(failure.text).doesNotContain("secret")
                if (name.isNotEmpty()) assertThat(failure.text).isEqualTo(name)
                assertThat(failure.text).isNotEmpty()
            }
        }
    }

    private fun failureOf(answer: Any): RustFailure =
        when (answer) {
            is RustSearch.Failed -> answer.failure
            is RustResolve.Failed -> answer.failure
            is RustStream.Failed -> answer.failure
            else -> error("not a failure: $answer")
        }

    /** Провайдер по сценарию: что спросили — записывает, что ответить — задаёт тест. */
    private class ScriptedProvider(
        override val id: String = "scripted.test",
        override val finds: Set<OnlineKind> = setOf(OnlineKind.TRACK),
        quality: OnlineQuality = OnlineQuality.STANDARD,
    ) : OnlineProvider {
        override val bestQuality: OnlineQuality = quality
        val searches = mutableListOf<Pair<String, Int>>()
        val resolves = mutableListOf<String>()
        val streams = mutableListOf<String>()
        var failure: Exception? = null
        var onSearch: (String, Int) -> List<OnlineResult> = { _, _ -> emptyList() }
        var onResolve: (String) -> List<OnlineTrack> = { emptyList() }
        var onStream: (String) -> StreamAddress = { StreamAddress("https://rr1.test/a") }

        override fun search(
            query: String,
            limit: Int,
        ): List<OnlineResult> {
            searches += query to limit
            failure?.let { throw it }
            return onSearch(query, limit)
        }

        override fun resolve(item: String): List<OnlineTrack> {
            resolves += item
            failure?.let { throw it }
            return onResolve(item)
        }

        override fun stream(source: String): StreamAddress {
            streams += source
            failure?.let { throw it }
            return onStream(source)
        }
    }
}
