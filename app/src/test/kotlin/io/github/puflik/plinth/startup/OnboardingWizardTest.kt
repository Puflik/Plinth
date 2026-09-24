package io.github.puflik.plinth.startup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Мастер первого запуска B+ (F1, план 12.4): пройти, пропустить шаг, пропустить всё. */
class OnboardingWizardTest {
    @Test
    fun `v0_1 wizard is the permission then the folders, only the permission is required`() {
        assertThat(OnboardingStep.entries).containsExactly(OnboardingStep.PERMISSION, OnboardingStep.FOLDERS).inOrder()
        assertThat(OnboardingStep.PERMISSION.skippable).isFalse()
        assertThat(OnboardingStep.FOLDERS.skippable).isTrue()
    }

    @Test
    fun `wizard starts on the first step with nothing skipped`() {
        val wizard = OnboardingWizard()

        assertThat(wizard.step).isEqualTo(OnboardingStep.PERMISSION)
        assertThat(wizard.skipped).isEmpty()
        assertThat(wizard.finished).isFalse()
    }

    @Test
    fun `done steps lead to the next and past the last one the wizard is finished`() {
        val folders = OnboardingWizard().next()
        assertThat(folders.step).isEqualTo(OnboardingStep.FOLDERS)

        val finished = folders.next()
        assertThat(finished.finished).isTrue()
        assertThat(finished.skipped).isEmpty()
        assertThat(finished.next()).isEqualTo(finished)
    }

    @Test
    fun `skipped step is remembered`() {
        val wizard = OnboardingWizard().next().skip()

        assertThat(wizard.finished).isTrue()
        assertThat(wizard.skipped).containsExactly(OnboardingStep.FOLDERS)
    }

    @Test
    fun `required step cannot be skipped on its own`() {
        val wizard = OnboardingWizard()

        assertThat(wizard.skip()).isEqualTo(wizard)
    }

    @Test
    fun `skip all skips the current step and everything after it`() {
        val fromStart = OnboardingWizard().skipAll()
        assertThat(fromStart.finished).isTrue()
        assertThat(fromStart.skipped).containsExactly(OnboardingStep.PERMISSION, OnboardingStep.FOLDERS)

        val fromFolders = OnboardingWizard().next().skipAll()
        assertThat(fromFolders.skipped).containsExactly(OnboardingStep.FOLDERS)
    }

    @Test
    fun `finished wizard stays as it is`() {
        val finished = OnboardingWizard().next().skip()

        assertThat(finished.skip()).isEqualTo(finished)
        assertThat(finished.skipAll()).isEqualTo(finished)
    }
}
