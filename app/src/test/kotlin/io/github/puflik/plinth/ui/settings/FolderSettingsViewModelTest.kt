package io.github.puflik.plinth.ui.settings

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.FakeFolderSettings
import io.github.puflik.plinth.library.LibraryScan
import io.github.puflik.plinth.library.ScanProgress
import io.github.puflik.plinth.library.model.FolderConfig
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

/** Экран папок (C2.5): что сканировать, что пропускать, пересканировать. */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderSettingsViewModelTest {
    private val settings = FakeFolderSettings()
    private val scan = FakeScan()
    private val viewModel by lazy { FolderSettingsViewModel(settings, scan) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `screen shows the chosen folders and the scan`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            scan.progress.value = ScanProgress.Done(found = 7)

            assertThat(
                viewModel.uiState.value,
            ).isEqualTo(FolderSettingsUiState(FolderConfig.DEFAULT, ScanProgress.Done(7)))
        }

    @Test
    fun `picked folders are included or excluded and removed again`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onInclude("Podcasts/")
            viewModel.onExclude("Download/")
            assertThat(viewModel.uiState.value.folders)
                .isEqualTo(FolderConfig(listOf("Music/", "Podcasts/"), listOf("Download/")))

            viewModel.onRemove("Podcasts/")
            assertThat(settings.folders.value.included).containsExactly("Music/")
        }

    @Test
    fun `reset brings back the default folders`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onRemove("Download/")
            viewModel.onExclude("Music/Rain/")

            viewModel.onReset()

            assertThat(viewModel.uiState.value.folders).isEqualTo(FolderConfig.DEFAULT)
            assertThat(viewModel.uiState.value.isDefault).isTrue()
        }

    @Test
    fun `changed folders are not the default`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onRemove("Download/")

            assertThat(viewModel.uiState.value.isDefault).isFalse()
        }

    @Test
    fun `changing folders does not scan by itself, rescan does`() =
        runTest(UnconfinedTestDispatcher()) {
            viewModel.onExclude("Download/")
            assertThat(scan.starts).isEqualTo(0)

            viewModel.onRescan()

            assertThat(scan.starts).isEqualTo(1)
        }

    private class FakeScan : LibraryScan {
        override val progress = MutableStateFlow<ScanProgress>(ScanProgress.Idle)
        var starts = 0

        override fun start() {
            starts++
        }

        override fun cancel() = Unit
    }
}
