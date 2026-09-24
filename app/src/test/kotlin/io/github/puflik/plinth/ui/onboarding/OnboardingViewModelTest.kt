package io.github.puflik.plinth.ui.onboarding

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.permission.PermissionState
import io.github.puflik.plinth.startup.FakeOnboardingSettings
import io.github.puflik.plinth.startup.OnboardingRecord
import io.github.puflik.plinth.startup.OnboardingSettings
import io.github.puflik.plinth.startup.OnboardingStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Мастер первого запуска на экране (F1): когда показывать и куда он ведёт. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val settings = FakeOnboardingSettings()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is shown until the record is read`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = OnboardingViewModel(NeverRead())
            collect(viewModel)

            assertThat(viewModel.uiState.value).isEqualTo(OnboardingUiState.Loading)
        }

    @Test
    fun `first launch opens the wizard on the permission step`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = OnboardingViewModel(settings)
            collect(viewModel)

            assertThat(viewModel.uiState.value).isEqualTo(OnboardingUiState.Step(OnboardingStep.PERMISSION))
        }

    @Test
    fun `finished wizard is not shown again`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = OnboardingViewModel(FakeOnboardingSettings(OnboardingRecord(finished = true)))
            collect(viewModel)

            assertThat(viewModel.uiState.value).isEqualTo(OnboardingUiState.Finished)
        }

    @Test
    fun `granted permission moves on to the folders, refusal stays`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = OnboardingViewModel(settings)
            collect(viewModel)

            viewModel.onPermission(PermissionState.Denied)
            assertThat(viewModel.uiState.value).isEqualTo(OnboardingUiState.Step(OnboardingStep.PERMISSION))

            viewModel.onPermission(PermissionState.Granted)
            assertThat(viewModel.uiState.value).isEqualTo(OnboardingUiState.Step(OnboardingStep.FOLDERS))

            // Возврат на экран с тем же разрешением шаг папок не проскакивает.
            viewModel.onPermission(PermissionState.Granted)
            assertThat(viewModel.uiState.value).isEqualTo(OnboardingUiState.Step(OnboardingStep.FOLDERS))
        }

    @Test
    fun `last step done finishes the wizard with nothing skipped`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = OnboardingViewModel(settings)
            collect(viewModel)

            viewModel.onPermission(PermissionState.Granted)
            viewModel.onNext()

            assertThat(viewModel.uiState.value).isEqualTo(OnboardingUiState.Finished)
            assertThat(settings.record.value).isEqualTo(OnboardingRecord(finished = true, skipped = emptySet()))
        }

    @Test
    fun `skipped folders are remembered`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = OnboardingViewModel(settings)
            collect(viewModel)

            viewModel.onPermission(PermissionState.Granted)
            viewModel.onSkip()

            assertThat(viewModel.uiState.value).isEqualTo(OnboardingUiState.Finished)
            assertThat(settings.record.value.skipped).containsExactly(OnboardingStep.FOLDERS)
        }

    @Test
    fun `skip all on the permission step skips everything`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = OnboardingViewModel(settings)
            collect(viewModel)

            viewModel.onSkipAll()

            assertThat(viewModel.uiState.value).isEqualTo(OnboardingUiState.Finished)
            assertThat(settings.record.value.skipped)
                .containsExactly(OnboardingStep.PERMISSION, OnboardingStep.FOLDERS)
        }

    private fun TestScope.collect(viewModel: OnboardingViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    /** Хранилище, которое ещё не ответило. */
    private class NeverRead : OnboardingSettings {
        override val record: Flow<OnboardingRecord> = MutableSharedFlow()

        override suspend fun finish(skipped: Set<OnboardingStep>) = Unit

        override suspend fun settle(step: OnboardingStep) = Unit
    }
}
