package io.github.puflik.plinth.library.permission

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class PermissionStateTest {
    private data class Case(
        val granted: Boolean,
        val requested: Boolean,
        val showRationale: Boolean,
        val expected: PermissionState,
    )

    @Test
    fun `state follows grant, request result and rationale`() {
        val table =
            listOf(
                Case(granted = true, requested = false, showRationale = false, PermissionState.Granted),
                Case(granted = true, requested = true, showRationale = true, PermissionState.Granted),
                // Не спрашивали — или отказали навсегда в прошлый раз: узнать можно, только спросив.
                Case(granted = false, requested = false, showRationale = false, PermissionState.NotRequested),
                // Отказ в прошлый запуск: сначала объяснить, потом спрашивать.
                Case(granted = false, requested = false, showRationale = true, PermissionState.Denied),
                Case(granted = false, requested = true, showRationale = true, PermissionState.Denied),
                // Система больше не покажет диалог: второй отказ или «Больше не спрашивать».
                Case(granted = false, requested = true, showRationale = false, PermissionState.PermanentlyDenied),
            )

        for (case in table) {
            val state = PermissionState.of(case.granted, case.requested, case.showRationale)

            assertWithMessage(case.toString()).that(state).isEqualTo(case.expected)
        }
    }
}
