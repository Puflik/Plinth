package io.github.puflik.plinth.ui.library.playlists

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.TaggedFile
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** «Любимое» и «Недавнее» (D4b): списки фонотеки, играют своим контекстом. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoPlaylistViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val library = FakeLibraryRepository()
    private val engine = FakeAudioEngine()
    private val playback = PlaybackController(engine, CoroutineScope(Dispatchers.Unconfined))
    private val actions =
        TrackActions(
            playback,
            FakeUserDataRepository(),
            FakePlaylistRepository(),
            CoroutineScope(Dispatchers.Unconfined),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        library.add(listOf(SONG, OTHER, NEVER).map(::TaggedFile))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `liked shows the liked tracks and plays them as liked`() =
        runTest(main) {
            library.like(listOf(OTHER, SONG))
            val viewModel = watched(AutoPlaylist.LIKED)

            viewModel.onTrack(viewModel.tracks.value!!.first(), TrackAction.PLAY)

            assertThat(viewModel.kind).isEqualTo(AutoPlaylist.LIKED)
            assertThat(viewModel.tracks.value?.map { it.title }).containsExactly("other", "song").inOrder()
            assertThat(playback.queue.value.context).isEqualTo(QueueContext.Liked)
        }

    @Test
    fun `recent shows the last played first and plays them as recent`() =
        runTest(main) {
            library.play(SONG, times = 1)
            library.play(OTHER, times = 1)
            val viewModel = watched(AutoPlaylist.RECENT)

            viewModel.onTrack(viewModel.tracks.value!!.first(), TrackAction.PLAY)

            assertThat(viewModel.tracks.value?.map { it.title }).containsExactly("other", "song").inOrder()
            assertThat(playback.queue.value.context).isEqualTo(QueueContext.Recent)
        }

    private fun TestScope.watched(kind: AutoPlaylist): AutoPlaylistViewModel {
        val viewModel = AutoPlaylistViewModel(SavedStateHandle(AutoPlaylistViewModel.arguments(kind)), library, actions)
        backgroundScope.launch { viewModel.tracks.collect {} }
        return viewModel
    }

    private companion object {
        const val SONG = "/storage/emulated/0/Music/song.mp3"
        const val OTHER = "/storage/emulated/0/Music/other.mp3"
        const val NEVER = "/storage/emulated/0/Music/never.mp3"
    }
}
