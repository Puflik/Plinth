package io.github.puflik.plinth.ui.settings

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.settings.FakeStartSettings
import io.github.puflik.plinth.settings.StartScreen
import io.github.puflik.plinth.startup.DecisionExplanation
import io.github.puflik.plinth.startup.StartDecision
import io.github.puflik.plinth.startup.StartDestination
import io.github.puflik.plinth.startup.StartLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.hours

/** Настройка стартового экрана и объяснение последнего решения (F3, 12.2, 12.6). */
@OptIn(ExperimentalCoroutinesApi::class)
class StartScreenSettingViewModelTest {
    private val settings = FakeStartSettings()
    private val log = StartLog()
    private val viewModel by lazy { StartScreenSettingViewModel(settings, log) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setting shows the choice and why this launch opened where it did`() =
        runTest(UnconfinedTestDispatcher()) {
            val decision = StartDecision(StartDestination.LIBRARY, DecisionExplanation.LongBreak(3.hours))
            log.record(decision)
            backgroundScope.launch { viewModel.uiState.collect {} }

            assertThat(viewModel.uiState.value).isEqualTo(StartScreenSettingUiState(StartScreen.AUTO, decision))
        }

    @Test
    fun `chosen screen is saved`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onChoose(StartScreen.CONTINUE)

            assertThat(settings.startScreen.value).isEqualTo(StartScreen.CONTINUE)
            assertThat(viewModel.uiState.value.choice).isEqualTo(StartScreen.CONTINUE)
        }
}
