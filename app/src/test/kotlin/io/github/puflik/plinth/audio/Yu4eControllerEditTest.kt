package io.github.puflik.plinth.audio

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.history.ListeningRecorder
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueAction
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
 * Правка очереди вокруг играющего трека (Ю4е): убрать или унести в ручной блок трек контекста, что стоит в
 * контексте ниже играющего, — сохранённая секунда и история прослушиваний этого не замечают. При shuffle такие
 * треки бывают впереди по порядку обхода, и без этого каждая вторая правка стирала бы секунду и писала в историю
 * ложный «пропуск» и второе прослушивание того же трека.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu4eControllerEditTest {
    private val engine = FakeAudioEngine()
    private val store = MemoryStore()
    private val album = QueueContext.Album("A Night at the Opera", "Queen")
    private val tracks = (0 until 5).map { item("opera-$it") }
    private val manual = item("manual-0")

    /** Shuffle: играет opera-3 (индекс 3); впереди opera-0, opera-4, opera-1, opera-2. */
    private val shuffled =
        PlaybackQueue(context = album, contextItems = tracks, order = listOf(3, 0, 4, 1, 2), shuffle = true)

    @Test
    fun `removing a lower track on pause keeps the saved second`() =
        runTest(UnconfinedTestDispatcher()) {
            store.saved = SavedQueue(shuffled, 150.seconds)
            val controller = PlaybackController(engine, backgroundScope)
            QueueKeeper(controller, store, backgroundScope, clock()).start()

            controller.removeUpcoming(0, tracks[0])

            val queue = controller.queue.value
            assertThat(queue.contextItems).containsExactlyElementsIn(tracks - tracks[0]).inOrder()
            assertThat(queue.current).isEqualTo(tracks[3])
            assertThat(store.saved?.queue).isEqualTo(queue)
            assertThat(store.saved?.position).isEqualTo(150.seconds)
        }

    @Test
    fun `moving a lower track into the manual block on pause keeps the saved second`() =
        runTest(UnconfinedTestDispatcher()) {
            store.saved = SavedQueue(shuffled, 150.seconds)
            val controller = PlaybackController(engine, backgroundScope)
            QueueKeeper(controller, store, backgroundScope, clock()).start()
            // Впереди: manual-0, opera-0, opera-4, opera-1, opera-2.
            controller.perform(QueueAction.ADD_TO_QUEUE, manual)

            controller.moveUpcoming(from = 1, to = 0, item = tracks[0])

            val queue = controller.queue.value
            assertThat(queue.upNext).containsExactly(tracks[0], manual).inOrder()
            assertThat(queue.current).isEqualTo(tracks[3])
            assertThat(store.saved?.queue).isEqualTo(queue)
            assertThat(store.saved?.position).isEqualTo(150.seconds)
        }

    /** Прослушивание трека — одно: правка очереди посреди него не делает из него «пропуск» и второе прослушивание. */
    @Test
    fun `the history gets one listening across a removal of a lower track`() =
        runTest(UnconfinedTestDispatcher()) {
            val userData = FakeUserDataRepository()
            val controller = PlaybackController(engine, backgroundScope)
            ListeningRecorder(controller, userData, backgroundScope, clock()).start()
            controller.restore(shuffled, Duration.ZERO)
            controller.togglePlayPause()

            advanceTimeBy(40.seconds)
            controller.removeUpcoming(0, tracks[0])
            advanceTimeBy(20.seconds)
            controller.next()
            advanceTimeBy(3.minutes)
            engine.completeTrack()

            // Страж: настоящая смена трека по-прежнему закрывает прослушивание — пропуском на нажатии «дальше».
            val plays = userData.recorded
            assertThat(plays.map { it.track }).containsExactly(TrackId("opera-3"), TrackId("opera-4")).inOrder()
            assertThat(plays[0].listened).isEqualTo(1.minutes)
            assertThat(plays[0].skippedAt).isNotNull()
            assertThat(plays[1].listened).isEqualTo(3.minutes)
            assertThat(plays[1].skippedAt).isNull()
        }

    private fun item(id: String) = QueueItem(AudioSource.Online(id), id, "Queen")

    /** Часы теста — виртуальное время планировщика от нуля эпохи. */
    private fun TestScope.clock() =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)
        }

    /** Хранилище в памяти: новая очередь пишется с позицией в начале трека, как в [QueueStore]. */
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
