package io.github.puflik.plinth.ui.settings

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.online.FakeOnlineSettings
import io.github.puflik.plinth.online.OnlineProviderIds
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

/** Переключатели провайдеров в настройках (Ю3г): включены все, пока человек не выключит; выбор сохраняется. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineSourcesProvidersViewModelTest {
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
    fun `every provider is on until switched off`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.disabledProviders.collect {} }

            assertThat(viewModel.disabledProviders.value).isEmpty()
        }

    @Test
    fun `a provider switched off is remembered and the other stays on`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.disabledProviders.collect {} }

            viewModel.onProviderToggle(OnlineProviderIds.YOUTUBE, false)

            assertThat(settings.disabledProviders.value).containsExactly(OnlineProviderIds.YOUTUBE)
            assertThat(viewModel.disabledProviders.value).containsExactly(OnlineProviderIds.YOUTUBE)
        }

    @Test
    fun `a provider switched on again leaves the set`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.disabledProviders.collect {} }
            viewModel.onProviderToggle(OnlineProviderIds.YOUTUBE, false)
            viewModel.onProviderToggle(OnlineProviderIds.ARCHIVE, false)

            viewModel.onProviderToggle(OnlineProviderIds.YOUTUBE, true)

            assertThat(viewModel.disabledProviders.value).containsExactly(OnlineProviderIds.ARCHIVE)
        }

    @Test
    fun `the main switch does not touch the choice of providers`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.disabledProviders.collect {} }
            viewModel.onProviderToggle(OnlineProviderIds.YOUTUBE, false)

            viewModel.onToggle(false)

            assertThat(settings.enabled.value).isFalse()
            assertThat(viewModel.disabledProviders.value).containsExactly(OnlineProviderIds.YOUTUBE)
        }
}
