package io.github.puflik.plinth.ui.online

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.online.FakeOnlineRepository
import io.github.puflik.plinth.online.TestConcert
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.radio.RecordingRadio
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** «Радио» в строке альбома провайдера (Ю4в): в каталог идёт только этот трек, радио начинается от него. */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu4cRadioAlbumRowTest {
    private val online = FakeOnlineRepository().apply { runBlocking { setEnabled(true) } }
    private val playback = PlaybackController(FakeAudioEngine(), CoroutineScope(Dispatchers.Unconfined))
    private val userData = FakeUserDataRepository()
    private val radio = RecordingRadio()
    private val actions =
        TrackActions(playback, userData, FakePlaylistRepository(), CoroutineScope(Dispatchers.Unconfined), radio)
    private val album =
        OnlineAlbumHeader(
            provider = FakeOnlineRepository.PROVIDER,
            item = TestConcert.ITEM,
            title = TestConcert.TITLE,
            artist = TestConcert.ARTIST,
            year = TestConcert.YEAR,
        )
    private val viewModel by lazy {
        OnlineAlbumViewModel(SavedStateHandle(OnlineAlbumViewModel.arguments(album)), online, userData, actions)
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
    fun `radio from a row takes only that track into the catalog and starts from it`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onTrack(1, TrackAction.RADIO)

            val ids = online.known(album.provider, TestConcert.tracks)
            assertThat(ids[0]).isNull()
            val closing = checkNotNull(ids[1])
            val seed = radio.seeds.single()
            assertThat(seed.track).isEqualTo(closing)
            assertThat(seed.item.source).isEqualTo(AudioSource.Online(closing.value))
            assertThat(seed.item.title).isEqualTo("Closing")
            assertThat(playback.queue.value).isEqualTo(PlaybackQueue.EMPTY)
        }
}
