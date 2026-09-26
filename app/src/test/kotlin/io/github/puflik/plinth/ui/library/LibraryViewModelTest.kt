package io.github.puflik.plinth.ui.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeFolderSettings
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.LibraryScan
import io.github.puflik.plinth.library.ScanProgress
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import io.github.puflik.plinth.library.model.FolderConfig
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.permission.PermissionState
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.TrackSort
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.settings.FakeSortSettings
import io.github.puflik.plinth.startup.FakeOnboardingSettings
import io.github.puflik.plinth.startup.OnboardingRecord
import io.github.puflik.plinth.startup.OnboardingStep
import kotlinx.coroutines.CoroutineScope
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
import kotlin.time.Duration.Companion.minutes

/** Библиотека (C4.1): разрешение → скан → списки на фейках; касание → звук. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val scan = FakeLibraryScan()
    private val repository = FakeLibraryRepository()
    private val engine = FakeAudioEngine()
    private val playback = PlaybackController(engine, CoroutineScope(Dispatchers.Unconfined))
    private val userData = FakeUserDataRepository()
    private val actions = TrackActions(playback, userData, CoroutineScope(Dispatchers.Unconfined))
    private val sorts = FakeSortSettings()
    private val folders = FakeFolderSettings()
    private val onboarding = FakeOnboardingSettings(OnboardingRecord(finished = true))
    private val viewModel by lazy { LibraryViewModel(scan, repository, playback, actions, sorts, folders, onboarding) }

    private val bohemian = track(1, "Bohemian Rhapsody", "Queen", "A Night at the Opera", "Music/Queen/")
    private val yesterday = track(2, "Yesterday", "The Beatles", "Rubber Soul", "Music/Beatles/")
    private val anthem = track(3, "Anthem", "Queen", "Innuendo", "Download/")

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

            assertThat(viewModel.uiState.value.permission).isEqualTo(PermissionState.NotRequested)
            assertThat(viewModel.uiState.value.scan).isEqualTo(ScanProgress.Idle)
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

            val done =
                LibraryUiState(
                    PermissionState.Granted,
                    ScanProgress.Done(found = 42),
                    loaded = true,
                    scannedFolders = FolderConfig.DEFAULT.included,
                )
            assertThat(viewModel.uiState.value).isEqualTo(done)
        }

    @Test
    fun `tracks come by title until another order is chosen`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            repository.upsert(listOf(yesterday, bohemian, anthem))

            assertThat(viewModel.uiState.value.tracks).containsExactly(anthem, bohemian, yesterday).inOrder()

            viewModel.onTrackSort(TrackSort.ARTIST)

            val state = viewModel.uiState.value
            assertThat(state.trackSort).isEqualTo(TrackSort.ARTIST)
            assertThat(state.tracks).containsExactly(yesterday, anthem, bohemian).inOrder()
        }

    @Test
    fun `order chosen earlier is there from the start and is saved`() =
        runTest(UnconfinedTestDispatcher()) {
            sorts.trackSort.value = TrackSort.ARTIST
            backgroundScope.launch { viewModel.uiState.collect {} }
            repository.upsert(listOf(yesterday, bohemian, anthem))

            assertThat(viewModel.uiState.value.tracks).containsExactly(yesterday, anthem, bohemian).inOrder()

            viewModel.onTrackSort(TrackSort.TITLE)
            viewModel.onAlbumSort(AlbumSort.ARTIST)

            assertThat(sorts.trackSort.value).isEqualTo(TrackSort.TITLE)
            assertThat(sorts.albumSort.value).isEqualTo(AlbumSort.ARTIST)
        }

    @Test
    fun `albums have an order of their own`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            repository.upsert(listOf(yesterday, bohemian, anthem))
            val opera = Album("A Night at the Opera", "Queen", 1, coverTrackUri = bohemian.uri)
            val soul = Album("Rubber Soul", "The Beatles", 1, coverTrackUri = yesterday.uri)
            val innuendo = Album("Innuendo", "Queen", 1, coverTrackUri = anthem.uri)

            assertThat(viewModel.uiState.value.albums).containsExactly(innuendo, opera, soul).inOrder()

            viewModel.onAlbumSort(AlbumSort.ARTIST)

            assertThat(viewModel.uiState.value.albumSort).isEqualTo(AlbumSort.ARTIST)
            assertThat(viewModel.uiState.value.albums).containsExactly(soul, innuendo, opera).inOrder()
            assertThat(viewModel.uiState.value.trackSort).isEqualTo(TrackSort.TITLE)
        }

    @Test
    fun `artists are listed by name`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            repository.upsert(listOf(yesterday, bohemian, anthem))

            val artists = viewModel.uiState.value.artists

            assertThat(artists).containsExactly(Artist("The Beatles", 1, 1), Artist("Queen", 2, 2)).inOrder()
        }

    @Test
    fun `tapped track plays the track list from it`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            repository.upsert(listOf(yesterday, bohemian, anthem))

            viewModel.onTrack(bohemian, TrackAction.PLAY)

            val queue = playback.queue.value
            assertThat(engine.preparedSources).containsExactly(AudioSource.LocalFile(bohemian.uri))
            assertThat(queue.context).isEqualTo(QueueContext.Tracks)
            assertThat(queue.current?.title).isEqualTo("Bohemian Rhapsody")
            assertThat(queue.current?.artist).isEqualTo("Queen")
            assertThat(queue.upcoming.map { it.title }).containsExactly("Yesterday")
        }

    @Test
    fun `track from a folder plays that folder`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            repository.upsert(listOf(yesterday, bohemian, anthem))
            viewModel.openFolder("Download/")

            viewModel.onFolderTrack(anthem, TrackAction.PLAY)

            assertThat(playback.queue.value.context).isEqualTo(QueueContext.Folder("Download/"))
            assertThat(playback.queue.value.upcoming).isEmpty()
        }

    @Test
    fun `long press adds a track without interrupting the current one`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            repository.upsert(listOf(yesterday, bohemian, anthem))
            viewModel.onTrack(anthem, TrackAction.PLAY)

            viewModel.onTrack(yesterday, TrackAction.PLAY_NEXT)

            assertThat(engine.preparedSources).hasSize(1)
            val upNext = playback.queue.value.upcoming
            assertThat(upNext.first().title).isEqualTo("Yesterday")
        }

    @Test
    fun `file picked from the menu plays with its name`() =
        runTest(UnconfinedTestDispatcher()) {
            viewModel.openFile("content://plinth.test/song.flac", "song.flac")

            assertThat(engine.preparedSources).containsExactly(AudioSource.LocalFile("content://plinth.test/song.flac"))
            assertThat(playback.queue.value.context).isEqualTo(QueueContext.File)
            assertThat(
                playback.queue.value.current
                    ?.title,
            ).isEqualTo("song.flac")
        }

    @Test
    fun `folders start at the root and open one level at a time`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            repository.upsert(listOf(yesterday, bohemian, anthem))

            val root = viewModel.uiState.value.folder
            assertThat(root.path).isEmpty()
            assertThat(root.folders.map { it.name }).containsExactly("Download", "Music").inOrder()

            viewModel.openFolder("Music/Queen/")
            assertThat(viewModel.uiState.value.folder.tracks).containsExactly(bohemian)

            viewModel.folderUp()
            assertThat(viewModel.uiState.value.folder.path).isEqualTo("Music/")
            viewModel.folderUp()
            assertThat(viewModel.uiState.value.folder.path).isEmpty()
        }

    @Test
    fun `tracks in a folder follow the track order`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            val zebra = track(4, "Zebra", "ABBA", null, "Download/")
            repository.upsert(listOf(anthem, zebra))
            viewModel.openFolder("Download/")

            assertThat(viewModel.uiState.value.folder.tracks).containsExactly(anthem, zebra).inOrder()

            viewModel.onTrackSort(TrackSort.ARTIST)

            assertThat(viewModel.uiState.value.folder.tracks).containsExactly(zebra, anthem).inOrder()
        }

    @Test
    fun `open folder that vanished after a scan falls back to what is left`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            repository.upsert(listOf(yesterday, bohemian))
            viewModel.openFolder("Music/Queen/")

            repository.markMissing(listOf(bohemian.id))

            assertThat(viewModel.uiState.value.folder.path).isEqualTo("Music/")

            viewModel.folderUp()
            assertThat(viewModel.uiState.value.folder.path).isEmpty()
        }

    @Test
    fun `finished scan without tracks is the empty library with the folders it looked in`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onPermission(PermissionState.Granted)

            scan.progress.value = ScanProgress.Running(written = 0, total = 0)
            assertThat(viewModel.uiState.value.isEmpty).isFalse()

            scan.progress.value = ScanProgress.Done(found = 0)
            assertThat(viewModel.uiState.value.isEmpty).isTrue()
            assertThat(viewModel.uiState.value.scannedFolders).containsExactly("Music/", "Download/").inOrder()
            assertThat(viewModel.uiState.value.prompt).isNull()

            repository.upsert(listOf(anthem))
            assertThat(viewModel.uiState.value.isEmpty).isFalse()
        }

    @Test
    fun `library is not empty until its lists are read`() {
        val unread = LibraryUiState(PermissionState.Granted, ScanProgress.Done(found = 0))

        assertThat(unread.isEmpty).isFalse()
        assertThat(unread.copy(loaded = true).isEmpty).isTrue()
        assertThat(unread.copy(loaded = true, permission = PermissionState.Denied).isEmpty).isFalse()
    }

    @Test
    fun `folders skipped in the wizard come back in the empty library only`() =
        runTest(UnconfinedTestDispatcher()) {
            onboarding.record.value = OnboardingRecord(finished = true, skipped = setOf(OnboardingStep.FOLDERS))
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onPermission(PermissionState.Granted)
            scan.progress.value = ScanProgress.Done(found = 0)

            assertThat(viewModel.uiState.value.prompt).isEqualTo(OnboardingStep.FOLDERS)

            repository.upsert(listOf(anthem))
            assertThat(viewModel.uiState.value.prompt).isNull()
        }

    @Test
    fun `folder picked in the empty library is scanned and settles the prompt`() =
        runTest(UnconfinedTestDispatcher()) {
            onboarding.record.value = OnboardingRecord(finished = true, skipped = setOf(OnboardingStep.FOLDERS))
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onPermission(PermissionState.Granted)
            scan.progress.value = ScanProgress.Done(found = 0)

            viewModel.onFolderPicked("Podcasts/")

            assertThat(folders.folders.value.included).containsExactly("Music/", "Download/", "Podcasts/")
            assertThat(scan.starts).isEqualTo(2)
            assertThat(onboarding.record.value.skipped).isEmpty()
            assertThat(viewModel.uiState.value.prompt).isNull()
        }

    @Test
    fun `dismissed prompt does not come back`() =
        runTest(UnconfinedTestDispatcher()) {
            onboarding.record.value = OnboardingRecord(finished = true, skipped = setOf(OnboardingStep.FOLDERS))
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onPermission(PermissionState.Granted)
            scan.progress.value = ScanProgress.Done(found = 0)

            viewModel.onDismissPrompt(OnboardingStep.FOLDERS)

            assertThat(viewModel.uiState.value.prompt).isNull()
            assertThat(onboarding.record.value.skipped).isEmpty()
            assertThat(viewModel.uiState.value.isEmpty).isTrue()
        }

    private fun track(
        id: Long,
        title: String,
        artist: String?,
        album: String?,
        folder: String,
    ) = LibraryTrack(
        id = TrackId("track-$id"),
        uri = "/storage/emulated/0/Music/track-$id.mp3",
        title = title,
        artist = artist,
        album = album,
        duration = 3.minutes,
        folder = folder,
    )

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
