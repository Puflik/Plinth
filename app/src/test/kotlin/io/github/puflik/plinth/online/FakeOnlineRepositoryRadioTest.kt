package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.OnlineOrigin
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.OnlineVariant
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.online.FakeOnlineRepository.RadioCall
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Радио и источники трека у `FakeOnlineRepository` (Ю4а, `docs/work/yu4a.md`): очередь и меню (yu4c) пишут свои тесты
 * на этом фейке, поэтому его поведение закреплено здесь. Выключено, нет сети и отдых провайдера действуют так же, как у
 * альбома; `origins` фейк выводит из `add`.
 */
class FakeOnlineRepositoryRadioTest {
    private val fake = FakeOnlineRepository()
    private val page = OnlineRadio.Page(listOf(track("aaaaaaaaaaa")), next = "page-2")
    private val down = OnlineRadio.Failed(OnlineProblem.PROVIDER_DOWN)

    @Test
    fun `a scripted page comes back for its provider, seed and key only`() =
        runTest {
            fake.setEnabled(true)
            fake.scriptRadio("youtube", "dQw4w9WgXcQ", null, page)

            val first = fake.radio("youtube", "dQw4w9WgXcQ")
            val wrongKey = fake.radio("youtube", "dQw4w9WgXcQ", "page-2")
            val wrongSeed = fake.radio("youtube", "other")
            val wrongProvider = fake.radio("archive.org", "dQw4w9WgXcQ")

            assertThat(first).isEqualTo(page)
            assertThat(listOf(wrongKey, wrongSeed, wrongProvider)).containsExactly(down, down, down)
            assertThat(fake.radioCalls)
                .containsExactly(
                    RadioCall("youtube", "dQw4w9WgXcQ", null),
                    RadioCall("youtube", "dQw4w9WgXcQ", "page-2"),
                    RadioCall("youtube", "other", null),
                    RadioCall("archive.org", "dQw4w9WgXcQ", null),
                ).inOrder()
        }

    @Test
    fun `a page can be the last one`() =
        runTest {
            fake.setEnabled(true)
            val last = OnlineRadio.Page(listOf(track("bbbbbbbbbbb")), next = null)
            fake.scriptRadio("youtube", "dQw4w9WgXcQ", "page-2", last)

            assertThat(fake.radio("youtube", "dQw4w9WgXcQ", "page-2")).isEqualTo(last)
        }

    @Test
    fun `with the sources off or the provider switched off there is no radio even when scripted`() =
        runTest {
            fake.scriptRadio("youtube", "dQw4w9WgXcQ", null, page)
            fake.scriptRadio("other.test", "dQw4w9WgXcQ", null, page)

            val off = fake.radio("youtube", "dQw4w9WgXcQ")
            fake.setEnabled(true, disabled = setOf("youtube"))
            val providerOff = fake.radio("youtube", "dQw4w9WgXcQ")
            val others = fake.radio("other.test", "dQw4w9WgXcQ")

            assertThat(off).isEqualTo(down)
            assertThat(providerOff).isEqualTo(down)
            assertThat(others).isEqualTo(page)
        }

    @Test
    fun `no network is no network and a resting provider is down`() =
        runTest {
            fake.setEnabled(true)
            fake.scriptRadio("youtube", "dQw4w9WgXcQ", null, page)

            fake.networkUp = false
            val offline = fake.radio("youtube", "dQw4w9WgXcQ")
            fake.networkUp = true
            fake.providerDown = true
            val resting = fake.radio("youtube", "dQw4w9WgXcQ")
            fake.providerDown = false
            val back = fake.radio("youtube", "dQw4w9WgXcQ")

            assertThat(offline).isEqualTo(OnlineRadio.Failed(OnlineProblem.NO_NETWORK))
            assertThat(resting).isEqualTo(down)
            assertThat(back).isEqualTo(page)
        }

    @Test
    fun `radio writes nothing to the catalog`() =
        runTest {
            fake.setEnabled(true)
            fake.scriptRadio("youtube", "dQw4w9WgXcQ", null, page)

            fake.radio("youtube", "dQw4w9WgXcQ")

            assertThat(fake.known("youtube", page.tracks)).containsExactly(null)
        }

    @Test
    fun `the origins of a track are the provider and the variants it was added with`() =
        runTest {
            val youtubeIds = fake.add("youtube", listOf(track("aaaaaaaaaaa")))
            val archiveIds = fake.add("archive.org", listOf(track("bbbbbbbbbbb")))

            assertThat(fake.origins(youtubeIds.single()))
                .containsExactly(OnlineOrigin("youtube", "aaaaaaaaaaa/251"), OnlineOrigin("youtube", "aaaaaaaaaaa/140"))
                .inOrder()
            assertThat(fake.origins(archiveIds.single()))
                .containsExactly(
                    OnlineOrigin("archive.org", "bbbbbbbbbbb/251"),
                    OnlineOrigin("archive.org", "bbbbbbbbbbb/140"),
                ).inOrder()
            assertThat(fake.origins(TrackId("nobody"))).isEmpty()
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
