package io.github.puflik.plinth.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Время на экранах: длительность трека в списках, позиция в плеере. */
class TimeFormatTest {
    @Test
    fun `minutes and seconds with a leading zero`() {
        assertThat(formatTime(0.seconds)).isEqualTo("0:00")
        assertThat(formatTime(3.minutes + 7.seconds)).isEqualTo("3:07")
    }

    @Test
    fun `long tracks keep counting minutes`() {
        assertThat(formatTime(61.minutes + 5.seconds)).isEqualTo("61:05")
    }

    @Test
    fun `fractions of a second are dropped`() {
        assertThat(formatTime(59_999.milliseconds)).isEqualTo("0:59")
    }
}
