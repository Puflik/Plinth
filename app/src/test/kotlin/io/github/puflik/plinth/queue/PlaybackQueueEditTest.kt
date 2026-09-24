package io.github.puflik.plinth.queue

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

/**
 * Правка очереди из панели «Очередь» (E4): убрать и переставить то, что
 * сыграет дальше. Индексы — места в [PlaybackQueue.upcoming], как их видит
 * пользователь: сначала ручной блок, потом остаток контекста.
 */
class PlaybackQueueEditTest {
    private val album = QueueContext.Album("A Night at the Opera", "Queen")
    private val tracks = items("opera", 5)
    private val manual = items("manual", 2)

    /** Играет opera-1, дальше manual-0, manual-1, opera-2, opera-3, opera-4. */
    private val queue =
        manual.fold(PlaybackQueue.EMPTY.play(album, tracks, start = 1)) { queue, item ->
            queue.perform(QueueAction.ADD_TO_QUEUE, item)
        }
    private val shuffled = queue.withShuffle(true, Random(7))

    @Test
    fun `removing any upcoming track leaves the rest in order`() {
        for (before in listOf(queue, shuffled)) {
            for (index in before.upcoming.indices) {
                val after = before.remove(index)

                val expected = before.upcoming.toMutableList().apply { removeAt(index) }
                assertThat(after.upcoming).containsExactlyElementsIn(expected).inOrder()
                assertThat(after.current).isEqualTo(before.current)
                assertThat(after.played).isEqualTo(before.played)
            }
        }
    }

    @Test
    fun `moving any upcoming track puts it exactly there`() {
        for (before in listOf(queue, shuffled)) {
            for (from in before.upcoming.indices) {
                for (to in before.upcoming.indices) {
                    val after = before.move(from, to)

                    val expected = before.upcoming.toMutableList().apply { add(to, removeAt(from)) }
                    assertThat(after.upcoming).containsExactlyElementsIn(expected).inOrder()
                    assertThat(after.current).isEqualTo(before.current)
                    assertThat(after.played).isEqualTo(before.played)
                }
            }
        }
    }

    @Test
    fun `removed track is skipped`() {
        val edited = queue.remove(index = 3)

        assertThat(edited.playThrough()).containsExactly(manual[0], manual[1], tracks[2], tracks[4]).inOrder()
    }

    /** Ручной блок переживает смену контекста — значит, трек стал ручным. */
    @Test
    fun `context track moved into the manual block becomes manual`() {
        val edited = queue.move(from = 3, to = 0)

        assertThat(edited.upNext).containsExactly(tracks[3], manual[0], manual[1]).inOrder()
        val jazz = edited.play(QueueContext.Album("Jazz", "Queen"), items("jazz", 1), start = 0)
        assertThat(jazz.upcoming).containsExactly(tracks[3], manual[0], manual[1]).inOrder()
    }

    @Test
    fun `manual track moved past the manual block joins the context`() {
        val edited = queue.move(from = 0, to = 3)

        assertThat(edited.upNext).containsExactly(manual[1])
        assertThat(edited.contextItems).contains(manual[0])
        assertThat(edited.playThrough())
            .containsExactly(manual[1], tracks[2], tracks[3], manual[0], tracks[4])
            .inOrder()
    }

    /** Перестановка в контексте — порядок обхода: без shuffle альбом возвращается к своему порядку. */
    @Test
    fun `reordered context goes back to album order when shuffle is turned off`() {
        val edited = queue.move(from = 4, to = 2)

        val unshuffled = edited.withShuffle(true).withShuffle(false)

        assertThat(edited.contextItems).isEqualTo(tracks)
        assertThat(unshuffled.upcoming.takeLast(3)).containsExactly(tracks[2], tracks[3], tracks[4]).inOrder()
    }

    @Test
    fun `edits outside the upcoming tracks are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { queue.remove(queue.upcoming.size) }
        assertThrows(IllegalArgumentException::class.java) { queue.move(0, queue.upcoming.size) }
        assertThrows(IllegalArgumentException::class.java) { queue.move(-1, 0) }
    }

    /** Что уже сыграло в контексте, вместе с текущим треком контекста. */
    private val PlaybackQueue.played: List<QueueItem>
        get() = order.take(position + 1).map(contextItems::get)

    /** Все треки, что сыграют один за другим от текущего. */
    private fun PlaybackQueue.playThrough(): List<QueueItem> =
        generateSequence(next()) { it.next() }.mapNotNull(PlaybackQueue::current).toList()

    private fun items(
        prefix: String,
        count: Int,
    ) = (0 until count).map { QueueItem(AudioSource.LocalFile("content://plinth.test/$prefix-$it"), "$prefix-$it") }
}
