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
import io.github.puflik.plinth.queue.PlaybackQueue
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
import kotlin.time.Duration.Companion.seconds

/**
 * Р6: сердце у названия в развёрнутом плеере (D4a) есть у любого трека
 * фонотеки, как бы он ни попал в очередь, — со списка, из сети или из очереди,
 * восстановленной после перезапуска. Автор в плеере его не нашёл; проверено
 * по каждому пути, а на настоящем ядре — `PlayerHeartRestoreTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerHeartTest {
    private val playback = PlaybackController(FakeAudioEngine(), CoroutineScope(Dispatchers.Unconfined))
    private val library = FakeLibraryRepository()
    private val userData = FakeUserDataRepository()
    private val actions =
        TrackActions(playback, userData, FakePlaylistRepository(library), CoroutineScope(Dispatchers.Unconfined))
    private val viewModel by lazy { PlayerViewModel(playback, userData, actions) }
    private val context = QueueContext.Folder("Music/")
    private val song = libraryTrack("/storage/emulated/0/Music/song.mp3", "Song")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a library file played from a list shows the heart`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            playback.play(context, listOf(song.toQueueItem()), start = 0)

            assertThat(viewModel.uiState.value.liked).isFalse()
        }

    @Test
    fun `an online library track shows the heart`() =
        runTest(UnconfinedTestDispatcher()) {
            val opening = LibraryTrack(TrackId("online-1"), uri = null, title = "Opening", duration = null, folder = "")
            library.upsert(listOf(opening))
            backgroundScope.launch { viewModel.uiState.collect {} }

            playback.play(context, listOf(opening.toQueueItem()), start = 0)

            assertThat(opening.toQueueItem().source).isEqualTo(AudioSource.Online("online-1"))
            assertThat(viewModel.uiState.value.liked).isFalse()
        }

    /** Очередь, которую вернул `QueueKeeper` при старте: на паузе, на той же секунде. */
    @Test
    fun `a restored queue shows the heart and the heart works`() =
        runTest(UnconfinedTestDispatcher()) {
            val queue = listOf(song.toQueueItem(), item("/music/other.mp3"))
            val saved = PlaybackQueue.EMPTY.replace(context, queue, start = 0)
            playback.restore(saved, position = 42.seconds)
            backgroundScope.launch { viewModel.uiState.collect {} }

            val before = viewModel.uiState.value.liked
            viewModel.onLike()

            assertThat(before).isFalse()
            assertThat(viewModel.uiState.value.liked).isTrue()
            assertThat(userData.liked(song.id).first()).isTrue()
        }

    private fun item(uri: String) = QueueItem(AudioSource.LocalFile(uri), "Other")

    private fun libraryTrack(
        uri: String,
        title: String,
    ): LibraryTrack {
        val id = userData.add(uri).single()
        return LibraryTrack(id = id, uri = uri, title = title, duration = null, folder = "Music/")
            .also { library.upsert(listOf(it)) }
    }
}
