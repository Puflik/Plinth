package io.github.puflik.plinth.history

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Прослушивания треков фонотеки уходят в историю ядра (D4a). */
@OptIn(ExperimentalCoroutinesApi::class)
class ListeningRecorderTest {
    private val engine = FakeAudioEngine()
    private val userData = FakeUserDataRepository()

    @Test
    fun `library tracks go to the history with their listening time`() =
        runTest(UnconfinedTestDispatcher()) {
            val (song, other) = userData.add(SONG, OTHER)
            val controller = recording()

            controller.play(QueueContext.Tracks, listOf(item(SONG), item(OTHER)), start = 0)
            advanceTimeBy(40.seconds)
            controller.next()
            advanceTimeBy(3.minutes)
            engine.completeTrack()

            val plays = userData.recorded
            assertThat(plays.map { it.track to it.listened })
                .containsExactly(song to 40.seconds, other to 3.minutes)
                .inOrder()
            assertThat(plays[0].startedAt).isEqualTo(Instant.fromEpochMilliseconds(0))
            assertThat(plays[0].skippedAt).isNotNull()
            assertThat(plays[1].skippedAt).isNull()
            assertThat(plays[1].previousTrack).isEqualTo(song)
            assertThat(plays[1].trackLength).isEqualTo(engine.trackDuration)
        }

    /** Сетевой трек (E3) — трек фонотеки без файла: в историю он идёт своим ID, не путём. */
    @Test
    fun `an online track goes to the history by its id`() =
        runTest(UnconfinedTestDispatcher()) {
            val (song) = userData.add(SONG)
            val online = TrackId("0192f7c4-0000-7000-8000-00000000000a")
            val controller = recording()

            controller.play(
                QueueContext.Liked,
                listOf(QueueItem(AudioSource.Online(online.value), "Opening"), item(SONG)),
                start = 0,
            )
            advanceTimeBy(2.minutes)
            controller.next()
            advanceTimeBy(1.minutes)
            engine.completeTrack()

            assertThat(userData.recorded.map { it.track }).containsExactly(online, song).inOrder()
            assertThat(userData.recorded[1].previousTrack).isEqualTo(online)
        }

    /** Файл, открытый через SAF, не из фонотеки: у него нет трека и истории. */
    @Test
    fun `a file outside the library is not recorded`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = recording()

            controller.play(QueueContext.Tracks, listOf(item("content://picked/song.flac")), start = 0)
            advanceTimeBy(3.minutes)
            engine.completeTrack()

            assertThat(userData.recorded).isEmpty()
        }

    private fun TestScope.recording(): PlaybackController {
        val controller = PlaybackController(engine, backgroundScope)
        // Часы теста — виртуальное время планировщика от нуля эпохи.
        val clock =
            object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)
            }
        ListeningRecorder(controller, userData, backgroundScope, clock).start()
        return controller
    }

    private fun item(uri: String) = QueueItem(AudioSource.LocalFile(uri), uri.substringAfterLast('/'))

    private companion object {
        const val SONG = "/storage/emulated/0/Music/song.mp3"
        const val OTHER = "/storage/emulated/0/Music/other.mp3"
    }
}
