package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import io.github.puflik.plinth.ffi.generated.Format as RustFormat
import io.github.puflik.plinth.ffi.generated.NetAnswer as RustNetAnswer
import io.github.puflik.plinth.ffi.generated.NetHeader as RustNetHeader
import io.github.puflik.plinth.ffi.generated.NetRequest as RustNetRequest
import io.github.puflik.plinth.ffi.generated.OnlineAlbumReply as RustOnlineAlbumReply
import io.github.puflik.plinth.ffi.generated.OnlineProblem as RustOnlineProblem
import io.github.puflik.plinth.ffi.generated.OnlineSearch as RustOnlineSearch
import io.github.puflik.plinth.ffi.generated.OnlineTrackInfo as RustOnlineTrackInfo
import io.github.puflik.plinth.ffi.generated.OnlineVariant as RustOnlineVariant

/**
 * Р1.11г (`docs/work/r1-11d.md`): новые слова онлайн-API ядра доходят до приложения и обратно без потерь —
 * заголовки и таймаут ответа сети, «просит подождать» у секции и треки или беда у альбома.
 *
 * Новые типы UniFFI (`NetAnswer.TimedOut`, `headers`, `OnlineProblem.RATE_LIMITED`, `OnlineAlbumReply`) и их
 * отображения — сигнатуры в файле задачи.
 */
class CoreOnlineProblemsMappingTest {
    @Test
    fun `the headers of a response reach the core in order`() {
        val answer =
            NetTransport {
                NetAnswer.Response(429, "slow".toByteArray(), listOf("Retry-After" to "30", "X-Test" to "1"))
            }.toRust().get(REQUEST) as RustNetAnswer.Response

        assertThat(answer.status).isEqualTo(429.toUShort())
        assertThat(answer.body).isEqualTo("slow".toByteArray())
        assertThat(answer.headers)
            .containsExactly(RustNetHeader("Retry-After", "30"), RustNetHeader("X-Test", "1"))
            .inOrder()
    }

    @Test
    fun `a response without headers is still a response`() {
        val answer = NetTransport { NetAnswer.Response(200, ByteArray(0)) }.toRust().get(REQUEST)

        assertThat((answer as RustNetAnswer.Response).headers).isEmpty()
    }

    @Test
    fun `a timeout of the transport reaches the core as a timeout`() {
        val answer = NetTransport { NetAnswer.TimedOut }.toRust().get(REQUEST)

        assertThat(answer).isEqualTo(RustNetAnswer.TimedOut)
    }

    @Test
    fun `every problem of a section comes through and none is lost on the way`() {
        val sections = RustOnlineProblem.entries.map { RustOnlineSearch("archive.org", emptyList(), it).toApp() }

        assertThat(sections.map { it.problem })
            .containsExactly(
                OnlineProblem.NO_NETWORK,
                OnlineProblem.PROVIDER_DOWN,
                OnlineProblem.RATE_LIMITED,
                OnlineProblem.BROKEN,
            )
        // Перечисления ядра и приложения совпадают один в один: новое слово не теряется молча.
        assertThat(RustOnlineProblem.entries.map { it.name })
            .containsExactlyElementsIn(OnlineProblem.entries.map { it.name })
    }

    @Test
    fun `an album reply keeps its tracks or its problem`() {
        val track =
            RustOnlineTrackInfo(
                external = "x/a.mp3",
                title = "A",
                artist = null,
                album = null,
                number = 1u,
                year = null,
                durationMs = null,
                mbid = null,
                variants = listOf(RustOnlineVariant("x/a.mp3", RustFormat.MP3, 128u)),
            )

        val opened = RustOnlineAlbumReply(listOf(track), null).toApp()
        val refused = RustOnlineAlbumReply(emptyList(), RustOnlineProblem.RATE_LIMITED).toApp()

        assertThat(opened.tracks).containsExactly(track.toApp())
        assertThat(opened.problem).isNull()
        assertThat(refused.tracks).isEmpty()
        assertThat(refused.problem).isEqualTo(OnlineProblem.RATE_LIMITED)
    }

    private companion object {
        val REQUEST = RustNetRequest("https://archive.org/metadata/x", emptyList(), 1_000u, 10u)
    }
}
