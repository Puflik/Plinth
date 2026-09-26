package io.github.puflik.plinth.ui.library.playlists

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.PlaylistEntryId
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.PlaylistRepository
import io.github.puflik.plinth.library.TaggedFile
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Экран плейлиста (D4b): треки по порядку, игра с места касания, перестановка и удаление. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val library = FakeLibraryRepository()
    private val playlists = FakePlaylistRepository(library)
    private val engine = FakeAudioEngine()
    private val playback = PlaybackController(engine, CoroutineScope(Dispatchers.Unconfined))
    private val tracks = library.add(listOf(A, B, C, GONE))

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `name and tracks come from the playlist`() =
        runTest(main) {
            val (a, b) = tracks
            val mix = filled(a, b, a)
            val viewModel = watched(mix, name = "Mix")

            assertThat(viewModel.state.value.name).isEqualTo("Mix")
            assertThat(viewModel.titles()).containsExactly("a", "b", "a").inOrder()
            assertThat(viewModel.state.value.loaded).isTrue()
        }

    @Test
    fun `a renamed playlist shows its new name`() =
        runTest(main) {
            val mix = filled()
            val viewModel = watched(mix, name = "Mix")

            playlists.rename(mix, "Road")

            assertThat(viewModel.state.value.name).isEqualTo("Road")
        }

    /** Один трек стоит дважды: играет то место, которого коснулись, контекстом плейлиста. */
    @Test
    fun `a tapped row plays the playlist from its place`() =
        runTest(main) {
            val (a, b) = tracks
            val mix = filled(a, b, a)
            val viewModel = watched(mix, name = "Mix")

            viewModel.onTrack(index = 2, TrackAction.PLAY)

            assertThat(engine.preparedSources).containsExactly(AudioSource.LocalFile(a.uri))
            assertThat(playback.queue.value.context).isEqualTo(QueueContext.Playlist(mix, "Mix"))
            assertThat(playback.queue.value.upcoming).isEmpty()
        }

    /** Место — среди видимых строк: скрытый пропавший файл не сбивает перестановку. */
    @Test
    fun `a dropped row lands where it was dropped past a hidden file`() =
        runTest(main) {
            val (a, b, c) = tracks
            val gone = tracks.last()
            val mix = filled(a, gone, b, c)
            library.hide(listOf(GONE.path))
            val viewModel = watched(mix, name = "Mix")

            viewModel.move(from = 0, to = 1)

            assertThat(viewModel.titles()).containsExactly("b", "a", "c").inOrder()
            assertThat(viewModel.titles()).isEqualTo(stored(mix))
        }

    @Test
    fun `a removed row leaves only its place`() =
        runTest(main) {
            val (a, b) = tracks
            val mix = filled(a, b, a)
            val viewModel = watched(mix, name = "Mix")

            viewModel.remove(index = 0)

            assertThat(viewModel.titles()).containsExactly("b", "a").inOrder()
            assertThat(stored(mix)).containsExactly("b", "a").inOrder()
        }

    /** Строка остаётся там, куда её опустили, пока ядро пишет: список не прыгает назад. */
    @Test
    fun `a dropped row stays put while the core writes`() =
        runTest(main) {
            val (a, b, c) = tracks
            val mix = filled(a, b, c)
            val gated = Gated(playlists)
            val viewModel = watched(mix, name = "Mix", repository = gated)

            viewModel.move(from = 2, to = 0)
            val waiting = viewModel.titles()
            gated.gate.complete(Unit)

            assertThat(waiting).containsExactly("c", "a", "b").inOrder()
            assertThat(viewModel.titles()).containsExactly("c", "a", "b").inOrder()
        }

    /** Ядро не записало — через миг строка возвращается: экран не врёт о порядке. */
    @Test
    fun `a move the core never wrote goes back`() =
        runTest(main) {
            val (a, b) = tracks
            val mix = filled(a, b)
            val viewModel = watched(mix, name = "Mix", repository = Refusing(playlists))

            viewModel.move(from = 0, to = 1)
            val shown = viewModel.titles()
            advanceUntilIdle()

            assertThat(shown).containsExactly("b", "a").inOrder()
            assertThat(viewModel.titles()).containsExactly("a", "b").inOrder()
        }

    private suspend fun filled(vararg rows: LibraryTrack): PlaylistId {
        val mix = playlists.create("Mix")
        for (track in rows) playlists.add(mix, track.id)
        return mix
    }

    private fun TestScope.watched(
        playlist: PlaylistId,
        name: String,
        repository: PlaylistRepository = playlists,
    ): PlaylistViewModel {
        val actions = TrackActions(playback, FakeUserDataRepository(), repository, backgroundScope)
        val viewModel =
            PlaylistViewModel(SavedStateHandle(PlaylistViewModel.arguments(playlist, name)), repository, actions)
        backgroundScope.launch { viewModel.state.collect {} }
        return viewModel
    }

    private fun PlaylistViewModel.titles() = state.value.tracks.map { it.track.title }

    private suspend fun stored(playlist: PlaylistId) = playlists.tracks(playlist).first().map { it.track.title }

    /** Перестановка ждёт [gate] — как ядро, которое ещё пишет. */
    private class Gated(
        private val delegate: PlaylistRepository,
    ) : PlaylistRepository by delegate {
        val gate = CompletableDeferred<Unit>()

        override suspend fun move(
            playlist: PlaylistId,
            entry: PlaylistEntryId,
            to: Int,
        ) {
            gate.await()
            delegate.move(playlist, entry, to)
        }
    }

    /** Перестановку ядро не записало: отказ ушёл в `CoreErrors`, список прежний. */
    private class Refusing(
        delegate: PlaylistRepository,
    ) : PlaylistRepository by delegate {
        override suspend fun move(
            playlist: PlaylistId,
            entry: PlaylistEntryId,
            to: Int,
        ) = Unit
    }

    private companion object {
        val A = TaggedFile("/storage/emulated/0/Music/a.mp3")
        val B = TaggedFile("/storage/emulated/0/Music/b.mp3")
        val C = TaggedFile("/storage/emulated/0/Music/c.mp3")
        val GONE = TaggedFile("/storage/emulated/0/Music/gone.mp3")
    }
}
