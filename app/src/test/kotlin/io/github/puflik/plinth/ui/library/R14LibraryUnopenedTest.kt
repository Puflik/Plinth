package io.github.puflik.plinth.ui.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.CoreOpening
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeFolderSettings
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.LibraryRepository
import io.github.puflik.plinth.library.LibraryScan
import io.github.puflik.plinth.library.ScanProgress
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.permission.PermissionState
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.TrackSort
import io.github.puflik.plinth.settings.FakeSortSettings
import io.github.puflik.plinth.startup.FakeOnboardingSettings
import io.github.puflik.plinth.startup.OnboardingRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Р1.4 (`docs/work/r1-4.md`, ревью v0.2, находка 4): ядро не открылось —
 * экран библиотеки говорит об этом и предлагает повторить, а не остаётся
 * пустым. Списки при этом не приходят вовсе, как у `CoreLibraryRepository`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R14LibraryUnopenedTest {
    private val base = FakeLibraryRepository()
    private val playback = PlaybackController(FakeAudioEngine(), CoroutineScope(Dispatchers.Unconfined))
    private val actions =
        TrackActions(
            playback,
            FakeUserDataRepository(),
            FakePlaylistRepository(),
            CoroutineScope(Dispatchers.Unconfined),
        )
    private val viewModel by lazy {
        LibraryViewModel(
            Scan(),
            ClosedUntilOpen(base),
            playback,
            actions,
            FakeSortSettings(),
            FakeFolderSettings(),
            FakeOnboardingSettings(OnboardingRecord(finished = true)),
        )
    }
    private val yesterday =
        LibraryTrack(
            id = TrackId("track-1"),
            uri = "/storage/emulated/0/Music/track-1.mp3",
            title = "Yesterday",
            artist = "The Beatles",
            album = "Help!",
            duration = 3.minutes,
            folder = "Music/",
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a library that did not open says so instead of staying blank`() =
        runTest(UnconfinedTestDispatcher()) {
            base.opening.value = CoreOpening.FAILED
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onPermission(PermissionState.Granted)

            assertThat(viewModel.uiState.value.unopened).isTrue()
            assertThat(viewModel.uiState.value.files).isEqualTo(FilesView.UNOPENED)
        }

    @Test
    fun `retry asks the library to open again`() =
        runTest(UnconfinedTestDispatcher()) {
            base.opening.value = CoreOpening.FAILED
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onRetry()

            assertThat(base.reopens).isEqualTo(1)
        }

    @Test
    fun `once the library opens the lists come and the notice goes`() =
        runTest(UnconfinedTestDispatcher()) {
            base.upsert(listOf(yesterday))
            base.opening.value = CoreOpening.FAILED
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onPermission(PermissionState.Granted)
            assertThat(viewModel.uiState.value.unopened).isTrue()

            base.opening.value = CoreOpening.OPEN

            assertThat(viewModel.uiState.value.unopened).isFalse()
            assertThat(viewModel.uiState.value.files).isEqualTo(FilesView.LISTS)
            assertThat(viewModel.uiState.value.tracks).containsExactly(yesterday)
        }

    /** Ядро ещё открывается — говорить не о чем: экран тот же, что до задачи. */
    @Test
    fun `while the library is opening nothing is said yet`() =
        runTest(UnconfinedTestDispatcher()) {
            base.opening.value = CoreOpening.PENDING
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onPermission(PermissionState.Granted)

            assertThat(viewModel.uiState.value.unopened).isFalse()
            assertThat(viewModel.uiState.value.files).isEqualTo(FilesView.NOTHING)
        }

    /** Как ядро: пока фонотека не открыта, списки не приходят (`CoreLibraryRepository.read`). */
    private class ClosedUntilOpen(
        private val base: FakeLibraryRepository,
    ) : LibraryRepository by base {
        override fun tracks(sort: TrackSort): Flow<List<LibraryTrack>> = whenOpen { base.tracks(sort) }

        override fun albums(sort: AlbumSort): Flow<List<Album>> = whenOpen { base.albums(sort) }

        override fun artists(): Flow<List<Artist>> = whenOpen { base.artists() }

        private fun <T> whenOpen(lists: () -> Flow<T>): Flow<T> =
            base.opening.flatMapLatest { if (it == CoreOpening.OPEN) lists() else emptyFlow() }
    }

    private class Scan : LibraryScan {
        override val progress = MutableStateFlow<ScanProgress>(ScanProgress.Idle)

        override fun start() = Unit

        override fun cancel() = Unit
    }
}
