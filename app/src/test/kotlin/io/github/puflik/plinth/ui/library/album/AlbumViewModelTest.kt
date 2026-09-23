package io.github.puflik.plinth.ui.library.album

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.LibraryTrack
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

/** Минимальный экран альбома (C4.1): треки по диску и номеру, касание → звук. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumViewModelTest {
    private val repository = FakeLibraryRepository()
    private val engine = FakeAudioEngine()
    private val playback = PlaybackController(engine)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `album comes from the navigation arguments`() {
        val viewModel = viewModelFor(Album("A Night at the Opera", "Queen", 12))

        assertThat(viewModel.album).isEqualTo(Album("A Night at the Opera", "Queen", 12))
    }

    @Test
    fun `tracks of the album go by disc and number`() =
        runTest(UnconfinedTestDispatcher()) {
            val second = track(1, "Bohemian Rhapsody", "A Night at the Opera", "Queen", number = 11)
            val first = track(2, "Death on Two Legs", "A Night at the Opera", "Queen", number = 1)
            val other = track(3, "Anthem", "Innuendo", "Queen", number = 1)
            repository.upsert(listOf(second, first, other))
            val viewModel = viewModelFor(Album("A Night at the Opera", "Queen", 2))
            backgroundScope.launch { viewModel.tracks.collect {} }

            assertThat(viewModel.tracks.value).containsExactly(first, second).inOrder()
        }

    @Test
    fun `album without an artist finds its tracks`() =
        runTest(UnconfinedTestDispatcher()) {
            val nameless = track(1, "Intro", "Demos", artist = null, number = 1)
            repository.upsert(listOf(nameless))
            val viewModel = viewModelFor(Album("Demos", null, 1))
            backgroundScope.launch { viewModel.tracks.collect {} }

            assertThat(viewModel.album.artist).isNull()
            assertThat(viewModel.tracks.value).containsExactly(nameless)
        }

    @Test
    fun `tapped track plays with its title`() {
        val song = track(1, "Bohemian Rhapsody", "A Night at the Opera", "Queen", number = 11)
        val viewModel = viewModelFor(Album("A Night at the Opera", "Queen", 1))

        viewModel.play(song)

        assertThat(engine.preparedSources).containsExactly(AudioSource.LocalFile(song.uri))
        assertThat(playback.title.value).isEqualTo("Bohemian Rhapsody")
    }

    private fun viewModelFor(album: Album): AlbumViewModel {
        val arguments = AlbumViewModel.arguments(album)
        return AlbumViewModel(SavedStateHandle(arguments), repository, playback)
    }

    private fun track(
        id: Long,
        title: String,
        album: String,
        artist: String?,
        number: Int,
    ) = LibraryTrack(
        id = id,
        uri = "content://media/external/audio/media/$id",
        title = title,
        artist = artist,
        album = album,
        trackNumber = number,
        duration = 3.minutes,
        folder = "Music/",
        modifiedAt = 0,
    )
}
