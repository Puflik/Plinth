package io.github.puflik.plinth.ui.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.LibraryScan
import io.github.puflik.plinth.library.ScanProgress
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.permission.PermissionState
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.TrackSort
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
    private val playback = PlaybackController(engine)
    private val viewModel by lazy { LibraryViewModel(scan, repository, playback) }

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
    fun `albums have an order of their own`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            repository.upsert(listOf(yesterday, bohemian, anthem))
            val opera = Album("A Night at the Opera", "Queen", 1)
            val soul = Album("Rubber Soul", "The Beatles", 1)
            val innuendo = Album("Innuendo", "Queen", 1)

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
    fun `tapped track plays with its title`() =
        runTest(UnconfinedTestDispatcher()) {
            viewModel.play(bohemian)

            assertThat(engine.preparedSources).containsExactly(AudioSource.LocalFile(bohemian.uri))
            assertThat(engine.lastParams?.autoPlay).isTrue()
            assertThat(playback.title.value).isEqualTo("Bohemian Rhapsody")
        }

    @Test
    fun `file picked from the menu plays with its name`() =
        runTest(UnconfinedTestDispatcher()) {
            viewModel.openFile("content://plinth.test/song.flac", "song.flac")

            assertThat(engine.preparedSources).containsExactly(AudioSource.LocalFile("content://plinth.test/song.flac"))
            assertThat(playback.title.value).isEqualTo("song.flac")
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

    private fun track(
        id: Long,
        title: String,
        artist: String?,
        album: String?,
        folder: String,
    ) = LibraryTrack(
        id = id,
        uri = "content://media/external/audio/media/$id",
        title = title,
        artist = artist,
        album = album,
        duration = 3.minutes,
        folder = folder,
        modifiedAt = 0,
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
