package io.github.puflik.plinth.queue

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Очередь переживает перезапуск (D1.5, D2.1): файл в памяти приложения. */
class FileQueueStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val store by lazy { FileQueueStore(temp.root.resolve("queue"), Dispatchers.Unconfined) }
    private val album = (0 until 4).map { item("opera-$it") }

    @Test
    fun `nothing saved reads as nothing`() =
        runTest {
            assertThat(store.load()).isNull()
        }

    @Test
    fun `whole queue and position come back`() =
        runTest {
            val queue =
                PlaybackQueue.EMPTY
                    .withRepeat(RepeatMode.ONE)
                    .play(QueueContext.Album("A Night at the Opera", "Queen"), album, start = 1)
                    .withShuffle(true, Random(5))
                    .perform(QueueAction.ADD_TO_QUEUE, item("manual-1", artist = null, duration = null))
                    .perform(QueueAction.ADD_TO_QUEUE, item("manual-2"))
                    .next()!!
            store.saveQueue(queue)
            store.savePosition(83.seconds)

            assertThat(store.load()).isEqualTo(SavedQueue(queue, 83.seconds))
        }

    @Test
    fun `every kind of context comes back`() =
        runTest {
            val contexts =
                listOf(
                    QueueContext.Tracks,
                    QueueContext.Album("Demos", null),
                    QueueContext.Folder("Music/Queen/"),
                    QueueContext.Search("queen opera"),
                    QueueContext.File,
                )
            for (context in contexts) {
                val queue = PlaybackQueue.EMPTY.play(context, album, start = 0)
                store.saveQueue(queue)
                assertThat(store.load()?.queue).isEqualTo(queue)
            }
        }

    @Test
    fun `new queue starts from the beginning`() =
        runTest {
            store.saveQueue(PlaybackQueue.EMPTY.play(QueueContext.Tracks, album, start = 0))
            store.savePosition(40.seconds)

            store.saveQueue(PlaybackQueue.EMPTY.play(QueueContext.Tracks, album, start = 2))

            assertThat(store.load()?.position).isEqualTo(Duration.ZERO)
        }

    @Test
    fun `stream headers are not written to disk`() =
        runTest {
            val stream =
                QueueItem(AudioSource.Remote("https://example.test/a.flac", mapOf("Authorization" to "secret")), "a")
            store.saveQueue(PlaybackQueue.EMPTY.play(QueueContext.Search("a"), listOf(stream), start = 0))

            assertThat(
                store
                    .load()
                    ?.queue
                    ?.current
                    ?.source,
            ).isEqualTo(AudioSource.Remote("https://example.test/a.flac"))
            assertThat(
                temp.root
                    .walkTopDown()
                    .filter { it.isFile }
                    .any { "secret" in it.readText(Charsets.ISO_8859_1) },
            ).isFalse()
        }

    @Test
    fun `damaged or foreign file reads as nothing`() =
        runTest {
            store.saveQueue(PlaybackQueue.EMPTY.play(QueueContext.Tracks, album, start = 0))
            temp.root
                .resolve("queue")
                .walkTopDown()
                .filter { it.isFile }
                .forEach { it.writeBytes(byteArrayOf(1, 2, 3)) }

            assertThat(store.load()).isNull()
        }

    private fun item(
        name: String,
        artist: String? = "Queen",
        duration: Duration? = 3.minutes,
    ) = QueueItem(
        source = AudioSource.LocalFile("content://media/external/audio/media/$name"),
        title = name,
        artist = artist,
        album = "A Night at the Opera",
        duration = duration,
    )
}
