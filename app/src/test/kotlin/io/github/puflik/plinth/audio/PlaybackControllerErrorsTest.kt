package io.github.puflik.plinth.audio

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.audio.engine.PlaybackError
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.core.FailedTrack
import io.github.puflik.plinth.core.TrackProblem
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Очередь на треке, который не играет (G3): недоступный, битый и чужой формат
 * пропускаются, остальное останавливает игру; о пропусках — одно сообщение
 * за серию.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerErrorsTest {
    private val engine = FakeAudioEngine()
    private val album = QueueContext.Album("Help!", "The Beatles")
    private val tracks = (0 until 3).map { item("track-$it") }
    private val errors = mutableListOf<AppError>()

    @Test
    fun `unavailable track is skipped and the next one plays`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            engine.markUnavailable(tracks[1].source)

            controller.play(album, tracks, start = 1)

            assertThat(engine.preparedSources).containsExactly(tracks[1].source, tracks[2].source).inOrder()
            assertThat(controller.queue.value.current).isEqualTo(tracks[2])
            assertThat(controller.state.value).isEqualTo(PlaybackState.Playing)
            assertThat(errors).containsExactly(AppError.TracksSkipped(listOf(failed(1, TrackProblem.UNAVAILABLE))))
        }

    /** Несколько подряд — одно сообщение, когда заиграл следующий. */
    @Test
    fun `a run of skipped tracks is reported once`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            engine.markUnavailable(tracks[0].source)
            engine.markUnavailable(tracks[1].source)

            controller.play(album, tracks, start = 0)

            assertThat(controller.queue.value.current).isEqualTo(tracks[2])
            val skipped = listOf(failed(0, TrackProblem.UNAVAILABLE), failed(1, TrackProblem.UNAVAILABLE))
            assertThat(errors).containsExactly(AppError.TracksSkipped(skipped))
        }

    @Test
    fun `damaged and unsupported files are skipped too`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            engine.markBroken(tracks[0].source, PlaybackError.Malformed())
            engine.markBroken(tracks[1].source, PlaybackError.UnsupportedFormat())

            controller.play(album, tracks, start = 0)

            assertThat(controller.queue.value.current).isEqualTo(tracks[2])
            val skipped = listOf(failed(0, TrackProblem.UNPLAYABLE), failed(1, TrackProblem.UNPLAYABLE))
            assertThat(errors).containsExactly(AppError.TracksSkipped(skipped))
        }

    @Test
    fun `track that breaks while playing is skipped`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.play(album, tracks, start = 0)

            engine.failWith(PlaybackError.Malformed())

            assertThat(controller.queue.value.current).isEqualTo(tracks[1])
            assertThat(controller.state.value).isEqualTo(PlaybackState.Playing)
            assertThat(errors).containsExactly(AppError.TracksSkipped(listOf(failed(0, TrackProblem.UNPLAYABLE))))
        }

    @Test
    fun `network and unexplained errors stop on the track`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            engine.markBroken(tracks[0].source, PlaybackError.Network())

            controller.play(album, tracks, start = 0)
            assertThat(engine.preparedSources).containsExactly(tracks[0].source)
            assertThat(controller.queue.value.current).isEqualTo(tracks[0])

            controller.play(album, tracks, start = 1)
            engine.failWith(PlaybackError.Unknown())

            assertThat(controller.queue.value.current).isEqualTo(tracks[1])
            assertThat(controller.state.value).isInstanceOf(PlaybackState.Error::class.java)
            assertThat(errors)
                .containsExactly(
                    AppError.PlaybackStopped(failed(0, TrackProblem.NETWORK)),
                    AppError.PlaybackStopped(failed(1, TrackProblem.UNKNOWN)),
                ).inOrder()
        }

    /** Повтор одного трека держит доигравший трек, но не битый — иначе он бы повторял ошибку. */
    @Test
    fun `repeat one does not hold a broken track`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.cycleRepeat()
            controller.cycleRepeat()
            engine.markUnavailable(tracks[1].source)

            controller.play(album, tracks, start = 1)

            assertThat(engine.preparedSources).containsExactly(tracks[1].source, tracks[2].source).inOrder()
            assertThat(controller.queue.value.current).isEqualTo(tracks[2])
        }

    /** С повтором всей очереди пропуск по кругу не кончился бы никогда. */
    @Test
    fun `queue where nothing plays stops after trying every track once`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.cycleRepeat()
            tracks.forEach { engine.markUnavailable(it.source) }

            controller.play(album, tracks, start = 0)

            assertThat(engine.preparedSources).containsExactlyElementsIn(tracks.map(QueueItem::source)).inOrder()
            assertThat(controller.state.value).isInstanceOf(PlaybackState.Error::class.java)
            assertThat(errors).containsExactly(
                AppError.PlaybackStopped(
                    failed(2, TrackProblem.UNAVAILABLE),
                    skipped = listOf(failed(0, TrackProblem.UNAVAILABLE), failed(1, TrackProblem.UNAVAILABLE)),
                ),
            )
        }

    @Test
    fun `failed tracks at the end of the queue stop there`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            engine.markUnavailable(tracks[1].source)
            engine.markUnavailable(tracks[2].source)

            controller.play(album, tracks, start = 1)

            assertThat(controller.queue.value.current).isEqualTo(tracks[2])
            assertThat(errors).containsExactly(
                AppError.PlaybackStopped(
                    failed(2, TrackProblem.UNAVAILABLE),
                    skipped = listOf(failed(1, TrackProblem.UNAVAILABLE)),
                ),
            )
        }

    /** Решение автора: пропавший трек не уходит из очереди — вернётся файл, сыграет. */
    @Test
    fun `skipped track keeps its place in the queue`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            engine.markUnavailable(tracks[1].source)
            controller.play(album, tracks, start = 1)

            controller.previous()

            assertThat(controller.queue.value.contextItems).isEqualTo(tracks)
            assertThat(engine.preparedSources)
                .containsExactly(tracks[1].source, tracks[2].source, tracks[1].source, tracks[2].source)
                .inOrder()
        }

    /** Сохранённую очередь возвращает система, а не человек: дальше — так же на паузе и без шума. */
    @Test
    fun `restored queue skips an unavailable track and stays paused`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            engine.markUnavailable(tracks[0].source)

            controller.restore(PlaybackQueue.EMPTY.play(album, tracks, start = 0), position = 30.seconds)

            assertThat(controller.queue.value.current).isEqualTo(tracks[1])
            assertThat(engine.lastParams?.autoPlay).isFalse()
            assertThat(engine.lastParams?.startPosition).isEqualTo(Duration.ZERO)
            assertThat(controller.state.value).isEqualTo(PlaybackState.Paused)
            assertThat(errors).containsExactly(
                AppError.TracksSkipped(listOf(failed(0, TrackProblem.UNAVAILABLE)), whileRestoring = true),
            )
        }

    private fun TestScope.controller(): PlaybackController {
        val controller = PlaybackController(engine, backgroundScope)
        backgroundScope.launch { controller.errors.collect(errors::add) }
        return controller
    }

    private fun failed(
        index: Int,
        problem: TrackProblem,
    ) = FailedTrack(tracks[index].title, tracks[index].source.key, problem)

    private fun item(name: String) = QueueItem(AudioSource.LocalFile("content://plinth.test/$name.flac"), title = name)
}
