package io.github.puflik.plinth.ui.library.album

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
    private val playback = PlaybackController(engine, CoroutineScope(Dispatchers.Unconfined))
    private val userData = FakeUserDataRepository()
    private val actions = TrackActions(playback, userData, CoroutineScope(Dispatchers.Unconfined))

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
    fun `tapped track plays the album from it`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = track(1, "Death on Two Legs", "A Night at the Opera", "Queen", number = 1)
            val second = track(2, "Bohemian Rhapsody", "A Night at the Opera", "Queen", number = 11)
            repository.upsert(listOf(first, second))
            val viewModel = viewModelFor(Album("A Night at the Opera", "Queen", 2))
            backgroundScope.launch { viewModel.tracks.collect {} }

            viewModel.onTrack(first, TrackAction.PLAY)

            assertThat(engine.preparedSources).containsExactly(AudioSource.LocalFile(first.uri))
            assertThat(playback.queue.value.context).isEqualTo(QueueContext.Album("A Night at the Opera", "Queen"))
            assertThat(
                playback.queue.value.upcoming
                    .map { it.title },
            ).containsExactly("Bohemian Rhapsody")
        }

    /** Лайк из меню уходит в журнал и очередь не трогает. */
    @Test
    fun `a like from the menu goes to the journal`() =
        runTest(UnconfinedTestDispatcher()) {
            val song = track(1, "Mustapha", "Jazz", "Queen", number = 1)
            repository.upsert(listOf(song))
            val id = userData.add(song.uri).single()
            val viewModel = viewModelFor(Album("Jazz", "Queen", 1))

            viewModel.onTrack(song, TrackAction.LIKE)
            val liked = userData.liked(id).first()
            viewModel.onTrack(song.copy(liked = true), TrackAction.UNLIKE)

            assertThat(liked).isTrue()
            assertThat(userData.liked(id).first()).isFalse()
            assertThat(engine.preparedSources).isEmpty()
            assertThat(playback.queue.value.current).isNull()
        }

    private fun viewModelFor(album: Album): AlbumViewModel {
        val arguments = AlbumViewModel.arguments(album)
        return AlbumViewModel(SavedStateHandle(arguments), repository, actions)
    }

    private fun track(
        id: Long,
        title: String,
        album: String,
        artist: String?,
        number: Int,
    ) = LibraryTrack(
        id = TrackId("track-$id"),
        uri = "/storage/emulated/0/Music/track-$id.mp3",
        title = title,
        artist = artist,
        album = album,
        trackNumber = number,
        duration = 3.minutes,
        folder = "Music/",
    )
}
