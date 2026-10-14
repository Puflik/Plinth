package io.github.puflik.plinth.ui.player

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackActions
import io.github.puflik.plinth.ui.library.toQueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
 * Р6, ревью, круг 1: «В плейлист…» из меню плеера добавляет тот трек, для которого
 * открыли выбор плейлиста, — даже если очередь за это время ушла дальше. В диалоге не
 * видно, какой трек добавляется, и подмена осталась бы незамеченной. Выбор закрыли —
 * запомненный трек забыт, и следующее «В плейлист…» берёт играющий.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerPlaylistTargetTest {
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
    fun `the track the picker was opened on goes to the chosen playlist after the queue moved on`() =
        runTest(UnconfinedTestDispatcher()) {
            val (first, _) = playTwo()
            viewModel.onPlaylistPickerShown()
            viewModel.onNext()
            val mix = playlists.create("Mix")

            viewModel.onAddToPlaylist(mix)

            assertThat(viewModel.uiState.value.title).isEqualTo("Second")
            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(first)
        }

    @Test
    fun `the track the picker was opened on goes to a new playlist after the queue moved on`() =
        runTest(UnconfinedTestDispatcher()) {
            val (first, _) = playTwo()
            viewModel.onPlaylistPickerShown()
            viewModel.onNext()

            viewModel.onAddToNewPlaylist("Road")

            assertThat(viewModel.uiState.value.title).isEqualTo("Second")
            val road = playlists.playlists().first().single()
            assertThat(playlists.tracks(road.id).first().map { it.track }).containsExactly(first)
        }

    /** Выбор закрыт без выбора — следующий раз берётся тот трек, что играет. */
    @Test
    fun `a closed picker lets go of the track`() =
        runTest(UnconfinedTestDispatcher()) {
            val (_, second) = playTwo()
            viewModel.onPlaylistPickerShown()
            viewModel.onNext()
            viewModel.onPlaylistPickerClosed()
            val mix = playlists.create("Mix")

            viewModel.onAddToPlaylist(mix)

            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(second)
        }

    /** Выбор открыт на втором треке: первый, сыгранный раньше, в плейлист не попадает. */
    @Test
    fun `the picker pins the track that plays when it opens, not an earlier one`() =
        runTest(UnconfinedTestDispatcher()) {
            val (_, second) = playTwo()
            viewModel.onNext()
            viewModel.onPlaylistPickerShown()
            val mix = playlists.create("Mix")

            viewModel.onAddToPlaylist(mix)

            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(second)
        }

    /** Два трека фонотеки в очереди, играет первый; модель подписана на состояние. */
    private fun TestScope.playTwo(): Pair<LibraryTrack, LibraryTrack> {
        val first = libraryTrack("/music/first.mp3", "First")
        val second = libraryTrack("/music/second.mp3", "Second")
        backgroundScope.launch { viewModel.uiState.collect {} }
        playback.play(context, listOf(first, second).map { it.toQueueItem() }, start = 0)
        return first to second
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
