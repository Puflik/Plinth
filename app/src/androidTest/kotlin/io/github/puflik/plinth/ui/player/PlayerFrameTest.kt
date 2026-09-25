package io.github.puflik.plinth.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * Раскладка плеера (ревью №3): на любом экране подробности — название,
 * перемотка, кнопки — видны целиком, рамка обложки квадратная и их не
 * накрывает. Высоты — место под шапкой плеера на настоящих телефонах.
 */
class PlayerFrameTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun landscape_phone_shows_the_controls_whole() {
        val (screen, frame, details) = layOut(width = 800.dp, height = 280.dp)

        assertFits(screen, frame, details)
    }

    @Test
    fun small_portrait_phone_shows_the_controls_whole() {
        val (screen, frame, details) = layOut(width = 360.dp, height = 512.dp)

        assertFits(screen, frame, details)
    }

    @Test
    fun tall_phone_keeps_the_big_cover() {
        val (screen, frame, details) = layOut(width = 411.dp, height = 760.dp)

        assertFits(screen, frame, details)
        // Как до исправления: 0,8 ширины за вычетом полей.
        assertThat(frame.width.value).isWithin(1f).of((411 - 48) * 0.8f)
    }

    private fun layOut(
        width: Dp,
        height: Dp,
    ): Triple<DpRect, DpRect, DpRect> {
        compose.setContent {
            Box(Modifier.requiredSize(width, height).testTag(SCREEN)) {
                PlayerFrame(
                    frame = { size -> Box(size.testTag(FRAME)) },
                    dots = { Box(Modifier.size(DOTS)) },
                ) {
                    Box(Modifier.fillMaxWidth().height(DETAILS).testTag(CONTROLS))
                }
            }
        }
        return Triple(bounds(SCREEN), bounds(FRAME), bounds(CONTROLS))
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun assertFits(
        screen: DpRect,
        frame: DpRect,
        details: DpRect,
    ) {
        assertThat(details.bottom.value).isAtMost(screen.bottom.value + SLACK)
        assertThat(details.right.value).isAtMost(screen.right.value + SLACK)
        assertThat(details.height.value).isWithin(SLACK).of(DETAILS.value)
        assertThat(frame.bottom.value).isAtMost(screen.bottom.value + SLACK)
        assertThat(frame.width.value).isWithin(SLACK).of(frame.height.value)
        assertThat(frame.width.value).isGreaterThan(0f)
        val apart = frame.bottom.value <= details.top.value + SLACK || frame.right.value <= details.left.value + SLACK
        assertThat(apart).isTrue()
    }

    private companion object {
        const val SCREEN = "screen"
        const val FRAME = "frame"
        const val CONTROLS = "controls"
        const val SLACK = 1f
        val DOTS = 40.dp

        /** Название в две строки, исполнитель, перемотка со временем и ряд кнопок. */
        val DETAILS = 250.dp
    }
}
