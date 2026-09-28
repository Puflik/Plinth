package io.github.puflik.plinth.ui.settings

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.online.FakeOnlineSettings
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

/** Переключатель «Онлайн-источники» в настройках (E3b): включён по умолчанию, выбор сохраняется. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineSourcesViewModelTest {
    private val settings = FakeOnlineSettings()
    private val viewModel by lazy { OnlineSourcesViewModel(settings) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sources are on until switched off`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.enabled.collect {} }
            val before = viewModel.enabled.value

            viewModel.onToggle(false)

            assertThat(before).isTrue()
            assertThat(settings.enabled.value).isFalse()
            assertThat(viewModel.enabled.value).isFalse()
        }
}
