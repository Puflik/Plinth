package io.github.puflik.plinth.ui.player

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.audio.engine.PlaybackError
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

/** Экран плеера первой вертикали: файл из SAF → звук (шаг 4). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val engine = FakeAudioEngine().apply { trackDuration = 4.minutes }
    private val viewModel by lazy { PlayerViewModel(PlaybackController(engine)) }

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
    fun `picked file plays with its name and length`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onFilePicked("content://plinth.test/song.flac", "song.flac")

            assertThat(engine.preparedSources).containsExactly(AudioSource.LocalFile("content://plinth.test/song.flac"))
            val state = viewModel.uiState.value
            assertThat(state.title).isEqualTo("song.flac")
            assertThat(state.isPlaying).isTrue()
            assertThat(state.canControl).isTrue()
            assertThat(state.duration).isEqualTo(4.minutes)
        }

    @Test
    fun `play pause and seek reach the engine`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.onFilePicked("content://plinth.test/song.flac", "song.flac")

            viewModel.onPlayPause()
            viewModel.onSeek(90.seconds)

            assertThat(viewModel.uiState.value.isPlaying).isFalse()
            assertThat(viewModel.uiState.value.position).isEqualTo(90.seconds)
        }

    @Test
    fun `unavailable file is reported and controls go off`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            engine.markUnavailable(AudioSource.LocalFile("content://plinth.test/gone.flac"))

            viewModel.onFilePicked("content://plinth.test/gone.flac", "gone.flac")

            val state = viewModel.uiState.value
            assertThat(state.error).isInstanceOf(PlaybackError.SourceUnavailable::class.java)
            assertThat(state.canControl).isFalse()
        }
}
