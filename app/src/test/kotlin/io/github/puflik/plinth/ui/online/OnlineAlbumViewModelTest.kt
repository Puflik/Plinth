package io.github.puflik.plinth.ui.online

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.PlaylistRepository
import io.github.puflik.plinth.online.FakeOnlineRepository
import io.github.puflik.plinth.online.TestConcert
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Альбом провайдера (E3b, ответ автора): треки из сети, «Играть», касание —
 * трек и остальные треки альбома в очередь, меню — как у локального. В
 * каталог трек попадает при действии, не при показе.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineAlbumViewModelTest {
    private val online = FakeOnlineRepository().apply { runBlocking { setEnabled(true) } }
    private val engine = FakeAudioEngine()
    private val playback = PlaybackController(engine, CoroutineScope(Dispatchers.Unconfined))
    private val userData = FakeUserDataRepository()
    private val playlists = RecordingPlaylists(FakePlaylistRepository())
    private val actions = TrackActions(playback, userData, playlists, CoroutineScope(Dispatchers.Unconfined))
    private val viewModel by lazy {
        OnlineAlbumViewModel(SavedStateHandle(OnlineAlbumViewModel.arguments(ALBUM)), online, userData, actions)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the album comes from the search result and its tracks from the network`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            assertThat(viewModel.album).isEqualTo(ALBUM)
            val state = viewModel.uiState.value
            assertThat(state.loading).isFalse()
            assertThat(state.tracks.map { it.label.title }).containsExactly("Opening", "Closing").inOrder()
            assertThat(state.tracks.map { it.label.online }).containsExactly(true, true)
            assertThat(online.known(ALBUM.provider, TestConcert.tracks)).containsExactly(null, null)
        }

    @Test
    fun `a track liked before shows its like`() =
        runTest(UnconfinedTestDispatcher()) {
            val closing = checkNotNull(online.add(ALBUM.provider, TestConcert.tracks.drop(1))).single()
            userData.setLiked(closing, true)
            backgroundScope.launch { viewModel.uiState.collect {} }

            assertThat(
                viewModel.uiState.value.tracks
                    .map { it.label.liked },
            ).containsExactly(false, true).inOrder()
        }

    @Test
    fun `a tapped track plays with the rest of the album and they go to the catalog`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onTrack(1, TrackAction.PLAY)

            val ids = online.known(ALBUM.provider, TestConcert.tracks)
            assertThat(ids).doesNotContain(null)
            assertThat(engine.preparedSources).containsExactly(AudioSource.Online(checkNotNull(ids[1]).value))
            assertThat(playback.queue.value.context).isEqualTo(QueueContext.Album(ALBUM.title, ALBUM.artist))
            assertThat(
                playback.queue.value.contextItems
                    .map { it.title },
            ).containsExactly("Opening", "Closing").inOrder()
        }

    @Test
    fun `play starts the album from its first track`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onPlay()

            assertThat(
                playback.queue.value.current
                    ?.title,
            ).isEqualTo("Opening")
        }

    @Test
    fun `a like adds only that track and shows at once`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onTrack(0, TrackAction.LIKE)

            val ids = online.known(ALBUM.provider, TestConcert.tracks)
            assertThat(ids[1]).isNull()
            assertThat(userData.liked(checkNotNull(ids[0])).first()).isTrue()
            assertThat(
                viewModel.uiState.value.tracks
                    .map { it.label.liked },
            ).containsExactly(true, false).inOrder()
        }

    @Test
    fun `a track goes to a playlist through the catalog`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            val playlist = PlaylistId("playlist-road")

            viewModel.onAddToPlaylist(1, playlist)

            val id = online.known(ALBUM.provider, TestConcert.tracks)[1]
            assertThat(playlists.added).containsExactly(playlist to id)
        }

    @Test
    fun `without network the album says so and opens on retry`() =
        runTest(UnconfinedTestDispatcher()) {
            online.networkUp = false
            backgroundScope.launch { viewModel.uiState.collect {} }
            val offline = viewModel.uiState.value

            online.networkUp = true
            viewModel.onRetry()

            assertThat(offline.problem).isEqualTo(OnlineProblem.NO_NETWORK)
            assertThat(offline.tracks).isEmpty()
            assertThat(viewModel.uiState.value.problem).isNull()
            assertThat(viewModel.uiState.value.tracks).hasSize(2)
        }

    /** Запоминает, что куда добавили. */
    private class RecordingPlaylists(
        private val delegate: PlaylistRepository,
    ) : PlaylistRepository by delegate {
        val added = mutableListOf<Pair<PlaylistId, TrackId>>()

        override suspend fun add(
            playlist: PlaylistId,
            track: TrackId,
        ) {
            added += playlist to track
            delegate.add(playlist, track)
        }
    }

    private companion object {
        val ALBUM =
            OnlineAlbumHeader(
                provider = FakeOnlineRepository.PROVIDER,
                item = TestConcert.ITEM,
                title = TestConcert.TITLE,
                artist = TestConcert.ARTIST,
                year = TestConcert.YEAR,
            )
    }
}
