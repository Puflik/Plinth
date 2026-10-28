package io.github.puflik.plinth.audio

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.history.ListeningRecorder
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.queue.QueueStore
import io.github.puflik.plinth.queue.SavedQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Дописать очередь радио, не прервав текущий трек (Ю4в): движок не трогается, а сохранённая секунда и история
 * прослушиваний не принимают дописанную очередь за другое место.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu4cControllerExtendTest {
    private val engine = FakeAudioEngine()
    private val store = MemoryStore()
    private val radio = QueueContext.Radio("youtube", "seed", "Seed")
    private val loaded = (0 until 4).map { item("radio-$it") }
    private val more = (0 until 3).map { item("more-$it") }

    @Test
    fun `extending leaves the engine and the current track alone`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = PlaybackController(engine, backgroundScope)
            controller.play(radio, loaded, start = 1)
            controller.seekTo(40.seconds)
            val prepared = engine.preparedSources

            val applied = controller.extend(radio, base = 4, items = more)

            assertThat(applied).isTrue()
            assertThat(engine.preparedSources).isEqualTo(prepared)
            assertThat(controller.state.value).isEqualTo(PlaybackState.Playing)
            assertThat(controller.progress.value.position).isEqualTo(40.seconds)
            assertThat(controller.queue.value.current).isEqualTo(loaded[1])
            assertThat(controller.queue.value.upcoming).containsExactlyElementsIn(loaded.drop(2) + more).inOrder()
        }

    @Test
    fun `a stale extension is refused and the queue is the same one`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = PlaybackController(engine, backgroundScope)
            controller.play(radio, loaded, start = 1)
            val before = controller.queue.value

            assertThat(controller.extend(radio, base = 3, items = more)).isFalse()
            assertThat(controller.extend(QueueContext.Tracks, base = 4, items = more)).isFalse()
            val another = QueueContext.Radio("youtube", "other", "Seed")
            assertThat(controller.extend(another, base = 4, items = more)).isFalse()

            assertThat(controller.queue.value).isSameInstanceAs(before)
        }

    @Test
    fun `the saved queue grows and the saved second stays on pause`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = PlaybackController(engine, backgroundScope)
            QueueKeeper(controller, store, backgroundScope, clock()).start()
            controller.play(radio, loaded, start = 1)
            controller.seekTo(150.seconds)
            controller.togglePlayPause()

            controller.extend(radio, base = 4, items = more)

            assertThat(store.saved?.queue).isEqualTo(controller.queue.value)
            assertThat(store.saved?.queue?.contextItems).hasSize(7)
            assertThat(store.saved?.position).isEqualTo(150.seconds)
        }

    @Test
    fun `the history gets one listening across an extension`() =
        runTest(UnconfinedTestDispatcher()) {
            val userData = FakeUserDataRepository()
            val controller = PlaybackController(engine, backgroundScope)
            ListeningRecorder(controller, userData, backgroundScope, clock()).start()
            controller.play(radio, loaded, start = 0)

            advanceTimeBy(40.seconds)
            controller.extend(radio, base = 4, items = more)
            advanceTimeBy(3.minutes)
            engine.completeTrack()

            val plays = userData.recorded
            assertThat(plays).hasSize(1)
            assertThat(plays[0].track).isEqualTo(TrackId("radio-0"))
            assertThat(plays[0].listened).isEqualTo(3.minutes + 40.seconds)
            assertThat(plays[0].skippedAt).isNull()
        }

    private fun item(id: String) = QueueItem(AudioSource.Online(id), id, "Band")

    /** Часы теста — виртуальное время планировщика от нуля эпохи. */
    private fun TestScope.clock() =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)
        }

    /** Хранилище в памяти. */
    private class MemoryStore : QueueStore {
        var saved: SavedQueue? = null

        override suspend fun load(): SavedQueue? = saved

        override suspend fun saveQueue(queue: PlaybackQueue) {
            saved = SavedQueue(queue, Duration.ZERO)
        }

        override suspend fun savePosition(position: Duration) {
            saved = saved?.copy(position = position)
        }

        override suspend fun savePlayedAt(at: Instant) = Unit
    }
}
