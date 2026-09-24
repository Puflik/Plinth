package io.github.puflik.plinth.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Доля сыгранного — для тонкой полосы мини-плеера и полосы перемотки. */
class PlayerUiStateTest {
    @Test
    fun `progress is the played share of the track`() {
        assertThat(state(position = 90.seconds, duration = 4.minutes).progressFraction).isEqualTo(0.375f)
    }

    @Test
    fun `unknown length gives no progress`() {
        assertThat(state(position = 90.seconds, duration = null).progressFraction).isEqualTo(0f)
        assertThat(state(position = 90.seconds, duration = Duration.ZERO).progressFraction).isEqualTo(0f)
    }

    @Test
    fun `position past the end stays at the end`() {
        assertThat(state(position = 5.minutes, duration = 4.minutes).progressFraction).isEqualTo(1f)
    }

    private fun state(
        position: Duration,
        duration: Duration?,
    ) = PlayerUiState.EMPTY.copy(position = position, duration = duration)
}
