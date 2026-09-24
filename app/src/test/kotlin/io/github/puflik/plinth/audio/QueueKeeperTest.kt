package io.github.puflik.plinth.audio

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.queue.QueueStore
import io.github.puflik.plinth.queue.SavedQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Восстановление очереди при старте и её сохранение по ходу (D1.5, D2.1, D2.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueKeeperTest {
    private val engine = FakeAudioEngine()
    private val store = MemoryQueueStore()
    private val tracks =
        (0 until 3).map {
            QueueItem(
                AudioSource.LocalFile("content://plinth.test/$it.flac"),
                "track-$it",
            )
        }
    private val saved = PlaybackQueue.EMPTY.play(QueueContext.Album("Jazz", "Queen"), tracks, start = 1)

    @Test
    fun `saved queue comes back paused on the same second`() =
        runTest(UnconfinedTestDispatcher()) {
            store.saved = SavedQueue(saved, 42.seconds)
            val (controller, keeper) = keeper()

            keeper.start()

            assertThat(controller.queue.value).isEqualTo(saved)
            assertThat(engine.preparedSources).containsExactly(tracks[1].source)
            assertThat(engine.lastParams?.autoPlay).isFalse()
            assertThat(engine.lastParams?.startPosition).isEqualTo(42.seconds)
            assertThat(controller.state.value).isEqualTo(PlaybackState.Paused)
            assertThat(store.saved).isEqualTo(SavedQueue(saved, 42.seconds))
        }

    @Test
    fun `queue started before the restore is not replaced`() =
        runTest(UnconfinedTestDispatcher()) {
            store.saved = SavedQueue(saved, 42.seconds)
            val (controller, keeper) = keeper()
            controller.play(QueueContext.Tracks, tracks, start = 2)

            keeper.start()

            assertThat(controller.queue.value.current).isEqualTo(tracks[2])
            assertThat(engine.preparedSources).containsExactly(tracks[2].source)
        }

    @Test
    fun `every change of the queue is saved`() =
        runTest(UnconfinedTestDispatcher()) {
            val (controller, keeper) = keeper()
            keeper.start()

            controller.play(QueueContext.Tracks, tracks, start = 0)
            controller.next()

            assertThat(store.saved?.queue).isEqualTo(controller.queue.value)
        }

    @Test
    fun `position is saved in whole seconds as it goes`() =
        runTest(UnconfinedTestDispatcher()) {
            val (controller, keeper) = keeper()
            keeper.start()
            controller.play(QueueContext.Tracks, tracks, start = 0)

            controller.seekTo(10.seconds + 700.milliseconds)
            assertThat(store.saved?.position).isEqualTo(10.seconds)

            controller.togglePlayPause()
            controller.seekTo(12.seconds)
            assertThat(store.saved?.position).isEqualTo(12.seconds)
            assertThat(store.positionWrites).isEqualTo(3)
        }

    @Test
    fun `last play time comes back with the queue`() =
        runTest(UnconfinedTestDispatcher()) {
            store.saved = SavedQueue(saved, 42.seconds, playedAt = Instant.fromEpochSeconds(1_700_000_000))
            val (_, keeper) = keeper()

            keeper.start()

            assertThat(keeper.lastPlayed.first()).isEqualTo(Instant.fromEpochSeconds(1_700_000_000))
        }

    @Test
    fun `nothing played before is known as never`() =
        runTest(UnconfinedTestDispatcher()) {
            val (_, keeper) = keeper()

            keeper.start()

            assertThat(keeper.lastPlayed.first()).isNull()
        }

    @Test
    fun `restored paused queue is not a play`() =
        runTest(UnconfinedTestDispatcher()) {
            store.saved = SavedQueue(saved, 42.seconds, playedAt = Instant.fromEpochSeconds(1_700_000_000))
            val (_, keeper) = keeper()

            keeper.start()
            advanceTimeBy(5.minutes)

            assertThat(store.playedAt).isNull()
            assertThat(keeper.lastPlayed.first()).isEqualTo(Instant.fromEpochSeconds(1_700_000_000))
        }

    @Test
    fun `playing marks the time every minute and pause marks the moment`() =
        runTest(UnconfinedTestDispatcher()) {
            val (controller, keeper) = keeper()
            keeper.start()

            advanceTimeBy(10.seconds)
            controller.play(QueueContext.Tracks, tracks, start = 0)
            assertThat(store.playedAt).isEqualTo(at(10.seconds))

            advanceTimeBy(80.seconds)
            assertThat(store.playedAt).isEqualTo(at(70.seconds))

            controller.togglePlayPause()
            assertThat(store.playedAt).isEqualTo(at(90.seconds))
            assertThat(keeper.lastPlayed.first()).isEqualTo(at(90.seconds))

            advanceTimeBy(10.minutes)
            assertThat(store.playedAt).isEqualTo(at(90.seconds))
        }

    private fun at(time: Duration) = Instant.fromEpochMilliseconds(time.inWholeMilliseconds)

    private fun TestScope.keeper(): Pair<PlaybackController, QueueKeeper> {
        val controller = PlaybackController(engine, backgroundScope)
        // Часы теста — виртуальное время планировщика от нуля эпохи.
        val clock =
            object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)
            }
        return controller to QueueKeeper(controller, store, backgroundScope, clock)
    }

    /** Хранилище в памяти; считает записи позиции. */
    private class MemoryQueueStore : QueueStore {
        var saved: SavedQueue? = null
        var positionWrites = 0
        var playedAt: Instant? = null

        override suspend fun savePlayedAt(at: Instant) {
            playedAt = at
        }

        override suspend fun load(): SavedQueue? = saved

        override suspend fun saveQueue(queue: PlaybackQueue) {
            saved = SavedQueue(queue, Duration.ZERO)
        }

        override suspend fun savePosition(position: Duration) {
            positionWrites++
            saved = saved?.copy(position = position)
        }
    }
}
