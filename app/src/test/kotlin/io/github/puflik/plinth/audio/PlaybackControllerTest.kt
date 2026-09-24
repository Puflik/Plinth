package io.github.puflik.plinth.audio

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.audio.engine.TrackInfo
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueAction
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.queue.RepeatMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Фасад воспроизведения для UI (B2–B4, D) на `FakeAudioEngine`: звук и очередь. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {
    private val engine = FakeAudioEngine()
    private val album = QueueContext.Album("Jazz", "Queen")
    private val tracks = (0 until 3).map { item("track-$it") }
    private val manual = item("manual")

    @Test
    fun `played context starts at the chosen track at once`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()

            controller.play(album, tracks, start = 1)

            assertThat(engine.preparedSources).containsExactly(tracks[1].source)
            assertThat(engine.lastParams?.autoPlay).isTrue()
            assertThat(controller.state.value).isEqualTo(PlaybackState.Playing)
            assertThat(controller.queue.value.current).isEqualTo(tracks[1])
        }

    /** Файл без тегов снаружи показывается так же, как в приложении. */
    @Test
    fun `track titles go to the engine for the system to show`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            val song = item("race").copy(title = "Bicycle Race", artist = "Queen", album = "Jazz")

            controller.play(album, listOf(song), start = 0)

            assertThat(engine.lastParams?.info).isEqualTo(TrackInfo("Bicycle Race", "Queen", "Jazz"))
        }

    @Test
    fun `restored track carries its titles too`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()

            controller.restore(PlaybackQueue.EMPTY.play(album, tracks, start = 1), position = 30.seconds)

            assertThat(engine.lastParams?.info).isEqualTo(TrackInfo("track-1"))
        }

    /** Двойной тап и удержание ⏮/⏭ (E5): перемотка на сдвиг, не за края трека. */
    @Test
    fun `seek by moves within the track and stops at its edges`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            engine.trackDuration = 60.seconds
            controller.play(album, tracks, start = 0)
            controller.seekTo(30.seconds)

            controller.seekBy(10.seconds)
            assertThat(controller.progress.value.position).isEqualTo(40.seconds)
            controller.seekBy((-50).seconds)
            assertThat(controller.progress.value.position).isEqualTo(0.seconds)
            controller.seekBy(90.seconds)
            assertThat(controller.progress.value.position).isEqualTo(60.seconds)
        }

    @Test
    fun `upcoming tracks can be removed and moved`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 0)

            controller.moveUpcoming(from = 1, to = 0, item = tracks[2])
            assertThat(controller.queue.value.upcoming).containsExactly(tracks[2], tracks[1]).inOrder()

            controller.removeUpcoming(index = 1, item = tracks[1])
            assertThat(controller.queue.value.upcoming).containsExactly(tracks[2])
            assertThat(engine.preparedSources).containsExactly(tracks[0].source)
        }

    /** Очередь сдвинулась, пока палец был на экране: на этом месте уже другой трек. */
    @Test
    fun `edit of a track that is no longer there does nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 0)
            val before = controller.queue.value

            controller.removeUpcoming(index = 0, item = tracks[2])
            controller.moveUpcoming(from = 0, to = 1, item = tracks[2])
            controller.removeUpcoming(index = 5, item = tracks[1])
            controller.moveUpcoming(from = 0, to = 5, item = tracks[1])

            assertThat(controller.queue.value).isEqualTo(before)
        }

    @Test
    fun `finished track makes way for the next one`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 0)

            engine.completeTrack()

            assertThat(engine.preparedSources).containsExactly(tracks[0].source, tracks[1].source).inOrder()
            assertThat(controller.state.value).isEqualTo(PlaybackState.Playing)
            assertThat(controller.queue.value.current).isEqualTo(tracks[1])
        }

    @Test
    fun `queue stops after its last track`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 2)

            engine.completeTrack()

            assertThat(engine.preparedSources).hasSize(1)
            assertThat(controller.state.value).isEqualTo(PlaybackState.Ended)
        }

    @Test
    fun `repeat one plays the finished track again`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.cycleRepeat()
            controller.cycleRepeat()
            controller.play(album, tracks, start = 0)

            engine.completeTrack()

            assertThat(controller.queue.value.repeat).isEqualTo(RepeatMode.ONE)
            assertThat(engine.preparedSources).containsExactly(tracks[0].source, tracks[0].source)
        }

    @Test
    fun `next and previous move through the queue`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 0)

            controller.next()
            assertThat(controller.queue.value.current).isEqualTo(tracks[1])

            controller.previous()
            assertThat(controller.queue.value.current).isEqualTo(tracks[0])
            assertThat(engine.preparedSources.last()).isEqualTo(tracks[0].source)
        }

    @Test
    fun `previous a few seconds into a track starts it over`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 1)
            controller.seekTo(PlaybackController.RESTART_THRESHOLD + 1.seconds)

            controller.previous()

            assertThat(controller.queue.value.current).isEqualTo(tracks[1])
            assertThat(controller.progress.value.position).isEqualTo(0.seconds)
            assertThat(engine.preparedSources).hasSize(1)
        }

    @Test
    fun `previous on the first track starts it over`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 0)

            controller.previous()

            assertThat(engine.preparedSources).hasSize(1)
            assertThat(controller.progress.value.position).isEqualTo(0.seconds)
        }

    @Test
    fun `next at the end of the queue does nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 2)

            controller.next()

            assertThat(engine.preparedSources).hasSize(1)
            assertThat(controller.state.value).isEqualTo(PlaybackState.Playing)
        }

    @Test
    fun `manual track waits for the current one`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 0)

            controller.perform(QueueAction.PLAY_NEXT, manual)
            assertThat(engine.preparedSources).hasSize(1)

            engine.completeTrack()
            assertThat(controller.queue.value.current).isEqualTo(manual)
        }

    @Test
    fun `manual track added to a silent player starts playing`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()

            controller.perform(QueueAction.ADD_TO_QUEUE, manual)

            assertThat(engine.preparedSources).containsExactly(manual.source)
            assertThat(controller.queue.value.current).isEqualTo(manual)
        }

    @Test
    fun `replacing the queue drops manual tracks and plays at once`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 0)
            controller.perform(QueueAction.ADD_TO_QUEUE, manual)

            controller.replace(QueueContext.Tracks, tracks, start = 2)

            assertThat(controller.queue.value.current).isEqualTo(tracks[2])
            assertThat(controller.queue.value.upcoming).isEmpty()
            assertThat(engine.preparedSources.last()).isEqualTo(tracks[2].source)
        }

    @Test
    fun `shuffle and repeat switch in the queue`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 0)

            controller.toggleShuffle()
            assertThat(controller.queue.value.shuffle).isTrue()
            assertThat(controller.queue.value.current).isEqualTo(tracks[0])

            val modes = List(3) { controller.cycleRepeat().let { controller.queue.value.repeat } }
            assertThat(modes).containsExactly(RepeatMode.ALL, RepeatMode.ONE, RepeatMode.OFF).inOrder()
        }

    @Test
    fun `toggle pauses playing track and resumes paused one`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 0)

            controller.togglePlayPause()
            assertThat(controller.state.value).isEqualTo(PlaybackState.Paused)

            controller.togglePlayPause()
            assertThat(controller.state.value).isEqualTo(PlaybackState.Playing)
        }

    @Test
    fun `toggle after the end plays the last track again`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 2)
            engine.completeTrack()

            controller.togglePlayPause()

            assertThat(controller.state.value).isEqualTo(PlaybackState.Playing)
        }

    @Test
    fun `controls without a track do nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()

            controller.togglePlayPause()
            controller.seekTo(30.seconds)
            controller.next()
            controller.previous()

            assertThat(controller.state.value).isEqualTo(PlaybackState.Idle)
            assertThat(engine.preparedSources).isEmpty()
        }

    @Test
    fun `seek moves within the open track`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 0)

            controller.seekTo(30.seconds)

            assertThat(controller.progress.value.position).isEqualTo(30.seconds)
        }

    private fun TestScope.controller() = PlaybackController(engine, backgroundScope)

    private fun item(name: String) = QueueItem(AudioSource.LocalFile("content://plinth.test/$name.flac"), title = name)
}
