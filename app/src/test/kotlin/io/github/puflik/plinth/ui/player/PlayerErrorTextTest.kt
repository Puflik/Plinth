package io.github.puflik.plinth.ui.player

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.R
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackError
import io.github.puflik.plinth.audio.engine.PlaybackProgress
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import org.junit.Test

/** Ошибка под названием трека (G3, E3): у сетевого трека файла нет — и слова другие. */
class PlayerErrorTextTest {
    @Test
    fun `a missing file says the file is gone`() {
        val state = stateOf(QueueItem(AudioSource.LocalFile("/storage/emulated/0/Music/a.mp3"), "a"))

        assertThat(state.online).isFalse()
        assertThat(playerErrorText(PlaybackError.SourceUnavailable(), state.online))
            .isEqualTo(R.string.player_error_unavailable)
    }

    @Test
    fun `an online track with nothing to play says it is not available online`() {
        val state = stateOf(QueueItem(AudioSource.Online("0192f7c4"), "Opening"))

        assertThat(state.online).isTrue()
        assertThat(playerErrorText(PlaybackError.SourceUnavailable(), state.online))
            .isEqualTo(R.string.player_error_online_unavailable)
        assertThat(playerErrorText(PlaybackError.Network(), state.online)).isEqualTo(R.string.player_error_network)
    }

    private fun stateOf(item: QueueItem) =
        PlayerUiState.from(
            PlaybackState.Error(PlaybackError.SourceUnavailable()),
            PlaybackProgress.NONE,
            PlaybackQueue.EMPTY.play(QueueContext.Liked, listOf(item), start = 0),
        )
}
