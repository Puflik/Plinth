package io.github.puflik.plinth.queue

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import kotlin.time.Duration.Companion.seconds

/**
 * Очередь радио в `queue.bin` (Ю4в): новый вид контекста в той же версии формата — при обновлении очередь версии 2
 * читается как читалась, а ключ страницы на диск не идёт.
 */
class Yu4cRadioQueueStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val directory get() = temp.root.resolve("queue")
    private val store by lazy { FileQueueStore(directory, Dispatchers.Unconfined) }
    private val radio = QueueContext.Radio("youtube", "dQw4w9WgXcQ/251", "Never Gonna Give You Up")

    @Test
    fun `a radio queue comes back whole`() =
        runTest {
            val queue =
                PlaybackQueue.EMPTY
                    .play(radio, items("radio", 5), start = 2)
                    .extended(radio, base = 5, items = items("more", 3))!!
                    .perform(QueueAction.ADD_TO_QUEUE, item("manual-0"))
            store.saveQueue(queue)
            store.savePosition(95.seconds)

            val loaded = store.load()

            assertThat(loaded).isEqualTo(SavedQueue(queue, 95.seconds))
            assertThat(loaded!!.queue.context).isEqualTo(radio)
        }

    @Test
    fun `the format version stays two`() =
        runTest {
            store.saveQueue(PlaybackQueue.EMPTY.play(radio, items("radio", 2), start = 0))

            val version = DataInputStream(directory.resolve("queue.bin").inputStream()).use { it.readInt() }

            assertThat(version).isEqualTo(2)
        }

    @Test
    fun `a file cut inside the radio context reads as nothing saved`() =
        runTest {
            store.saveQueue(PlaybackQueue.EMPTY.play(radio, items("radio", 2), start = 0))
            val file = directory.resolve("queue.bin")
            // Версия (4 байта) и метка вида «radio» (2 + 5 байт), дальше — только три байта провайдера.
            file.writeBytes(file.readBytes().copyOf(4 + 2 + 5 + 3))

            assertThat(store.load()).isNull()
        }

    @Test
    fun `a very long title is cut and does not stop the save`() =
        runTest {
            val long = QueueContext.Radio("youtube", "seed", "я".repeat(70_000))
            val queue = PlaybackQueue.EMPTY.play(long, items("radio", 2), start = 0)

            store.saveQueue(queue)

            val context = store.load()!!.queue.context as QueueContext.Radio
            // «я» — два байта в модифицированном UTF-8: в предел 65 535 байт помещается 32 767.
            assertThat(context.title).isEqualTo("я".repeat(32_767))
            assertThat(context.seed).isEqualTo("seed")
        }

    private fun item(id: String) = QueueItem(AudioSource.Online(id), id, "Band", duration = null)

    private fun items(
        prefix: String,
        count: Int,
    ) = (0 until count).map { item("$prefix-$it") }
}
