package io.github.puflik.plinth.audio

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.audio.engine.PlaybackState
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Фасад воспроизведения для UI (B2–B4) на `FakeAudioEngine`. */
class PlaybackControllerTest {
    private val engine = FakeAudioEngine()
    private val controller = PlaybackController(engine)
    private val track = AudioSource.LocalFile("content://plinth.test/track.flac")

    @Test
    fun `opened file starts playing at once`() {
        controller.open(track)

        assertThat(engine.preparedSources).containsExactly(track)
        assertThat(engine.lastParams?.autoPlay).isTrue()
        assertThat(controller.state.value).isEqualTo(PlaybackState.Playing)
    }

    @Test
    fun `toggle pauses playing track and resumes paused one`() {
        controller.open(track)

        controller.togglePlayPause()
        assertThat(controller.state.value).isEqualTo(PlaybackState.Paused)

        controller.togglePlayPause()
        assertThat(controller.state.value).isEqualTo(PlaybackState.Playing)
    }

    @Test
    fun `toggle after the end plays the track again`() {
        controller.open(track)
        engine.completeTrack()

        controller.togglePlayPause()

        assertThat(controller.state.value).isEqualTo(PlaybackState.Playing)
    }

    @Test
    fun `toggle without a track does nothing`() {
        controller.togglePlayPause()

        assertThat(controller.state.value).isEqualTo(PlaybackState.Idle)
    }

    @Test
    fun `seek moves within the open track`() {
        controller.open(track)

        controller.seekTo(30.seconds)

        assertThat(controller.progress.value.position).isEqualTo(30.seconds)
    }

    @Test
    fun `seek without a track is ignored instead of crashing the screen`() {
        controller.seekTo(30.seconds)

        assertThat(controller.progress.value.position).isEqualTo(0.seconds)
    }
}
