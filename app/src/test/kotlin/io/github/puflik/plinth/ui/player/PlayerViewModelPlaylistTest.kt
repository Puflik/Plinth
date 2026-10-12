package io.github.puflik.plinth.ui.player

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.ui.library.TrackActions
import io.github.puflik.plinth.ui.library.toQueueItem
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

/**
 * Р6: «Добавить в плейлист…» в меню плеера. Плеер знает только то, что играет:
 * текущий трек фонотеки — по файлу очереди или по своему ID, если он сетевой.
 * Пишет `TrackActions` в журнал приложения, как и строка списка.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelPlaylistTest {
    private val playback = PlaybackController(FakeAudioEngine(), CoroutineScope(Dispatchers.Unconfined))
    private val library = FakeLibraryRepository()
    private val playlists = FakePlaylistRepository(library)
    private val userData = FakeUserDataRepository()
    private val actions = TrackActions(playback, userData, playlists, CoroutineScope(Dispatchers.Unconfined))
    private val viewModel by lazy { PlayerViewModel(playback, userData, actions) }
    private val context = QueueContext.Album("Jazz", "Queen")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the playing track goes to the chosen playlist`() =
        runTest(UnconfinedTestDispatcher()) {
            val song = libraryTrack("/music/song.mp3", "Song")
            val other = libraryTrack("/music/other.mp3", "Other")
            backgroundScope.launch { viewModel.uiState.collect {} }
            playback.play(context, listOf(song, other).map { it.toQueueItem() }, start = 1)
            val mix = playlists.create("Mix")

            viewModel.onAddToPlaylist(mix)

            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(other)
        }

    @Test
    fun `the playing track goes to a new playlist with a trimmed name`() =
        runTest(UnconfinedTestDispatcher()) {
            val song = libraryTrack("/music/song.mp3", "Song")
            backgroundScope.launch { viewModel.uiState.collect {} }
            playback.play(context, listOf(song.toQueueItem()), start = 0)

            viewModel.onAddToNewPlaylist("  Road  ")

            val road = playlists.playlists().first().single()
            assertThat(road.name).isEqualTo("Road")
            assertThat(playlists.tracks(road.id).first().map { it.track }).containsExactly(song)
        }

    @Test
    fun `a blank name makes no playlist`() =
        runTest(UnconfinedTestDispatcher()) {
            val song = libraryTrack("/music/song.mp3", "Song")
            backgroundScope.launch { viewModel.uiState.collect {} }
            playback.play(context, listOf(song.toQueueItem()), start = 0)

            viewModel.onAddToNewPlaylist("   ")

            assertThat(playlists.playlists().first()).isEmpty()
        }

    /** Сетевой трек (E3) — трек фонотеки без файла: в плейлист он идёт своим ID. */
    @Test
    fun `an online track goes to the playlist by its own id`() =
        runTest(UnconfinedTestDispatcher()) {
            val opening = LibraryTrack(TrackId("online-1"), uri = null, title = "Opening", duration = null, folder = "")
            library.upsert(listOf(opening))
            backgroundScope.launch { viewModel.uiState.collect {} }
            playback.play(context, listOf(QueueItem(AudioSource.Online("online-1"), "Opening")), start = 0)
            val mix = playlists.create("Mix")

            viewModel.onAddToPlaylist(mix)

            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(opening)
        }

    /** Файл не из фонотеки (SAF) в плейлисты не идёт и новых плейлистов не заводит. */
    @Test
    fun `a file outside the library goes to no playlist and makes none`() =
        runTest(UnconfinedTestDispatcher()) {
            val outside = QueueItem(AudioSource.LocalFile("/saf/outside.mp3"), "Outside")
            backgroundScope.launch { viewModel.uiState.collect {} }
            playback.play(context, listOf(outside), start = 0)
            val mix = playlists.create("Mix")

            viewModel.onAddToPlaylist(mix)
            viewModel.onAddToNewPlaylist("Road")

            assertThat(playlists.playlists().first().map { it.name }).containsExactly("Mix")
            assertThat(playlists.tracks(mix).first()).isEmpty()
        }

    @Test
    fun `with nothing playing no playlist is made`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onAddToNewPlaylist("Road")

            assertThat(playlists.playlists().first()).isEmpty()
        }

    /** В плейлист — не игра и не лайк: очередь и сердце остаются как были. */
    @Test
    fun `adding to a playlist leaves the queue and the like alone`() =
        runTest(UnconfinedTestDispatcher()) {
            val song = libraryTrack("/music/song.mp3", "Song")
            val other = libraryTrack("/music/other.mp3", "Other")
            backgroundScope.launch { viewModel.uiState.collect {} }
            playback.play(context, listOf(song, other).map { it.toQueueItem() }, start = 0)
            val mix = playlists.create("Mix")

            viewModel.onAddToPlaylist(mix)

            assertThat(viewModel.uiState.value.title).isEqualTo("Song")
            assertThat(viewModel.uiState.value.liked).isFalse()
            assertThat(playback.queue.value.upcoming).containsExactly(other.toQueueItem())
        }

    /** Трек фонотеки: файл знает хранилище лайков, а строку списка — фонотека; ID у них общий. */
    private fun libraryTrack(
        uri: String,
        title: String,
    ): LibraryTrack {
        val id = userData.add(uri).single()
        return LibraryTrack(id = id, uri = uri, title = title, duration = null, folder = "Music/")
            .also { library.upsert(listOf(it)) }
    }
}
