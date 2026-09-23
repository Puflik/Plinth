package io.github.puflik.plinth.ui.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.LibraryScan
import io.github.puflik.plinth.library.ScanProgress
import io.github.puflik.plinth.library.permission.PermissionState
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

/** Библиотека до списков (шаг 4): разрешение → скан → «Найдено N треков». */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val scan = FakeLibraryScan()
    private val viewModel by lazy { LibraryViewModel(scan) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is scanned before the permission is known`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            val initial = LibraryUiState(PermissionState.NotRequested, ScanProgress.Idle)
            assertThat(viewModel.uiState.value).isEqualTo(initial)
            assertThat(scan.starts).isEqualTo(0)
        }

    @Test
    fun `granted permission starts one scan`() =
        runTest(UnconfinedTestDispatcher()) {
            viewModel.onPermission(PermissionState.Granted)
            viewModel.onPermission(PermissionState.Granted)

            assertThat(scan.starts).isEqualTo(1)
        }

    @Test
    fun `denied permission starts no scan`() =
        runTest(UnconfinedTestDispatcher()) {
            viewModel.onPermission(PermissionState.Denied)
            viewModel.onPermission(PermissionState.PermanentlyDenied)

            assertThat(scan.starts).isEqualTo(0)
        }

    @Test
    fun `permission granted after a refusal starts the scan`() =
        runTest(UnconfinedTestDispatcher()) {
            viewModel.onPermission(PermissionState.Denied)

            viewModel.onPermission(PermissionState.Granted)

            assertThat(scan.starts).isEqualTo(1)
        }

    @Test
    fun `screen sees permission and scan progress`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onPermission(PermissionState.Granted)

            scan.progress.value = ScanProgress.Done(found = 42)

            val done = LibraryUiState(PermissionState.Granted, ScanProgress.Done(found = 42))
            assertThat(viewModel.uiState.value).isEqualTo(done)
        }

    private class FakeLibraryScan : LibraryScan {
        override val progress = MutableStateFlow<ScanProgress>(ScanProgress.Idle)
        var starts = 0

        override fun start() {
            starts++
        }

        override fun cancel() {
            progress.value = ScanProgress.Idle
        }
    }
}
