package io.github.puflik.plinth.ui.player

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.audio.engine.PlaybackError
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.queue.RepeatMode
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
import kotlin.time.Duration.Companion.seconds

/** Экран плеера: что открыли библиотека или SAF — звук, название, очередь. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val engine = FakeAudioEngine().apply { trackDuration = 4.minutes }
    private val playback = PlaybackController(engine, CoroutineScope(Dispatchers.Unconfined))
    private val viewModel by lazy { PlayerViewModel(playback) }
    private val album = QueueContext.Album("Jazz", "Queen")
    private val song = item("song", artist = "Queen")
    private val second = item("second")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is open at first`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            assertThat(viewModel.uiState.value).isEqualTo(PlayerUiState.EMPTY)
        }

    @Test
    fun `track opened elsewhere plays with its title, artist and length`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            playback.play(album, listOf(song, second), start = 0)

            val state = viewModel.uiState.value
            assertThat(state.title).isEqualTo("song")
            assertThat(state.artist).isEqualTo("Queen")
            assertThat(state.isPlaying).isTrue()
            assertThat(state.canControl).isTrue()
            assertThat(state.duration).isEqualTo(4.minutes)
            assertThat(state.upcoming).containsExactly(second)
        }

    @Test
    fun `play pause and seek reach the engine`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            playback.play(album, listOf(song), start = 0)

            viewModel.onPlayPause()
            viewModel.onSeek(90.seconds)

            assertThat(viewModel.uiState.value.isPlaying).isFalse()
            assertThat(viewModel.uiState.value.position).isEqualTo(90.seconds)
        }

    @Test
    fun `next previous shuffle and repeat reach the queue`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            playback.play(album, listOf(song, second), start = 0)

            viewModel.onNext()
            assertThat(viewModel.uiState.value.title).isEqualTo("second")
            viewModel.onPrevious()
            assertThat(viewModel.uiState.value.title).isEqualTo("song")

            viewModel.onShuffle()
            viewModel.onRepeat()
            assertThat(viewModel.uiState.value.shuffle).isTrue()
            assertThat(viewModel.uiState.value.repeat).isEqualTo(RepeatMode.ALL)
        }

    @Test
    fun `queue panel removes and moves upcoming tracks`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            val third = item("third")
            playback.play(album, listOf(song, second, third), start = 0)

            viewModel.onMoveUpcoming(from = 1, to = 0, item = third)
            assertThat(viewModel.uiState.value.upcoming).containsExactly(third, second).inOrder()

            viewModel.onRemoveUpcoming(index = 0, item = third)
            assertThat(viewModel.uiState.value.upcoming).containsExactly(second)
        }

    /** Мини-плеер виден, когда есть что показать, — и после восстановления на паузе тоже. */
    @Test
    fun `there is a track once the queue has one`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            assertThat(viewModel.uiState.value.hasTrack).isFalse()

            playback.restore(PlaybackQueue.EMPTY.play(album, listOf(song), start = 0), position = 30.seconds)

            assertThat(viewModel.uiState.value.hasTrack).isTrue()
            assertThat(viewModel.uiState.value.isPlaying).isFalse()
        }

    @Test
    fun `artwork comes from the current file and a stream has none`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            playback.play(album, listOf(song), start = 0)
            assertThat(viewModel.uiState.value.artworkUri).isEqualTo("content://plinth.test/song.flac")

            val stream = QueueItem(AudioSource.Remote("https://radio.example/live"), title = "Live")
            playback.play(QueueContext.File, listOf(stream), start = 0)
            assertThat(viewModel.uiState.value.artworkUri).isNull()
        }

    @Test
    fun `unavailable file is reported and controls go off`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            val gone = item("gone")
            engine.markUnavailable(gone.source)

            playback.play(QueueContext.File, listOf(gone), start = 0)

            val state = viewModel.uiState.value
            assertThat(state.error).isInstanceOf(PlaybackError.SourceUnavailable::class.java)
            assertThat(state.canControl).isFalse()
        }

    private fun item(
        name: String,
        artist: String? = null,
    ) = QueueItem(AudioSource.LocalFile("content://plinth.test/$name.flac"), title = name, artist = artist)
}
