package io.github.puflik.plinth.history

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackProgress
import io.github.puflik.plinth.queue.QueueItem
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Сколько сыграл трек (D4a): время звука, а не позиция. */
class ListenTrackerTest {
    private var clock = Instant.fromEpochMilliseconds(1_790_000_000_000)
    private val tracker = ListenTracker { clock }
    private val song = item("song")
    private val other = item("other")

    private fun pass(time: Duration) {
        clock += time
    }

    @Test
    fun `a track played to the end is a full listen`() {
        val startedAt = clock
        tracker.current(1, song)
        tracker.playing(true)
        pass(3.minutes)

        val listen = tracker.ended()

        assertThat(listen).isEqualTo(Listen(song, startedAt, 3.minutes, skippedAt = null, length = null))
    }

    @Test
    fun `a pause is not counted and the start is when the sound went`() {
        tracker.current(1, song)
        pass(10.seconds)
        val startedAt = clock
        tracker.playing(true)
        pass(30.seconds)
        tracker.playing(false)
        pass(5.minutes)
        tracker.playing(true)
        pass(20.seconds)

        val listen = tracker.ended()

        assertThat(listen?.startedAt).isEqualTo(startedAt)
        assertThat(listen?.listened).isEqualTo(50.seconds)
    }

    /** Перемотка вперёд прослушиванием не становится: считается время звука. */
    @Test
    fun `seeking forward does not add listening`() {
        tracker.current(1, song)
        tracker.playing(true)
        pass(10.seconds)
        tracker.progress(PlaybackProgress(3.minutes, 4.minutes))

        val listen = tracker.current(2, other)

        assertThat(listen?.listened).isEqualTo(10.seconds)
    }

    @Test
    fun `switching tracks closes the listen as skipped at the last position`() {
        tracker.current(1, song)
        tracker.playing(true)
        pass(40.seconds)
        tracker.progress(PlaybackProgress(40.seconds, 4.minutes))

        val listen = tracker.current(2, other)

        assertThat(listen).isEqualTo(Listen(song, listen!!.startedAt, 40.seconds, 40.seconds, 4.minutes))
        pass(1.minutes)
        assertThat(tracker.ended()?.item).isEqualTo(other)
    }

    /** Пропуск в последние секунды — тот же конец трека: событие конца и смена трека приходят в любом порядке. */
    @Test
    fun `a switch at the very end is not a skip`() {
        tracker.current(1, song)
        tracker.playing(true)
        pass(4.minutes)
        tracker.progress(PlaybackProgress(4.minutes - 1.seconds, 4.minutes))

        assertThat(tracker.current(2, other)?.skippedAt).isNull()
        assertThat(tracker.ended()).isNull()
    }

    @Test
    fun `nothing played is no listen`() {
        tracker.current(1, song)
        pass(1.minutes)

        assertThat(tracker.current(2, other)).isNull()
        assertThat(tracker.ended()).isNull()
    }

    /** Та же очередь с другим порядком обхода (shuffle) — тот же трек: прослушивание не рвётся. */
    @Test
    fun `the same place keeps the listen open`() {
        tracker.current(1, song)
        tracker.playing(true)
        pass(1.minutes)

        assertThat(tracker.current(1, song)).isNull()
        pass(1.minutes)
        assertThat(tracker.ended()?.listened).isEqualTo(2.minutes)
    }

    /** Повтор одного трека: каждый круг — своё прослушивание, даже если звук не прерывался. */
    @Test
    fun `a repeated track is a new listen each time`() {
        tracker.current(1, song)
        tracker.playing(true)
        pass(3.minutes)
        val first = tracker.ended()
        tracker.progress(PlaybackProgress(1.seconds, 3.minutes))
        pass(2.minutes)

        val second = tracker.ended()

        assertThat(first?.listened).isEqualTo(3.minutes)
        assertThat(second?.listened).isEqualTo(2.minutes)
    }

    @Test
    fun `less than a second is no listen`() {
        tracker.current(1, song)
        tracker.playing(true)
        pass(Duration.parse("0.5s"))

        assertThat(tracker.ended()).isNull()
    }

    private fun item(name: String) = QueueItem(AudioSource.LocalFile("/storage/emulated/0/Music/$name.mp3"), name)
}
