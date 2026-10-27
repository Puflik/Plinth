package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import io.github.puflik.plinth.ffi.generated.OnlineOrigin as RustOnlineOrigin
import io.github.puflik.plinth.ffi.generated.OnlineProblem as RustOnlineProblem
import io.github.puflik.plinth.ffi.generated.OnlineRadioReply as RustOnlineRadioReply

/**
 * Ответ ядра на радио (Ю4а, `docs/work/yu4a.md`) → типы приложения: страница с ключом следующей, беда провайдера с
 * пустой страницей, источник трека каталога.
 */
class CoreOnlineRadioMappingTest {
    @Test
    fun `a page of the core becomes a page of the app with the key of the next one`() {
        val tracks = listOf(track("aaaaaaaaaaa", "One"), track("bbbbbbbbbbb", "Два"))

        val reply = RustOnlineRadioReply(tracks.map(OnlineTrack::toRust), "page-2", null).toApp()

        assertThat(reply).isEqualTo(OnlineRadioReply(OnlineRadioPage(tracks, "page-2"), null))
    }

    @Test
    fun `the last page has no key`() {
        val reply = RustOnlineRadioReply(listOf(track("aaaaaaaaaaa", "One").toRust()), null, null).toApp()

        assertThat(reply.page.next).isNull()
        assertThat(reply.page.tracks.map { it.external }).containsExactly("aaaaaaaaaaa")
        assertThat(reply.problem).isNull()
    }

    @Test
    fun `trouble of the provider is a problem with an empty page`() {
        val cases =
            listOf(
                RustOnlineProblem.NO_NETWORK to OnlineProblem.NO_NETWORK,
                RustOnlineProblem.PROVIDER_DOWN to OnlineProblem.PROVIDER_DOWN,
                RustOnlineProblem.RATE_LIMITED to OnlineProblem.RATE_LIMITED,
                RustOnlineProblem.BROKEN to OnlineProblem.BROKEN,
            )
        for ((rust, expected) in cases) {
            val reply = RustOnlineRadioReply(emptyList(), null, rust).toApp()

            assertThat(reply).isEqualTo(OnlineRadioReply(OnlineRadioPage(emptyList(), null), expected))
        }
    }

    @Test
    fun `an origin of the core becomes an origin of the app`() {
        val origin = RustOnlineOrigin("youtube", "dQw4w9WgXcQ/251").toApp()

        assertThat(origin).isEqualTo(OnlineOrigin("youtube", "dQw4w9WgXcQ/251"))
    }

    private fun track(
        video: String,
        title: String,
    ) = OnlineTrack(
        external = video,
        title = title,
        artist = "Artist",
        duration = 200.seconds,
        variants = listOf(OnlineVariant("$video/251", AudioFormat.OPUS, 160)),
    )
}
