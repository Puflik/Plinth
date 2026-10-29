package io.github.puflik.plinth.queue

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.puflik.plinth.audio.engine.AudioSource
import org.junit.Test
import kotlin.random.Random

/**
 * Место играющего трека не двигается от правки остальной очереди (Ю4е). Убрать или унести в ручной блок трек
 * контекста, у которого индекс в контексте ниже, чем у играющего, — значит сдвинуть индекс играющего; тогда сохранённая
 * секунда и идущее прослушивание принимали бы тот же трек за другой. Впереди по порядку обхода такие треки бывают
 * при shuffle.
 */
class Yu4eQueuePlaceTest {
    private val album = QueueContext.Album("A Night at the Opera", "Queen")
    private val tracks = items("opera", 5)
    private val manual = items("manual", 2)

    /** Shuffle: играет opera-3 (индекс 3); впереди opera-0, opera-4, opera-1, opera-2 — индексы и ниже, и выше. */
    private val shuffled =
        PlaybackQueue(context = album, contextItems = tracks, order = listOf(3, 0, 4, 1, 2), shuffle = true)

    /** Сыграны opera-3 и opera-0, играет opera-4 (индекс 4): оба трека впереди стоят в контексте ниже. */
    private val midway = shuffled.copy(position = 2)

    /** Как [shuffled], а за играющим ещё и ручной блок: manual-0, manual-1. */
    private val withManual = manual.fold(shuffled) { queue, item -> queue.perform(QueueAction.ADD_TO_QUEUE, item) }

    private val queues =
        mapOf(
            "shuffled" to shuffled,
            "midway" to midway,
            "with a manual block" to withManual,
            "a manual track plays" to withManual.next()!!,
            "plain order" to PlaybackQueue.EMPTY.play(album, tracks, start = 1),
        )

    @Test
    fun `removing a track that stands lower in the context does not move the place`() {
        val after = shuffled.remove(index = 0)

        // Страж сценария: убран opera-0, и индекс играющего opera-3 в контексте вправду упал с 3 до 2.
        assertThat(shuffled.upcoming.first()).isEqualTo(tracks[0])
        assertThat(after.contextItems).containsExactlyElementsIn(tracks - tracks[0]).inOrder()
        assertThat(after.order[after.position]).isEqualTo(2)
        assertThat(after.current).isEqualTo(tracks[3])
        assertThat(after.playing).isEqualTo(shuffled.playing)
    }

    @Test
    fun `removing a track that stands higher in the context does not move the place either`() {
        val after = shuffled.remove(index = 1)

        assertThat(shuffled.upcoming[1]).isEqualTo(tracks[4])
        assertThat(after.order[after.position]).isEqualTo(3)
        assertThat(after.playing).isEqualTo(shuffled.playing)
    }

    @Test
    fun `a lower track moved into the manual block does not move the place`() {
        // Впереди: manual-0, manual-1, opera-0, opera-4, opera-1, opera-2.
        val after = withManual.move(from = 2, to = 0)

        assertThat(after.upNext).containsExactly(tracks[0], manual[0], manual[1]).inOrder()
        assertThat(after.order[after.position]).isEqualTo(2)
        assertThat(after.playing).isEqualTo(withManual.playing)
    }

    @Test
    fun `no removal moves the place of what plays`() {
        for ((name, before) in queues) {
            for (index in before.upcoming.indices) {
                assertWithMessage("$name: remove($index)")
                    .that(before.remove(index).playing)
                    .isEqualTo(before.playing)
            }
        }
    }

    @Test
    fun `no move moves the place of what plays`() {
        for ((name, before) in queues) {
            for (from in before.upcoming.indices) {
                for (to in before.upcoming.indices) {
                    assertWithMessage("$name: move($from, $to)")
                        .that(before.move(from, to).playing)
                        .isEqualTo(before.playing)
                }
            }
        }
    }

    @Test
    fun `removing another track does not move the place of a repeated one`() {
        // Играет второй opera-0 (индекс 2); впереди opera-1 (индекс 0) и первый opera-0 (индекс 1).
        val repeated =
            PlaybackQueue(
                context = album,
                contextItems = listOf(tracks[1], tracks[0], tracks[0]),
                order = listOf(2, 0, 1),
                shuffle = true,
            )

        val after = repeated.remove(index = 0)

        assertThat(after.contextItems).containsExactly(tracks[0], tracks[0]).inOrder()
        assertThat(after.order[after.position]).isEqualTo(1)
        assertThat(after.playing).isEqualTo(repeated.playing)
    }

    /** В плейлисте один трек может стоять дважды: это два места, и ключ без номера вхождения их слил бы. */
    @Test
    fun `a track that stands twice in the context is two places`() {
        val twice = listOf(tracks[0], tracks[1], tracks[0], tracks[1])
        val atFirst = PlaybackQueue.EMPTY.play(album, twice, start = 0)
        val atThird = PlaybackQueue.EMPTY.play(album, twice, start = 2)

        assertThat(atThird.playing).isNotEqualTo(atFirst.playing)
        assertThat(atFirst.next()!!.next()!!.playing).isEqualTo(atThird.playing)
    }

    @Test
    fun `the same track twice in a row is a new place`() {
        val back = PlaybackQueue.EMPTY.play(album, listOf(tracks[0], tracks[0], tracks[1]), start = 0)

        assertThat(back.next()!!.playing).isNotEqualTo(back.playing)
        assertThat(back.next()!!.previous().playing).isEqualTo(back.playing)
    }

    @Test
    fun `shuffle does not move the place of a repeated track`() {
        val twice = listOf(tracks[0], tracks[1], tracks[0], tracks[1])
        val atThird = PlaybackQueue.EMPTY.play(album, twice, start = 2)

        assertThat(atThird.withShuffle(true, Random(2)).playing).isEqualTo(atThird.playing)
        assertThat(atThird.withShuffle(true, Random(2)).withShuffle(false).playing).isEqualTo(atThird.playing)
    }

    /** Дописанное в конец радио (Ю4в) место не двигает, даже если дописали ещё одну копию играющего трека. */
    @Test
    fun `extending with another copy of the playing track does not move the place`() {
        val radio = QueueContext.Radio("youtube", "seed", "Seed")
        val queue = PlaybackQueue.EMPTY.play(radio, listOf(tracks[0], tracks[1]), start = 0)

        val after = queue.extended(radio, base = 2, items = listOf(tracks[0]))!!

        assertThat(after.contextItems).containsExactly(tracks[0], tracks[1], tracks[0]).inOrder()
        assertThat(after.playing).isEqualTo(queue.playing)
    }

    private fun items(
        prefix: String,
        count: Int,
    ) = (0 until count).map { QueueItem(AudioSource.LocalFile("content://plinth.test/$prefix-$it"), "$prefix-$it") }
}
