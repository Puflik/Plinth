package io.github.puflik.plinth.ui.library.artist

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CoroutineScope
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
import kotlin.time.Duration.Companion.minutes

/** Минимальный экран исполнителя (E5): его альбомы и треки, касание → звук. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistViewModelTest {
    private val repository = FakeLibraryRepository()
    private val engine = FakeAudioEngine()
    private val playback = PlaybackController(engine, CoroutineScope(Dispatchers.Unconfined))
    private val userData = FakeUserDataRepository()
    private val actions =
        TrackActions(playback, userData, FakePlaylistRepository(), CoroutineScope(Dispatchers.Unconfined))
    private val anthem = track(1, "Innuendo", "Queen", "Innuendo")
    private val bohemian = track(2, "Bohemian Rhapsody", "Queen", "A Night at the Opera")
    private val waterloo = track(3, "Waterloo", "ABBA", "Waterloo")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `artist comes from the navigation arguments`() {
        assertThat(viewModelFor("Queen").artist).isEqualTo("Queen")
    }

    @Test
    fun `albums and tracks are the artist's own`() =
        runTest(UnconfinedTestDispatcher()) {
            repository.upsert(listOf(anthem, bohemian, waterloo))
            val viewModel = viewModelFor("Queen")
            backgroundScope.launch { viewModel.uiState.collect {} }

            val state = viewModel.uiState.value
            assertThat(state.albums.map(Album::title)).containsExactly("Innuendo", "A Night at the Opera").inOrder()
            assertThat(state.tracks).containsExactly(anthem, bohemian).inOrder()
        }

    @Test
    fun `tapped track plays the artist's tracks from it`() =
        runTest(UnconfinedTestDispatcher()) {
            repository.upsert(listOf(anthem, bohemian, waterloo))
            val viewModel = viewModelFor("Queen")
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onTrack(bohemian, TrackAction.PLAY)

            assertThat(engine.preparedSources).containsExactly(AudioSource.LocalFile(bohemian.uri))
            val queue = playback.queue.value
            assertThat(queue.context).isEqualTo(QueueContext.Artist("Queen"))
            assertThat(queue.contextItems.map { it.title }).containsExactly("Innuendo", "Bohemian Rhapsody").inOrder()
        }

    private fun viewModelFor(artist: String) =
        ArtistViewModel(SavedStateHandle(ArtistViewModel.arguments(artist)), repository, actions)

    private fun track(
        id: Long,
        title: String,
        artist: String,
        album: String,
    ) = LibraryTrack(
        id = TrackId("track-$id"),
        uri = "/storage/emulated/0/Music/track-$id.mp3",
        title = title,
        artist = artist,
        album = album,
        duration = 3.minutes,
        folder = "Music/",
    )
}
