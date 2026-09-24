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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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

    private fun TestScope.keeper(): Pair<PlaybackController, QueueKeeper> {
        val controller = PlaybackController(engine, backgroundScope)
        return controller to QueueKeeper(controller, store, backgroundScope)
    }

    /** Хранилище в памяти; считает записи позиции. */
    private class MemoryQueueStore : QueueStore {
        var saved: SavedQueue? = null
        var positionWrites = 0

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
