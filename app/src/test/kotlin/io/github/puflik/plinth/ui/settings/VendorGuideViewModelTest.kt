package io.github.puflik.plinth.ui.settings

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.diagnostics.vendor.KillReport
import io.github.puflik.plinth.diagnostics.vendor.Vendor
import io.github.puflik.plinth.diagnostics.vendor.VendorPromptState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** «Почему музыка останавливается» — один раз после убийства (G2.4). */
@OptIn(ExperimentalCoroutinesApi::class)
class VendorGuideViewModelTest {
    private val prompt = FakePrompt()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `guide shows after a kill and never again once closed`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = VendorGuideViewModel(KillReport(detected = true), prompt, Vendor.XIAOMI)
            backgroundScope.launch { viewModel.showGuide.collect {} }
            assertThat(viewModel.showGuide.value).isTrue()

            viewModel.onClose()

            assertThat(viewModel.showGuide.value).isFalse()
            assertThat(prompt.shown.value).isTrue()
            val next = VendorGuideViewModel(KillReport(detected = true), prompt, Vendor.XIAOMI)
            backgroundScope.launch { next.showGuide.collect {} }
            assertThat(next.showGuide.value).isFalse()
        }

    @Test
    fun `no kill, no guide`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = VendorGuideViewModel(KillReport(detected = false), prompt, Vendor.SAMSUNG)
            backgroundScope.launch { viewModel.showGuide.collect {} }

            assertThat(viewModel.showGuide.value).isFalse()
        }

    private class FakePrompt : VendorPromptState {
        override val shown = MutableStateFlow(false)

        override suspend fun markShown() {
            shown.value = true
        }
    }
}
