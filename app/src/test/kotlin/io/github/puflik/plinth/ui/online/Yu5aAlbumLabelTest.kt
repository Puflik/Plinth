package io.github.puflik.plinth.ui.online

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.online.FakeOnlineRepository
import io.github.puflik.plinth.online.TestConcert
import io.github.puflik.plinth.ui.library.TrackActions
import io.github.puflik.plinth.ui.library.components.TrackLabel
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

/**
 * Значок источника в строках альбома провайдера (Ю5а): экран знает, чей альбом открыт, и называет провайдера в
 * строке каждого трека («From YouTube»), а не отвечает за всех «From Internet Archive».
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu5aAlbumLabelTest {
    private val online = FakeOnlineRepository().apply { runBlocking { setEnabled(true) } }
    private val userData = FakeUserDataRepository()
    private val playback = PlaybackController(FakeAudioEngine(), CoroutineScope(Dispatchers.Unconfined))
    private val actions =
        TrackActions(playback, userData, FakePlaylistRepository(), CoroutineScope(Dispatchers.Unconfined))
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
    fun `every row of an album names the provider of the album`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            val state = viewModel.uiState.value
            val labels = state.tracks.map { it.label }
            val providers = labels.map { it.provider }

            assertThat(labels).hasSize(2)
            assertThat(labels.map { it.online }).containsExactly(true, true)
            assertThat(providers).containsExactly(FakeOnlineRepository.PROVIDER, FakeOnlineRepository.PROVIDER)
        }

    @Test
    fun `a row of a library track does not know its provider`() {
        val track =
            LibraryTrack(id = TrackId("track-online"), uri = null, title = "Online", duration = null, folder = "")

        val label = TrackLabel.of(track)

        assertThat(label.online).isTrue()
        assertThat(label.provider).isNull()
    }
}
