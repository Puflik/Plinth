package io.github.puflik.plinth.startup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Пропущенные шаги мастера возвращаются к месту (F2, план 12.4). */
class DeferredPromptsTest {
    @Test
    fun `skipped folders come back when the library is empty`() {
        val due = DeferredPrompts.due(setOf(OnboardingStep.FOLDERS), PromptTrigger.EMPTY_LIBRARY)

        assertThat(due).isEqualTo(OnboardingStep.FOLDERS)
    }

    @Test
    fun `nothing comes back when nothing was skipped`() {
        assertThat(DeferredPrompts.due(emptySet(), PromptTrigger.EMPTY_LIBRARY)).isNull()
    }

    @Test
    fun `skipped permission is not offered again - the library asks for it itself`() {
        assertThat(DeferredPrompts.due(setOf(OnboardingStep.PERMISSION), PromptTrigger.EMPTY_LIBRARY)).isNull()
    }
}
