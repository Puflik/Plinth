package io.github.puflik.plinth.queue

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import org.junit.Test
import kotlin.random.Random

/**
 * Очередь радио (Ю4в): страницы дописываются в конец контекста, а место играющего трека от этого не меняется —
 * иначе сохранённая секунда и история прослушиваний сочли бы дописанную очередь другим местом.
 */
class Yu4cQueueExtendTest {
    private val radio = QueueContext.Radio("youtube", "seed", "Seed")
    private val loaded = items("radio", 4)
    private val more = items("more", 3)

    /** Играет radio-1; впереди radio-2, radio-3. */
    private val queue = PlaybackQueue.EMPTY.play(radio, loaded, start = 1)

    @Test
    fun `extended appends to the end of the context and changes nothing else`() {
        val after = queue.extended(radio, base = 4, items = more)!!

        assertThat(after.contextItems).containsExactlyElementsIn(loaded + more).inOrder()
        assertThat(after.order).containsExactly(0, 1, 2, 3, 4, 5, 6).inOrder()
        assertThat(after.position).isEqualTo(1)
        assertThat(after.current).isEqualTo(loaded[1])
        assertThat(after.context).isEqualTo(radio)
        assertThat(after.upcoming).containsExactlyElementsIn(loaded.drop(2) + more).inOrder()
    }

    @Test
    fun `a shuffled order keeps its loaded part and the new tracks follow in page order`() {
        val shuffled = queue.withShuffle(true, Random(3))

        val after = shuffled.extended(radio, base = 4, items = more)!!

        assertThat(after.order.take(4)).isEqualTo(shuffled.order)
        assertThat(after.order.drop(4)).containsExactly(4, 5, 6).inOrder()
        assertThat(after.current).isEqualTo(shuffled.current)
        assertThat(after.position).isEqualTo(shuffled.position)
        assertThat(after.shuffle).isTrue()
    }

    @Test
    fun `the manual block and a manual track that plays stay as they are`() {
        val manual = item("manual-0")
        val queued = queue.perform(QueueAction.PLAY_NEXT, manual).perform(QueueAction.ADD_TO_QUEUE, item("manual-1"))
        val playingManual = queued.next()!!

        val after = playingManual.extended(radio, base = 4, items = more)!!

        assertThat(after.playingManual).isEqualTo(manual)
        assertThat(after.upNext).isEqualTo(playingManual.upNext)
        assertThat(after.position).isEqualTo(playingManual.position)
        assertThat(after.current).isEqualTo(manual)
        assertThat(after.playing).isEqualTo(playingManual.playing)
    }

    @Test
    fun `another context or a stale base is refused`() {
        assertThat(queue.extended(QueueContext.Radio("youtube", "other", "Seed"), base = 4, items = more)).isNull()
        assertThat(queue.extended(QueueContext.Radio("youtube", "seed", "Seed 2"), base = 4, items = more)).isNull()
        assertThat(queue.extended(QueueContext.Tracks, base = 4, items = more)).isNull()
        assertThat(queue.extended(radio, base = 3, items = more)).isNull()
        assertThat(queue.extended(radio, base = 5, items = more)).isNull()
        assertThat(PlaybackQueue.EMPTY.extended(radio, base = 0, items = more)).isNull()
    }

    @Test
    fun `playing onward walks over the appended tracks`() {
        val after = queue.extended(radio, base = 4, items = more)!!

        val walked = generateSequence(after) { it.next() }.mapNotNull { it.current }.toList()

        assertThat(walked).containsExactlyElementsIn(loaded.drop(1) + more).inOrder()
    }

    @Test
    fun `extending does not move the place of what plays`() {
        val after = queue.extended(radio, base = 4, items = more)!!

        assertThat(after.playing).isEqualTo(queue.playing)
        assertThat(after.next()!!.playing).isNotEqualTo(queue.playing)
    }

    @Test
    fun `the place follows the track and the context`() {
        assertThat(queue.next()!!.playing).isNotEqualTo(queue.playing)
        assertThat(queue.previous().playing).isNotEqualTo(queue.playing)

        // То же место списка, другой трек на нём — другое место.
        val elsewhere = PlaybackQueue.EMPTY.play(radio, items("else", 4), start = 1)
        assertThat(elsewhere.playing).isNotEqualTo(queue.playing)

        // Тот же трек на том же месте, но контекст другой — другое место.
        val album = PlaybackQueue.EMPTY.play(QueueContext.Album("Seed", null), loaded, start = 1)
        assertThat(album.playing).isNotEqualTo(queue.playing)

        // Тот же список с того же трека, как при втором касании, — то же место.
        assertThat(PlaybackQueue.EMPTY.play(radio, loaded, start = 1).playing).isEqualTo(queue.playing)
    }

    @Test
    fun `shuffle does not move the place`() {
        assertThat(queue.withShuffle(true, Random(1)).playing).isEqualTo(queue.playing)
        assertThat(queue.withShuffle(true, Random(1)).withShuffle(false).playing).isEqualTo(queue.playing)
    }

    /** Побочно: убранный из очереди трек контекста тоже не двигает место играющего. */
    @Test
    fun `dropping an upcoming track does not move the place either`() {
        val after = queue.remove(index = 1)

        assertThat(after.upcoming).containsExactly(loaded[2])
        assertThat(after.playing).isEqualTo(queue.playing)
    }

    private fun item(id: String) = QueueItem(AudioSource.Online(id), id, "Band")

    private fun items(
        prefix: String,
        count: Int,
    ) = (0 until count).map { item("$prefix-$it") }
}
