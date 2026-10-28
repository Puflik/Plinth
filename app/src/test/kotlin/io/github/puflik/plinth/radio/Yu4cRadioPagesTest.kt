package io.github.puflik.plinth.radio

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.OnlineVariant
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueAction
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Что из страницы радио ещё не стоит в очереди (Ю4в): тот же трек каталога и та же песня под другим видео — это повтор;
 * сравнивается со всем, что в очереди, — сыгранным, текущим, остатком и ручным блоком.
 */
class Yu4cRadioPagesTest {
    private val radio = QueueContext.Radio("youtube", "seed", "Seed")

    @Test
    fun `a track of the catalog that is in the queue is a repeat`() {
        val queue = queueOf(online("id-a", "Song A", "Band"))
        val page = listOf(track("a1", "Different Title", "Other"), track("b1", "Song B", "Band"))

        val fresh = freshIndices(page, known = listOf(TrackId("id-a"), null), queue = queue)

        assertThat(fresh).containsExactly(1)
    }

    @Test
    fun `a known track that is not in the queue is fresh`() {
        val queue = queueOf(online("id-a", "Song A", "Band"))
        val page = listOf(track("c1", "Song C", "Band"), track("d1", "Song D", "Band"))

        val fresh = freshIndices(page, known = listOf(TrackId("id-c"), null), queue = queue)

        assertThat(fresh).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `the same song under another video is a repeat whatever the case and the signs`() {
        val queue =
            queueOf(
                online("id-a", "Adventure of a Lifetime", "Coldplay"),
                online("id-b", "Группа крови", "Кино"),
            )
        val page =
            listOf(
                track("x1", "adventure OF a lifetime!", "COLDPLAY"),
                track("x2", "Группа  крови", "кино."),
                track("x3", "Adventure of a Lifetime (Live)", "Coldplay"),
            )

        val fresh = freshIndices(page, known = listOf(null, null, null), queue = queue)

        assertThat(fresh).containsExactly(2)
    }

    @Test
    fun `everything in the queue counts - played, current, upcoming and the manual block`() {
        val played = online("id-1", "Played", "Band")
        val current = online("id-2", "Current", "Band")
        val upcoming = online("id-3", "Upcoming", "Band")
        val manual = online("id-4", "Manual", "Band")
        val playingManual = online("id-5", "Playing Manual", "Band")
        val queue =
            PlaybackQueue.EMPTY
                .play(radio, listOf(played, current, upcoming), start = 1)
                .perform(QueueAction.PLAY_NEXT, playingManual)
                .perform(QueueAction.ADD_TO_QUEUE, manual)
                .next()!!
        check(queue.playingManual == playingManual && queue.upNext == listOf(manual))
        val page =
            listOf("Played", "Current", "Upcoming", "Manual", "Playing Manual", "Fresh").mapIndexed { index, title ->
                track("p$index", title, "Band")
            }

        val fresh = freshIndices(page, known = List(page.size) { null }, queue = queue)

        assertThat(fresh).containsExactly(5)
    }

    @Test
    fun `repeats inside the page count too, the first one stays`() {
        val queue = queueOf(online("id-a", "Song A", "Band"))
        val page =
            listOf(
                track("n1", "New", "Band"),
                track("n2", "new", "BAND"),
                track("n3", "Other", "Band"),
                track("n4", "Other again", "Band"),
            )

        val known = listOf(null, null, TrackId("id-o"), TrackId("id-o"))

        val fresh = freshIndices(page, known = known, queue = queue)

        assertThat(fresh).containsExactly(0, 2).inOrder()
    }

    @Test
    fun `without an artist only the catalog id counts`() {
        val queue = queueOf(online("id-a", "Intro", null), online("id-b", "Outro", "Band"))
        val page =
            listOf(
                track("i1", "Intro", null),
                track("i2", "Intro", "Band"),
                track("o1", "Outro", null),
            )

        val fresh = freshIndices(page, known = listOf(null, null, null), queue = queue)

        assertThat(fresh).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `a page that is all new stays whole and in order`() {
        val queue = queueOf(online("id-a", "Song A", "Band"))
        val page = (0 until 5).map { track("f$it", "Fresh $it", "Band") }

        val fresh = freshIndices(page, known = List(5) { null }, queue = queue)

        assertThat(fresh).containsExactly(0, 1, 2, 3, 4).inOrder()
    }

    @Test
    fun `a radio track is queued as the track itself`() {
        val item = track("t1", "Title", "Artist").toRadioItem(TrackId("id-t1"))

        assertThat(item.source).isEqualTo(AudioSource.Online("id-t1"))
        assertThat(item.title).isEqualTo("Title")
        assertThat(item.artist).isEqualTo("Artist")
        assertThat(item.album).isNull()
        assertThat(item.duration).isEqualTo(3.minutes)
        assertThat(item.albumOwner).isEqualTo("Artist")
    }

    private fun queueOf(vararg items: QueueItem) = PlaybackQueue.EMPTY.play(radio, items.toList(), start = 0)

    private fun online(
        id: String,
        title: String,
        artist: String?,
    ) = QueueItem(AudioSource.Online(id), title, artist)

    private fun track(
        video: String,
        title: String,
        artist: String?,
    ) = OnlineTrack(
        external = video,
        title = title,
        artist = artist,
        album = null,
        number = null,
        year = null,
        duration = 3.minutes,
        mbid = null,
        variants = listOf(OnlineVariant("$video/251", AudioFormat.OPUS)),
    )
}
