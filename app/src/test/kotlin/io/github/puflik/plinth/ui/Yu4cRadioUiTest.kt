package io.github.puflik.plinth.ui

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.audio.engine.PlaybackProgress
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.core.ErrorNotice
import io.github.puflik.plinth.core.ErrorPresenter
import io.github.puflik.plinth.core.RadioProblem
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.online.OnlineProviderIds
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.radio.RadioSeed
import io.github.puflik.plinth.radio.RecordingRadio
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import io.github.puflik.plinth.ui.library.act
import io.github.puflik.plinth.ui.library.toQueueItem
import io.github.puflik.plinth.ui.player.PlayerUiState
import io.github.puflik.plinth.ui.player.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Пункт «Радио» снаружи очереди (Ю4в): действие над треком списка и над играющим треком плеера доходит до запуска радио
 * с нужной затравкой, очередь сама по себе остаётся нетронутой, а вести о радио говорятся каждый раз.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu4cRadioUiTest {
    private val engine = FakeAudioEngine()
    private val playback = PlaybackController(engine, CoroutineScope(Dispatchers.Unconfined))
    private val userData = FakeUserDataRepository()
    private val radio = RecordingRadio()
    private val actions =
        TrackActions(playback, userData, FakePlaylistRepository(), CoroutineScope(Dispatchers.Unconfined), radio)
    private val track =
        LibraryTrack(
            id = TrackId("track-1"),
            uri = "/storage/emulated/0/Music/one.mp3",
            title = "One",
            artist = "Band",
            duration = 3.minutes,
            folder = "Music/",
        )
    private val online =
        LibraryTrack(TrackId("track-2"), uri = null, title = "Two", artist = "Band", duration = null, folder = "")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `only youtube is a radio provider`() {
        assertThat(OnlineProviderIds.RADIO).containsExactly(OnlineProviderIds.YOUTUBE)
    }

    @Test
    fun `radio is a menu action that neither starts the playback nor queues`() {
        assertThat(TrackAction.RADIO.startsPlayback).isFalse()
        assertThat(TrackAction.RADIO.queues).isFalse()
    }

    @Test
    fun `the queue itself does not take radio`() {
        playback.act(TrackAction.RADIO, QueueContext.Tracks, listOf(track), track)

        assertThat(playback.queue.value).isEqualTo(PlaybackQueue.EMPTY)
        assertThat(engine.preparedSources).isEmpty()
    }

    @Test
    fun `a track of a list starts the radio with itself as the seed`() {
        actions.act(TrackAction.RADIO, QueueContext.Tracks, listOf(track, online), track)
        actions.act(TrackAction.RADIO, QueueContext.Search("two"), listOf(online), online)

        assertThat(radio.seeds)
            .containsExactly(RadioSeed(track.toQueueItem(), track.id), RadioSeed(online.toQueueItem(), online.id))
            .inOrder()
        assertThat(playback.queue.value).isEqualTo(PlaybackQueue.EMPTY)
    }

    @Test
    fun `the player starts the radio from what it plays, with the catalog id when there is one`() {
        val (known) = userData.add("/storage/emulated/0/Music/one.mp3")
        val file = QueueItem(AudioSource.LocalFile("/storage/emulated/0/Music/one.mp3"), "One", "Band")
        val outside = QueueItem(AudioSource.LocalFile("content://picked/two.flac"), "Two")
        val net = QueueItem(AudioSource.Online("track-9"), "Nine", "Band")
        val stream = QueueItem(AudioSource.Remote("https://example.org/ten.mp3"), "Ten")

        listOf(file, outside, net, stream).forEach(actions::radio)

        assertThat(radio.seeds)
            .containsExactly(
                RadioSeed(file, known),
                RadioSeed(outside, null),
                RadioSeed(net, TrackId("track-9")),
                RadioSeed(stream, null),
            ).inOrder()
    }

    @Test
    fun `the player menu action takes the playing item and does nothing on an empty queue`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = PlayerViewModel(playback, userData, actions)
            val item = QueueItem(AudioSource.Online("track-9"), "Nine", "Band")

            viewModel.onRadio()
            assertThat(radio.seeds).isEmpty()

            playback.play(QueueContext.Tracks, listOf(item), start = 0)
            viewModel.onRadio()

            assertThat(radio.seeds).containsExactly(RadioSeed(item, TrackId("track-9")))
        }

    @Test
    fun `the player knows it is a radio and from what`() {
        val items = listOf(QueueItem(AudioSource.Online("a"), "A"), QueueItem(AudioSource.Online("b"), "B"))
        val radioQueue = PlaybackQueue.EMPTY.play(QueueContext.Radio("youtube", "seed", "Get Lucky"), items, start = 0)
        val albumQueue = PlaybackQueue.EMPTY.play(QueueContext.Album("Album", null), items, start = 0)

        val radioState = PlayerUiState.from(PlaybackState.Playing, PlaybackProgress.NONE, radioQueue)
        val albumState = PlayerUiState.from(PlaybackState.Playing, PlaybackProgress.NONE, albumQueue)
        assertThat(radioState.radio).isEqualTo("Get Lucky")
        assertThat(albumState.radio).isNull()
        assertThat(PlayerUiState.EMPTY.radio).isNull()
    }

    @Test
    fun `every radio news reaches the person every time`() {
        val presenter = ErrorPresenter()

        for (problem in RadioProblem.entries) {
            val first = presenter.present(AppError.Radio(problem))
            val second = presenter.present(AppError.Radio(problem))

            assertThat(first).isEqualTo(ErrorNotice.Radio(problem))
            assertThat(second).isEqualTo(ErrorNotice.Radio(problem))
        }
        assertThat(RadioProblem.entries.map { it.name })
            .containsExactly("NEEDS_YOUTUBE", "NO_NETWORK", "NOT_FOUND", "STOPPED")
    }
}
