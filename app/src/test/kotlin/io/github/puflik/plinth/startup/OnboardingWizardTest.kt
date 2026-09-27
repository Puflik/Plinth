package io.github.puflik.plinth.startup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Мастер первого запуска B+ (F1, план 12.4): пройти, пропустить шаг, пропустить всё. */
class OnboardingWizardTest {
    @Test
    fun `v0_2 wizard is the permission, the folders and the backup, only the permission is required`() {
        assertThat(OnboardingStep.entries)
            .containsExactly(OnboardingStep.PERMISSION, OnboardingStep.FOLDERS, OnboardingStep.BACKUP)
            .inOrder()
        assertThat(OnboardingStep.PERMISSION.skippable).isFalse()
        assertThat(OnboardingStep.FOLDERS.skippable).isTrue()
        assertThat(OnboardingStep.BACKUP.skippable).isTrue()
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
        val backup = folders.next()
        assertThat(backup.step).isEqualTo(OnboardingStep.BACKUP)

        val finished = backup.next()
        assertThat(finished.finished).isTrue()
        assertThat(finished.skipped).isEmpty()
        assertThat(finished.next()).isEqualTo(finished)
    }

    @Test
    fun `skipped steps are remembered`() {
        val wizard = OnboardingWizard().next().skip()

        assertThat(wizard.step).isEqualTo(OnboardingStep.BACKUP)
        assertThat(wizard.skipped).containsExactly(OnboardingStep.FOLDERS)

        val finished = wizard.skip()
        assertThat(finished.finished).isTrue()
        assertThat(finished.skipped).containsExactly(OnboardingStep.FOLDERS, OnboardingStep.BACKUP)
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
        assertThat(fromStart.skipped).containsExactlyElementsIn(OnboardingStep.entries)

        val fromFolders = OnboardingWizard().next().skipAll()
        assertThat(fromFolders.skipped).containsExactly(OnboardingStep.FOLDERS, OnboardingStep.BACKUP)
    }

    @Test
    fun `finished wizard stays as it is`() {
        val finished = OnboardingWizard().next().skipAll()

        assertThat(finished.skip()).isEqualTo(finished)
        assertThat(finished.skipAll()).isEqualTo(finished)
    }
}
