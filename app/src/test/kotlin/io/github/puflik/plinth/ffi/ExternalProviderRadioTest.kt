package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import io.github.puflik.plinth.ffi.generated.ExternalFailure as RustFailure
import io.github.puflik.plinth.ffi.generated.ExternalRadio as RustRadio

/**
 * Радио провайдера приложения глазами ядра (Ю4а, `docs/work/yu4a.md`): `OnlineProvider.radio` → внешний `trait`,
 * который зовёт Rust. Как и с остальными вопросами к провайдеру (`ExternalProviderMappingTest`), исключение через
 * границу не идёт: ядро получает страницу или беду с видом и текстом, а чужое исключение — только именем класса.
 *
 * `OnlineProvider.radio`, `OnlineRadioPage` и `ExternalRadio` — новый API (сигнатуры в файле задачи).
 */
class ExternalProviderRadioTest {
    private val provider = RadioProvider()
    private val rust = provider.toRust()

    @Test
    fun `a page reaches the core as the provider gave it and the key of the next one comes with it`() {
        val tracks = listOf(track("aaaaaaaaaaa", "One"), track("bbbbbbbbbbb", "Два"))
        provider.onRadio = { _, _ -> OnlineRadioPage(tracks, next = "page-2") }

        val answer = rust.radio("dQw4w9WgXcQ", null)

        assertThat(answer).isEqualTo(RustRadio.Found(tracks.map(OnlineTrack::toRust), "page-2"))
        assertThat(provider.radios).containsExactly("dQw4w9WgXcQ" to null)
    }

    @Test
    fun `the last page has no key and an empty page is still a page`() {
        provider.onRadio = { _, _ -> OnlineRadioPage(emptyList()) }

        val answer = rust.radio("dQw4w9WgXcQ", "page-9")

        assertThat(answer).isEqualTo(RustRadio.Found(emptyList(), null))
    }

    @Test
    fun `the seed and the key go to the provider verbatim`() {
        rust.radio("dQw4w9WgXcQ/251", "ключ 1/== ю")

        assertThat(provider.radios).containsExactly("dQw4w9WgXcQ/251" to "ключ 1/== ю")
    }

    @Test
    fun `what the provider names as trouble is what the core is told`() {
        val cases =
            listOf(
                ProviderException.Network("http 503, retry after 7 s") to
                    RustFailure.Network("http 503, retry after 7 s"),
                ProviderException.Parse("no usable track") to RustFailure.Parse("no usable track"),
                ProviderException.Unavailable("page") to RustFailure.Unavailable("page"),
            )
        for ((thrown, told) in cases) {
            provider.failure = thrown

            assertThat(rust.radio("dQw4w9WgXcQ", null)).isEqualTo(RustRadio.Failed(told))
        }
    }

    @Test
    fun `a foreign exception reaches the core as the name of its class and nothing else`() {
        provider.failure = IllegalStateException("https://secret.example/?q=my library")

        val answer = rust.radio("dQw4w9WgXcQ", null)

        assertThat(answer).isEqualTo(RustRadio.Failed(RustFailure.Internal("IllegalStateException")))
    }

    @Test
    fun `a provider that does not do radio tells the core it does not`() {
        val plain =
            object : OnlineProvider {
                override val id = "plain.test"
                override val finds = setOf(OnlineKind.TRACK)
                override val bestQuality = OnlineQuality.STANDARD

                override fun search(
                    query: String,
                    limit: Int,
                ): List<OnlineResult> = emptyList()

                override fun resolve(item: String): List<OnlineTrack> = emptyList()

                override fun stream(source: String): StreamAddress = throw ProviderException.Unavailable("none")
            }.toRust()

        assertThat(plain.radio("dQw4w9WgXcQ", null)).isEqualTo(RustRadio.Failed(RustFailure.Unavailable("radio")))
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

    /** Провайдер по сценарию: что спросили — записывает, что ответить — задаёт тест. */
    private class RadioProvider : OnlineProvider {
        override val id = "radio.test"
        override val finds = setOf(OnlineKind.TRACK)
        override val bestQuality = OnlineQuality.STANDARD
        val radios = mutableListOf<Pair<String, String?>>()
        var failure: Exception? = null
        var onRadio: (String, String?) -> OnlineRadioPage = { _, _ -> OnlineRadioPage(emptyList()) }

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
            radios += seed to page
            failure?.let { throw it }
            return onRadio(seed, page)
        }
    }
}
