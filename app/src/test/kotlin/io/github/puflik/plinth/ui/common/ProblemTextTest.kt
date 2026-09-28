package io.github.puflik.plinth.ui.common

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.R
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.core.FailedTrack
import io.github.puflik.plinth.core.TrackProblem
import org.junit.Test

/** Почему трек не сыграл — словами (G3, E3). */
class ProblemTextTest {
    @Test
    fun `a missing file is an unavailable file`() {
        val track = FailedTrack("Song", "/storage/emulated/0/Music/song.mp3", TrackProblem.UNAVAILABLE)

        assertThat(problemText(track)).isEqualTo(R.string.error_problem_unavailable)
    }

    /** У сетевого трека файла нет: источники выключены или трек пропал у провайдера. */
    @Test
    fun `an online track with nothing to play is not a missing file`() {
        val track = FailedTrack("Opening", AudioSource.Online("0192f7c4").key, TrackProblem.UNAVAILABLE)

        assertThat(problemText(track)).isEqualTo(R.string.error_problem_online_unavailable)
        assertThat(problemText(track.copy(problem = TrackProblem.NETWORK))).isEqualTo(R.string.error_problem_network)
    }
}
