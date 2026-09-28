package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.github.puflik.plinth.ffi.generated.Format as RustFormat
import io.github.puflik.plinth.ffi.generated.NetAnswer as RustNetAnswer
import io.github.puflik.plinth.ffi.generated.NetHeader as RustNetHeader
import io.github.puflik.plinth.ffi.generated.NetRequest as RustNetRequest
import io.github.puflik.plinth.ffi.generated.OnlineKind as RustOnlineKind
import io.github.puflik.plinth.ffi.generated.OnlineProblem as RustOnlineProblem
import io.github.puflik.plinth.ffi.generated.OnlineResult as RustOnlineResult
import io.github.puflik.plinth.ffi.generated.OnlineSearch as RustOnlineSearch
import io.github.puflik.plinth.ffi.generated.OnlineTrackInfo as RustOnlineTrackInfo
import io.github.puflik.plinth.ffi.generated.OnlineVariant as RustOnlineVariant
import io.github.puflik.plinth.ffi.generated.StreamAddress as RustStreamAddress

/**
 * Онлайн-API ядра (E3) → типы приложения и обратно: сеть Kotlin отвечает на
 * запросы ядра, треки альбома возвращаются в ядро при действии — без потерь.
 */
class CoreOnlineMappingTest {
    @Test
    fun `the transport gets the request of the core as it is`() {
        var seen: NetRequest? = null
        val transport =
            NetTransport { request ->
                seen = request
                NetAnswer.TooLarge
            }.toRust()

        transport.get(
            RustNetRequest(
                url = "https://archive.org/advancedsearch.php?q=piano",
                headers = listOf(RustNetHeader("User-Agent", "Plinth/0.2"), RustNetHeader("Accept", "*/*")),
                timeoutMs = 15_000u,
                maxBodyBytes = 1_048_576u,
            ),
        )

        assertThat(seen)
            .isEqualTo(
                NetRequest(
                    url = "https://archive.org/advancedsearch.php?q=piano",
                    headers = listOf("User-Agent" to "Plinth/0.2", "Accept" to "*/*"),
                    timeout = 15.seconds,
                    maxBodyBytes = 1_048_576,
                ),
            )
    }

    @Test
    fun `every answer of the transport reaches the core`() {
        val body = "{}".toByteArray()
        val answers =
            listOf(NetAnswer.Response(404, body), NetAnswer.NoAnswer("SocketTimeoutException"), NetAnswer.TooLarge)

        val rust = answers.map { answer -> NetTransport { answer }.toRust().get(REQUEST) }

        val response = rust[0] as RustNetAnswer.Response
        assertThat(response.status).isEqualTo(404.toUShort())
        assertThat(response.body).isEqualTo(body)
        assertThat(rust.drop(1))
            .containsExactly(RustNetAnswer.NoAnswer("SocketTimeoutException"), RustNetAnswer.TooLarge)
            .inOrder()
    }

    @Test
    fun `a transport that throws is no answer without the address`() {
        val transport = NetTransport { throw IllegalStateException("https://archive.org/secret?q=my library") }.toRust()

        val answer = transport.get(REQUEST)

        assertThat(answer).isEqualTo(RustNetAnswer.NoAnswer("IllegalStateException"))
    }

    @Test
    fun `a search section keeps its results and its problem`() {
        val sections =
            listOf(
                RustOnlineSearch(
                    provider = "archive.org",
                    results =
                        listOf(
                            RustOnlineResult("MIXG031", RustOnlineKind.ALBUM, "Mix", "Magnatune", 2004u, null),
                            RustOnlineResult("t1", RustOnlineKind.TRACK, "Song", null, null, 61_500u),
                        ),
                    problem = null,
                ),
                RustOnlineSearch("archive.org", emptyList(), RustOnlineProblem.NO_NETWORK),
                RustOnlineSearch("archive.org", emptyList(), RustOnlineProblem.PROVIDER_DOWN),
                RustOnlineSearch("archive.org", emptyList(), RustOnlineProblem.BROKEN),
            ).map { it.toApp() }

        assertThat(sections[0].results)
            .containsExactly(
                OnlineResult("MIXG031", OnlineKind.ALBUM, "Mix", "Magnatune", 2004, null),
                OnlineResult("t1", OnlineKind.TRACK, "Song", null, null, 61_500.milliseconds),
            ).inOrder()
        assertThat(sections.map { it.problem })
            .containsExactly(null, OnlineProblem.NO_NETWORK, OnlineProblem.PROVIDER_DOWN, OnlineProblem.BROKEN)
            .inOrder()
    }

    @Test
    fun `an album track goes back to the core unchanged`() {
        val rust =
            RustOnlineTrackInfo(
                external = "78_oh/a.flac",
                title = "\"OH DOCTOR\"",
                artist = "Naomi Brown",
                album = "\"OH DOCTOR\"",
                number = 1u,
                year = 1929u,
                durationMs = 127_450u,
                mbid = "b1a9c0e9-d987-4042-ae91-78d6a3267d69",
                variants =
                    listOf(
                        RustOnlineVariant("78_oh/a.flac", RustFormat.FLAC, null),
                        RustOnlineVariant("78_oh/a.mp3", RustFormat.MP3, 233u),
                    ),
            )

        val track = rust.toApp()

        assertThat(track.duration).isEqualTo(127_450.milliseconds)
        assertThat(track.variants.map { it.format }).containsExactly(AudioFormat.FLAC, AudioFormat.MP3).inOrder()
        assertThat(track.toRust()).isEqualTo(rust)
        assertThat(AudioFormat.entries.map { it.toRust().toApp() }).isEqualTo(AudioFormat.entries)
    }

    @Test
    fun `a stream address keeps its headers`() {
        val address =
            RustStreamAddress(
                "https://archive.org/download/x/a.mp3",
                listOf(RustNetHeader("Authorization", "t")),
            ).toApp()

        assertThat(address).isEqualTo(
            StreamAddress(
                "https://archive.org/download/x/a.mp3",
                listOf(
                    "Authorization" to "t",
                ),
            ),
        )
    }

    private companion object {
        val REQUEST = RustNetRequest("https://archive.org/metadata/x", emptyList(), 1_000u, 10u)
    }
}
