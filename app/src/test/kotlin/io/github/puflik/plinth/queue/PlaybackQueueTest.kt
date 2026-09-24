package io.github.puflik.plinth.queue

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import org.junit.Test
import kotlin.random.Random

/**
 * Модель очереди (D1): контекст плюс ручные добавления, которые переживают
 * смену контекста; shuffle — порядок обхода, а не перестановка очереди.
 */
class PlaybackQueueTest {
    private val album = QueueContext.Album("A Night at the Opera", "Queen")
    private val albumTracks = items("opera", 5)
    private val otherAlbum = QueueContext.Album("Jazz", "Queen")
    private val jazzTracks = items("jazz", 3)
    private val manual = item("manual-1")
    private val manual2 = item("manual-2")

    @Test
    fun `empty queue has nothing to play`() {
        assertThat(PlaybackQueue.EMPTY.current).isNull()
        assertThat(PlaybackQueue.EMPTY.upcoming).isEmpty()
        assertThat(PlaybackQueue.EMPTY.next()).isNull()
    }

    @Test
    fun `context plays from the tapped track and then in order`() {
        val queue = PlaybackQueue.EMPTY.play(album, albumTracks, start = 2)

        assertThat(queue.current).isEqualTo(albumTracks[2])
        assertThat(queue.context).isEqualTo(album)
        assertThat(queue.upcoming).containsExactly(albumTracks[3], albumTracks[4]).inOrder()
    }

    @Test
    fun `context ends after its last track when repeat is off`() {
        val last = PlaybackQueue.EMPTY.play(album, albumTracks, start = 3).next(auto = true)

        assertThat(last?.current).isEqualTo(albumTracks[4])
        assertThat(last?.next(auto = true)).isNull()
        assertThat(last?.next()).isNull()
    }

    @Test
    fun `repeat all goes round the context`() {
        val last = PlaybackQueue.EMPTY.withRepeat(RepeatMode.ALL).play(album, albumTracks, start = 4)

        assertThat(last.next(auto = true)?.current).isEqualTo(albumTracks[0])
    }

    @Test
    fun `repeat one replays the track by itself but lets the user skip`() {
        val queue = PlaybackQueue.EMPTY.withRepeat(RepeatMode.ONE).play(album, albumTracks, start = 1)

        assertThat(queue.next(auto = true)?.current).isEqualTo(albumTracks[1])
        assertThat(queue.next()?.current).isEqualTo(albumTracks[2])
    }

    @Test
    fun `manual tracks play right after the current one, then the context goes on`() {
        val queue =
            PlaybackQueue.EMPTY
                .play(album, albumTracks, start = 0)
                .perform(QueueAction.ADD_TO_QUEUE, manual)
                .perform(QueueAction.ADD_TO_QUEUE, manual2)

        assertThat(queue.upcoming.take(3)).containsExactly(manual, manual2, albumTracks[1]).inOrder()
        val afterManual = queue.next()?.next()?.next()
        assertThat(afterManual?.current).isEqualTo(albumTracks[1])
    }

    @Test
    fun `play next goes before tracks added earlier`() {
        val queue =
            PlaybackQueue.EMPTY
                .play(album, albumTracks, start = 0)
                .perform(QueueAction.ADD_TO_QUEUE, manual)
                .perform(QueueAction.PLAY_NEXT, manual2)

        assertThat(queue.upcoming.take(2)).containsExactly(manual2, manual).inOrder()
    }

    @Test
    fun `new context keeps the manual tracks`() {
        val queue =
            PlaybackQueue.EMPTY
                .play(album, albumTracks, start = 0)
                .perform(QueueAction.ADD_TO_QUEUE, manual)
                .play(otherAlbum, jazzTracks, start = 1)

        assertThat(queue.current).isEqualTo(jazzTracks[1])
        assertThat(queue.context).isEqualTo(otherAlbum)
        assertThat(queue.upcoming).containsExactly(manual, jazzTracks[2]).inOrder()
    }

    @Test
    fun `replacing the queue drops the manual tracks`() {
        val queue =
            PlaybackQueue.EMPTY
                .play(album, albumTracks, start = 0)
                .perform(QueueAction.ADD_TO_QUEUE, manual)
                .replace(otherAlbum, jazzTracks, start = 0)

        assertThat(queue.upcoming).containsExactly(jazzTracks[1], jazzTracks[2]).inOrder()
    }

    @Test
    fun `played manual track leaves the queue`() {
        val onManual =
            PlaybackQueue.EMPTY
                .withRepeat(RepeatMode.ALL)
                .play(album, albumTracks, start = 0)
                .perform(QueueAction.ADD_TO_QUEUE, manual)
                .next()

        assertThat(onManual?.current).isEqualTo(manual)
        assertThat(onManual?.upcoming).doesNotContain(manual)
        val backInContext = onManual?.next()
        assertThat(backInContext?.previous()?.current).isEqualTo(albumTracks[0])
    }

    @Test
    fun `manual tracks play even without a context`() {
        val queue = PlaybackQueue.EMPTY.perform(QueueAction.ADD_TO_QUEUE, manual)

        assertThat(queue.current).isNull()
        assertThat(queue.next()?.current).isEqualTo(manual)
        assertThat(queue.next()?.next()).isNull()
    }

    @Test
    fun `previous steps back and stays on the first track`() {
        val queue = PlaybackQueue.EMPTY.play(album, albumTracks, start = 1)

        val first = queue.previous()
        assertThat(first.current).isEqualTo(albumTracks[0])
        assertThat(first.previous().current).isEqualTo(albumTracks[0])
    }

    @Test
    fun `previous from a manual track returns to the context track before it`() {
        val onManual =
            PlaybackQueue.EMPTY
                .play(album, albumTracks, start = 2)
                .perform(QueueAction.PLAY_NEXT, manual)
                .next()

        assertThat(onManual?.previous()?.current).isEqualTo(albumTracks[2])
    }

    @Test
    fun `shuffle keeps the current track and visits every track once`() {
        val shuffled = PlaybackQueue.EMPTY.play(album, albumTracks, start = 2).withShuffle(true, Random(7))

        assertThat(shuffled.current).isEqualTo(albumTracks[2])
        assertThat(shuffled.upcoming).containsExactlyElementsIn(albumTracks - albumTracks[2])
        assertThat(shuffled.contextItems).containsExactlyElementsIn(albumTracks).inOrder()
    }

    @Test
    fun `turning shuffle off returns to the album order at the same track`() {
        val shuffled = PlaybackQueue.EMPTY.play(album, albumTracks, start = 0).withShuffle(true, Random(7))
        val moved = checkNotNull(shuffled.next()?.next())
        val playing = moved.current

        val unshuffled = moved.withShuffle(false)

        assertThat(unshuffled.current).isEqualTo(playing)
        val index = albumTracks.indexOf(playing)
        assertThat(unshuffled.upcoming).containsExactlyElementsIn(albumTracks.drop(index + 1)).inOrder()
    }

    @Test
    fun `new context under shuffle starts from the tapped track`() {
        val queue = PlaybackQueue.EMPTY.withShuffle(true, Random(3)).play(otherAlbum, jazzTracks, start = 2, Random(3))

        assertThat(queue.shuffle).isTrue()
        assertThat(queue.current).isEqualTo(jazzTracks[2])
        assertThat(queue.upcoming).containsExactly(jazzTracks[0], jazzTracks[1])
    }

    private fun items(
        prefix: String,
        count: Int,
    ) = (0 until count).map { item("$prefix-$it") }

    private fun item(name: String) =
        QueueItem(source = AudioSource.LocalFile("content://plinth.test/$name"), title = name)
}
