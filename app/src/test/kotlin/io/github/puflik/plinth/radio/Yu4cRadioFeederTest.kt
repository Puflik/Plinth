package io.github.puflik.plinth.radio

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.core.RadioProblem
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.online.OnlineRadio
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Подгрузка радио (Ю4в): когда впереди остаётся пять треков контекста, `RadioFeeder` просит следующую страницу, заводит
 * её новые треки в каталог и дописывает в конец контекста — один запрос за раз, без повторов, не дальше трёхсот
 * треков. Сеть и каталог — в памяти (`RadioRig`), «впереди» считается по порядку обхода: `order.size - 1 - position`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu4cRadioFeederTest {
    @Test
    fun `a radio with more than five tracks ahead asks for nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()

            rig.playRadio(rig.tracks(7))

            assertThat(rig.calls).isEmpty()
        }

    @Test
    fun `five ahead is the threshold and the first request opens the radio from the seed`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            rig.script("seedvid", null, rig.page(rig.tracks(6, "n")))

            rig.playRadio(rig.tracks(7), start = 1)

            assertThat(rig.calls).containsExactly("seedvid" to null)
            assertThat(rig.controller.queue.value.contextItems).hasSize(13)
        }

    @Test
    fun `the page lands in the catalog and at the end of the context without touching the engine`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val fresh = listOf(rig.track("n1", "New One", "Artist A"), rig.track("n2", "New Two", null))
            rig.script("seedvid", null, rig.page(fresh, next = "k2"))

            val ids = rig.playRadio(rig.tracks(7), start = 3)

            val queue = rig.controller.queue.value
            assertThat(queue.contextItems).hasSize(9)
            val added = queue.contextItems.drop(7)
            assertThat(added.map { it.title }).containsExactly("New One", "New Two").inOrder()
            assertThat(added.map { it.artist }).containsExactly("Artist A", null).inOrder()
            assertThat(added.all { it.source is AudioSource.Online }).isTrue()
            assertThat(added[0].duration).isEqualTo(3.minutes)
            assertThat(queue.current?.source).isEqualTo(AudioSource.Online(ids[3].value))
            assertThat(rig.engine.preparedSources).hasSize(1)
            assertThat(rig.engine.state.value).isEqualTo(PlaybackState.Playing)
            assertThat(rig.repository.known("youtube", fresh)).doesNotContain(null)
        }

    @Test
    fun `each request carries the key of the page before and a finished radio asks no more`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            rig.script("seedvid", "k1", rig.page(rig.tracks(3, "a"), next = "k2"))
            rig.script("seedvid", "k2", rig.page(rig.tracks(3, "b"), next = null))
            rig.feeder.expect(RadioRig.RADIO, "k1")

            rig.playRadio(rig.tracks(6), start = 1)
            repeat(2) { rig.controller.next() }
            repeat(3) { rig.controller.next() }

            assertThat(rig.calls).containsExactly("seedvid" to "k1", "seedvid" to "k2").inOrder()
            assertThat(rig.controller.queue.value.contextItems).hasSize(12)
        }

    @Test
    fun `starting the same radio again takes the new key`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            rig.script("seedvid", "k9", rig.page(rig.tracks(7, "n")))
            rig.feeder.expect(RadioRig.RADIO, "k1")
            rig.feeder.expect(RadioRig.RADIO, "k9")

            rig.playRadio(rig.tracks(4))

            assertThat(rig.calls).containsExactly("seedvid" to "k9")
        }

    @Test
    fun `a key is never used for another radio`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            rig.feeder.expect(RadioRig.RADIO, "k1")

            rig.playRadio(rig.tracks(4), context = QueueContext.Radio("youtube", "otherseed", "Other"))

            assertThat(rig.calls).containsExactly("otherseed" to null)
        }

    @Test
    fun `tracks that are already in the queue do not come again`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val queued =
                listOf(
                    rig.track("q0", "Played", "Band"),
                    rig.track("q1", "Current", "Band"),
                    rig.track("q2", "Upcoming", "Band"),
                )
            val lookalike = rig.track("zz", "current!", "band")
            val fresh = listOf(rig.track("f1", "Fresh", "Band"), rig.track("f2", "Fresh Too", "Band"))
            val page = listOf(queued[0], lookalike, queued[2]) + fresh
            rig.script("seedvid", null, rig.page(page))

            rig.playRadio(queued, start = 1)

            val queue = rig.controller.queue.value
            val titles = queue.contextItems.map { it.title }
            assertThat(titles).containsExactly("Played", "Current", "Upcoming", "Fresh", "Fresh Too").inOrder()
            // В каталог идёт только то, что встало в очередь: двойник трека — нет.
            assertThat(rig.repository.known("youtube", listOf(lookalike))).containsExactly(null)
        }

    @Test
    fun `a page of repeats is skipped for the next one`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val queued = rig.tracks(4)
            rig.feeder.expect(RadioRig.RADIO, "k1")
            rig.script("seedvid", "k1", rig.page(queued.take(2), next = "k2"))
            rig.script("seedvid", "k2", rig.page(listOf(rig.track("z", "Zed")), next = null))

            rig.playRadio(queued)

            assertThat(rig.calls.map { it.second }).containsExactly("k1", "k2").inOrder()
            val queue = rig.controller.queue.value
            val titles = queue.contextItems.map { it.title }
            assertThat(titles).containsExactly("Song v0", "Song v1", "Song v2", "Song v3", "Zed").inOrder()
        }

    @Test
    fun `three pages of repeats in a row end the radio`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val queued = rig.tracks(3)
            rig.feeder.expect(RadioRig.RADIO, "k1")
            rig.script("seedvid", "k1", rig.page(queued, next = "k2"))
            rig.script("seedvid", "k2", rig.page(queued, next = "k3"))
            rig.script("seedvid", "k3", rig.page(queued, next = "k4"))
            rig.script("seedvid", "k4", rig.page(rig.tracks(2, "late"), next = null))

            rig.playRadio(queued)
            repeat(2) { rig.controller.next() }

            assertThat(rig.calls.map { it.second }).containsExactly("k1", "k2", "k3").inOrder()
            assertThat(rig.controller.queue.value.contextItems).hasSize(3)
        }

    @Test
    fun `only one request is in the air`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val gate = CompletableDeferred<Unit>()
            rig.online.radioGate = gate
            rig.script("seedvid", null, rig.page(rig.tracks(6, "n")))

            rig.playRadio(rig.tracks(3))
            rig.controller.next()
            rig.controller.togglePlayPause()
            rig.controller.togglePlayPause()
            assertThat(rig.online.radioStarted).hasSize(1)

            gate.complete(Unit)

            assertThat(rig.online.radioStarted).hasSize(1)
            assertThat(rig.controller.queue.value.contextItems).hasSize(9)
        }

    @Test
    fun `a failed key reopens the radio from the seed once and goes on`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            rig.feeder.expect(RadioRig.RADIO, "old")
            rig.script("seedvid", "old", OnlineRadio.Failed(OnlineProblem.PROVIDER_DOWN))
            rig.script("seedvid", null, rig.page(rig.tracks(7, "n"), next = "k2"))
            rig.script("seedvid", "k2", rig.page(rig.tracks(2, "m"), next = null))

            rig.playRadio(rig.tracks(4))
            repeat(5) { rig.controller.next() }

            assertThat(rig.calls.map { it.second }).containsExactly("old", null, "k2").inOrder()
            assertThat(rig.controller.queue.value.contextItems).hasSize(13)
        }

    @Test
    fun `a failure without a key waits thirty seconds before the next try`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()

            rig.playRadio(rig.tracks(6), start = 1)
            assertThat(rig.calls).hasSize(1)

            advanceTimeBy(10.seconds)
            rig.controller.next()
            assertThat(rig.calls).hasSize(1)

            advanceTimeBy(21.seconds)
            rig.controller.next()
            assertThat(rig.calls).hasSize(2)
        }

    @Test
    fun `a failed key and a failed reopening wait thirty seconds and try the key first again`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            rig.feeder.expect(RadioRig.RADIO, "stale")

            rig.playRadio(rig.tracks(6), start = 1)
            assertThat(rig.calls.map { it.second }).containsExactly("stale", null).inOrder()

            advanceTimeBy(10.seconds)
            rig.controller.next()
            assertThat(rig.calls).hasSize(2)

            advanceTimeBy(21.seconds)
            rig.controller.next()
            val keys = rig.calls.map { it.second }
            assertThat(keys[2]).isEqualTo("stale")
        }

    @Test
    fun `the context stops at three hundred tracks`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            rig.script("seedvid", null, rig.page(rig.tracks(24, "n"), next = "k2"))

            rig.playRadio(rig.tracks(296), start = 291)
            repeat(5) { rig.controller.next() }

            val items = rig.controller.queue.value.contextItems
            assertThat(items).hasSize(300)
            assertThat(items.takeLast(4).map { it.title })
                .containsExactly("Song n0", "Song n1", "Song n2", "Song n3")
                .inOrder()
            assertThat(rig.calls).hasSize(1)
        }

    @Test
    fun `other contexts are left alone`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val songs = listOf(local("/m/1.mp3", "One"), local("/m/2.mp3", "Two"))

            rig.controller.play(QueueContext.Album("Album", null), songs, start = 0)
            rig.controller.play(QueueContext.Search("one"), songs, start = 1)
            rig.controller.play(QueueContext.Tracks, songs, start = 0)

            assertThat(rig.calls).isEmpty()
        }

    @Test
    fun `a restored radio waits for the first play`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val saved = PlaybackQueue.EMPTY.play(RadioRig.RADIO, rig.tracks(3).map(::itemOf), start = 0)

            rig.controller.restore(saved, 42.seconds)
            assertThat(rig.engine.state.value).isEqualTo(PlaybackState.Paused)
            assertThat(rig.calls).isEmpty()

            rig.controller.togglePlayPause()

            assertThat(rig.calls).containsExactly("seedvid" to null)
        }

    @Test
    fun `a page that comes after the person left the radio is dropped and costs the catalog nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val gate = CompletableDeferred<Unit>()
            rig.online.radioGate = gate
            val page = rig.tracks(6, "n")
            rig.script("seedvid", null, rig.page(page))
            rig.playRadio(rig.tracks(3))
            val songs = listOf(local("/m/1.mp3", "One"), local("/m/2.mp3", "Two"))
            rig.controller.play(QueueContext.Album("Album", null), songs, start = 0)

            gate.complete(Unit)

            val queue = rig.controller.queue.value
            assertThat(queue.context).isEqualTo(QueueContext.Album("Album", null))
            assertThat(queue.contextItems).isEqualTo(songs)
            assertThat(rig.engine.preparedSources).hasSize(2)
            assertThat(rig.repository.known("youtube", page).filterNotNull()).isEmpty()
            assertThat(rig.told).isEmpty()
        }

    @Test
    fun `the end of a radio that could not be fed is told once`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            rig.playRadio(rig.tracks(2))
            rig.controller.next()
            assertThat(rig.told).isEmpty()

            rig.engine.completeTrack()
            assertThat(rig.told).containsExactly(RadioProblem.STOPPED)

            // Пока конец тянется, правки очереди о нём заново не говорят (повтор: выключен → всё → один → выключен);
            // новый конец после повторной игры — говорят.
            repeat(3) { rig.controller.cycleRepeat() }
            assertThat(rig.told).containsExactly(RadioProblem.STOPPED)
            rig.controller.togglePlayPause()
            rig.engine.completeTrack()
            assertThat(rig.told).containsExactly(RadioProblem.STOPPED, RadioProblem.STOPPED)
        }

    @Test
    fun `a page that comes while the loaded part has just ended carries the playback on`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val gate = CompletableDeferred<Unit>()
            rig.online.radioGate = gate
            rig.script("seedvid", null, rig.page(rig.tracks(3, "n")))
            rig.playRadio(rig.tracks(1))

            rig.engine.completeTrack()
            assertThat(rig.engine.state.value).isEqualTo(PlaybackState.Ended)
            gate.complete(Unit)

            assertThat(rig.engine.state.value).isEqualTo(PlaybackState.Playing)
            val current = rig.controller.queue.value.current
            assertThat(current?.title).isEqualTo("Song n0")
            assertThat(rig.told).isEmpty()
        }

    private suspend fun TestScope.rig() = RadioRig(this).also { it.enable() }

    /** Элемент очереди для трека, которого в каталоге нет: так выглядит радио, прочитанное с диска после запуска. */
    private fun itemOf(track: OnlineTrack) =
        QueueItem(AudioSource.Online("id-${track.external}"), track.title, track.artist)

    private fun local(
        uri: String,
        title: String,
    ) = QueueItem(AudioSource.LocalFile(uri), title)
}
