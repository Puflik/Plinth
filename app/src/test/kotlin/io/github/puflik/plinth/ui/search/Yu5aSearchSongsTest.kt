package io.github.puflik.plinth.ui.search

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.PlaylistRepository
import io.github.puflik.plinth.online.FakeOnlineSettings
import io.github.puflik.plinth.online.TestSong
import io.github.puflik.plinth.online.TestYoutubeOnline
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.radio.RecordingRadio
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Песня YouTube в секции поиска (Ю5а): строка трека, а не «альбом из одного трека». Любое действие над песней
 * начинается с `resolve` (в поиске у песни нет вариантов — каталог узнаёт трек по ним) и заводит в каталог только
 * её; что случилось, экран узнаёт по [SearchViewModel.songEvents], что показать в строке — по `uiState.songs`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu5aSearchSongsTest {
    private val online = TestYoutubeOnline()
    private val engine = FakeAudioEngine()
    private val playback = PlaybackController(engine, CoroutineScope(Dispatchers.Unconfined))
    private val userData = FakeUserDataRepository()
    private val playlists = RecordingPlaylists(FakePlaylistRepository())
    private val radio = RecordingRadio()
    private val actions =
        TrackActions(playback, userData, playlists, CoroutineScope(Dispatchers.Unconfined), radio)
    private val viewModel by lazy { SearchViewModel(FakeLibraryRepository(), online, FakeOnlineSettings(), actions) }
    private val events = mutableListOf<SongEvent>()
    private val key = songKey(TestSong.PROVIDER, TestSong.result)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Как экран: подписка на состояние и на события. */
    private fun TestScope.watch() {
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch { viewModel.songEvents.toList(events) }
    }

    /** Строка песни в состоянии; песни, с которой не работали, там нет — это «ничего не известно». */
    private val song: SongState get() = viewModel.uiState.value.songs[key] ?: SongState()

    private suspend fun idOfSong(): TrackId =
        checkNotNull(online.known(TestSong.PROVIDER, listOf(TestSong.track)).single()) { "песни нет в каталоге" }

    private fun act(action: TrackAction) = viewModel.onSong(TestSong.PROVIDER, TestSong.result, action)

    @Test
    fun `a song nobody worked with has no state`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()

            assertThat(viewModel.uiState.value.songs).isEmpty()
            assertThat(song).isEqualTo(SongState(liked = false, busy = false))
            assertThat(online.albumCalls).isEmpty()
        }

    @Test
    fun `a tapped song goes to the catalog and plays alone as the search`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()
            viewModel.onQuery("lucky")

            act(TrackAction.PLAY)

            val id = idOfSong()
            assertThat(engine.preparedSources).containsExactly(AudioSource.Online(id.value))
            assertThat(playback.queue.value.context).isEqualTo(QueueContext.Search("lucky"))
            assertThat(
                playback.queue.value.contextItems
                    .map { it.title },
            ).containsExactly("Lucky")
            assertThat(events).containsExactly(SongEvent.Done(TrackAction.PLAY))
        }

    @Test
    fun `a replaced queue plays the song and the event says what was done`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()

            act(TrackAction.REPLACE_QUEUE)

            assertThat(engine.preparedSources).hasSize(1)
            assertThat(events).containsExactly(SongEvent.Done(TrackAction.REPLACE_QUEUE))
        }

    @Test
    fun `the provider is asked for a song once however many things are done with it`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()

            act(TrackAction.PLAY)
            act(TrackAction.LIKE)
            act(TrackAction.ADD_TO_QUEUE)

            assertThat(online.albumCalls).containsExactly(TestSong.VIDEO)
        }

    @Test
    fun `a like adds only that song and its heart shows at once`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()

            act(TrackAction.LIKE)

            val id = idOfSong()
            assertThat(userData.liked(id).first()).isTrue()
            assertThat(song.liked).isTrue()
            assertThat(engine.preparedSources).isEmpty()
            assertThat(playback.queue.value).isEqualTo(PlaybackQueue.EMPTY)
            assertThat(events).containsExactly(SongEvent.Done(TrackAction.LIKE))

            act(TrackAction.UNLIKE)

            assertThat(userData.liked(id).first()).isFalse()
            assertThat(song.liked).isFalse()
        }

    @Test
    fun `the heart follows the journal once the song is known`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()
            act(TrackAction.LIKE)

            userData.setLiked(idOfSong(), liked = false)

            assertThat(song.liked).isFalse()
        }

    @Test
    fun `a song goes to a playlist through the catalog`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()
            val playlist = PlaylistId("playlist-road")

            viewModel.onSongToPlaylist(TestSong.PROVIDER, TestSong.result, playlist)

            assertThat(playlists.added).containsExactly(playlist to idOfSong())
            assertThat(engine.preparedSources).isEmpty()
        }

    @Test
    fun `a song goes to a new playlist through the catalog`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()

            viewModel.onSongToNewPlaylist(TestSong.PROVIDER, TestSong.result, "Road")

            assertThat(playlists.created).containsExactly("Road")
            assertThat(playlists.added.single().second).isEqualTo(idOfSong())
        }

    @Test
    fun `a song queued while another plays joins the manual block`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()
            act(TrackAction.PLAY)

            act(TrackAction.ADD_TO_QUEUE)

            assertThat(
                playback.queue.value.upcoming
                    .map { it.title },
            ).containsExactly("Lucky")
            assertThat(events)
                .containsExactly(SongEvent.Done(TrackAction.PLAY), SongEvent.Done(TrackAction.ADD_TO_QUEUE))
                .inOrder()
        }

    @Test
    fun `radio from a song starts with the song as the seed and its catalog id`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()

            act(TrackAction.RADIO)

            val id = idOfSong()
            val seed = radio.seeds.single()
            assertThat(seed.track).isEqualTo(id)
            assertThat(seed.item.source).isEqualTo(AudioSource.Online(id.value))
            assertThat(seed.item.title).isEqualTo("Lucky")
            assertThat(playback.queue.value).isEqualTo(PlaybackQueue.EMPTY)
            assertThat(events).containsExactly(SongEvent.Done(TrackAction.RADIO))
        }

    @Test
    fun `a song being fetched shows busy and ignores another tap`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()
            val gate = CompletableDeferred<Unit>()
            online.gate = gate

            act(TrackAction.PLAY)
            val whileWaiting = song
            act(TrackAction.PLAY)
            val calls = online.albumCalls.size
            val prepared = engine.preparedSources.size
            gate.complete(Unit)

            assertThat(whileWaiting.busy).isTrue()
            assertThat(calls).isEqualTo(1)
            assertThat(prepared).isEqualTo(0)
            assertThat(song.busy).isFalse()
            assertThat(engine.preparedSources).hasSize(1)
            assertThat(events).containsExactly(SongEvent.Done(TrackAction.PLAY))
        }

    @Test
    fun `without network nothing is added and the person is told`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()
            online.networkUp = false

            act(TrackAction.PLAY)

            assertThat(events).containsExactly(SongEvent.Failed(TestSong.PROVIDER, OnlineProblem.NO_NETWORK))
            assertThat(online.known(TestSong.PROVIDER, listOf(TestSong.track)).single()).isNull()
            assertThat(engine.preparedSources).isEmpty()
            assertThat(song.busy).isFalse()

            online.networkUp = true
            act(TrackAction.PLAY)

            assertThat(engine.preparedSources).hasSize(1)
            assertThat(online.albumCalls).hasSize(2)
        }

    @Test
    fun `an answer without a track is a broken answer`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()
            online.emptyAnswer = true

            act(TrackAction.LIKE)

            assertThat(events).containsExactly(SongEvent.Failed(TestSong.PROVIDER, OnlineProblem.BROKEN))
            assertThat(online.known(TestSong.PROVIDER, listOf(TestSong.track)).single()).isNull()
            assertThat(song.busy).isFalse()
        }

    @Test
    fun `an album is not a song`() =
        runTest(UnconfinedTestDispatcher()) {
            watch()

            viewModel.onSong(TestSong.PROVIDER, TestSong.albumResult, TrackAction.PLAY)

            assertThat(online.albumCalls).isEmpty()
            assertThat(events).isEmpty()
            assertThat(playback.queue.value).isEqualTo(PlaybackQueue.EMPTY)
            assertThat(viewModel.uiState.value.songs).isEmpty()
        }

    @Test
    fun `a song key tells songs of different providers apart`() {
        val other = TestSong.result.copy(external = "othervideo1")

        assertThat(songKey(TestSong.PROVIDER, TestSong.result)).isNotEqualTo(songKey(TestSong.PROVIDER, other))
        assertThat(songKey("youtube", TestSong.result)).isNotEqualTo(songKey("archive.org", TestSong.result))
    }

    /** Запоминает, что куда добавили и какие плейлисты создали. */
    private class RecordingPlaylists(
        private val delegate: PlaylistRepository,
    ) : PlaylistRepository by delegate {
        val added = mutableListOf<Pair<PlaylistId, TrackId>>()
        val created = mutableListOf<String>()

        override suspend fun create(name: String): PlaylistId? {
            created += name
            return delegate.create(name)
        }

        override suspend fun add(
            playlist: PlaylistId,
            track: TrackId,
        ) {
            added += playlist to track
            delegate.add(playlist, track)
        }
    }
}
