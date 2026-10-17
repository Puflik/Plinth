package io.github.puflik.plinth.queue

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Ревью v0.2, №19 (Р1.11в): порча одного файла позиции не отбрасывает сохранённую очередь.
 * Обесточили, когда переименование уже на диске, а данные нет, — файл пуст или короче 8 байт.
 */
class R111cPositionFileTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val directory by lazy { temp.root.resolve("queue") }
    private val store by lazy { FileQueueStore(directory, Dispatchers.Unconfined) }
    private val queue =
        PlaybackQueue.EMPTY.play(QueueContext.Album("Demos", "Queen"), (0 until 4).map(::track), start = 1)
    private val playedAt = Instant.fromEpochMilliseconds(1_800_000_000_123)

    private fun track(number: Int) =
        QueueItem(AudioSource.LocalFile("/storage/emulated/0/Music/$number.mp3"), "track-$number", duration = 3.minutes)

    private suspend fun saveEverything() {
        store.saveQueue(queue)
        store.savePosition(83.seconds)
        store.savePlayedAt(playedAt)
    }

    private val positionFile: File get() = directory.resolve("position.bin")

    @Test
    fun `an empty position file keeps the queue and starts the track over`() =
        runTest {
            saveEverything()
            positionFile.writeBytes(ByteArray(0))

            assertThat(store.load()).isEqualTo(SavedQueue(queue, Duration.ZERO, playedAt))
        }

    @Test
    fun `a position file cut short keeps the queue and starts the track over`() =
        runTest {
            saveEverything()
            positionFile.writeBytes(byteArrayOf(0, 0, 0))

            assertThat(store.load()).isEqualTo(SavedQueue(queue, Duration.ZERO, playedAt))
        }

    @Test
    fun `a position that cannot be one starts the track over`() =
        runTest {
            saveEverything()
            DataOutputStream(positionFile.outputStream()).use { it.writeLong(-5_000) }

            assertThat(store.load()).isEqualTo(SavedQueue(queue, Duration.ZERO, playedAt))
        }

    @Test
    fun `a missing position file starts the track over`() =
        runTest {
            saveEverything()
            positionFile.delete()

            assertThat(store.load()).isEqualTo(SavedQueue(queue, Duration.ZERO, playedAt))
        }

    /** Охранный: хорошая позиция возвращается секунда в секунду. */
    @Test
    fun `a good position comes back as it was`() =
        runTest {
            saveEverything()

            assertThat(store.load()).isEqualTo(SavedQueue(queue, 83.seconds, playedAt))
        }

    /** Охранный: порча самой очереди — как раньше, «ничего не сохранено», какой бы ни была позиция. */
    @Test
    fun `a damaged queue file still reads as nothing`() =
        runTest {
            saveEverything()
            directory.resolve("queue.bin").writeBytes(byteArrayOf(1, 2, 3))

            assertThat(store.load()).isNull()
        }
}
