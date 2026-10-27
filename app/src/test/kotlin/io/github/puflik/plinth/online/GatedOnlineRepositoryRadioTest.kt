package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.OnlineOrigin
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.OnlineVariant
import io.github.puflik.plinth.online.FakeOnlineRepository.RadioCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Радио и `GatedOnlineRepository` (Ю4а, `docs/work/yu4a.md`; ворота — Р1.11г): восстановленное радио просит
 * страницу на холодном старте одновременно с `OnlineSwitch`, поэтому, как альбом, ждёт первого применения
 * переключателя — иначе ядро ответило бы «источники выключены». Источники трека (`origins`) — каталог, они не ждут.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatedOnlineRepositoryRadioTest {
    private val inner = FakeOnlineRepository()
    private val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)
    private val page = OnlineRadio.Page(listOf(track("aaaaaaaaaaa")), next = "page-2")

    @Test
    fun `a radio that comes before the switch waits for it and then asks`() =
        runTest {
            inner.scriptRadio("youtube", "dQw4w9WgXcQ", null, page)
            val radio = async { gated.radio("youtube", "dQw4w9WgXcQ") }
            runCurrent()
            val waiting = !radio.isCompleted
            val askedEarly = inner.radioCalls.toList()

            gated.setEnabled(true)
            runCurrent()

            assertThat(waiting).isTrue()
            assertThat(askedEarly).isEmpty()
            assertThat(radio.isCompleted).isTrue()
            assertThat(radio.await()).isEqualTo(page)
            assertThat(inner.radioCalls).containsExactly(RadioCall("youtube", "dQw4w9WgXcQ", null))
        }

    @Test
    fun `a switch that turns the sources off lets the radio through at once with nothing`() =
        runTest {
            inner.scriptRadio("youtube", "dQw4w9WgXcQ", null, page)
            val radio = async { gated.radio("youtube", "dQw4w9WgXcQ") }
            runCurrent()

            gated.setEnabled(false)
            runCurrent()

            assertThat(radio.isCompleted).isTrue()
            assertThat(radio.await()).isEqualTo(OnlineRadio.Failed(OnlineProblem.PROVIDER_DOWN))
        }

    @Test
    fun `a switch that never comes lets the radio through after the wait limit`() =
        runTest {
            val radio = async { gated.radio("youtube", "dQw4w9WgXcQ") }
            runCurrent()
            val waiting = !radio.isCompleted

            advanceTimeBy(5_001)
            runCurrent()

            assertThat(waiting).isTrue()
            assertThat(radio.isCompleted).isTrue()
            assertThat(inner.radioCalls).containsExactly(RadioCall("youtube", "dQw4w9WgXcQ", null))
        }

    @Test
    fun `the origins do not wait for the switch`() =
        runTest {
            val ids = inner.add("youtube", listOf(track("aaaaaaaaaaa")))
            val origins = async { gated.origins(ids.single()) }

            runCurrent()

            assertThat(origins.isCompleted).isTrue()
            assertThat(origins.await())
                .containsExactly(OnlineOrigin("youtube", "aaaaaaaaaaa/251"), OnlineOrigin("youtube", "aaaaaaaaaaa/140"))
        }

    private fun track(video: String) =
        OnlineTrack(
            external = video,
            title = "Song $video",
            artist = "Artist",
            variants =
                listOf(
                    OnlineVariant("$video/251", AudioFormat.OPUS, 160),
                    OnlineVariant("$video/140", AudioFormat.AAC, 128),
                ),
        )
}
