package io.github.puflik.plinth.ui.player

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.CoreTestFile
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.library.CoreLibraryRepository
import io.github.puflik.plinth.library.CoreUserDataRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.sort.TrackSort
import io.github.puflik.plinth.queue.FileQueueStore
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.ui.library.TrackActions
import io.github.puflik.plinth.ui.library.toQueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Р6: сердце у названия в развёрнутом плеере есть только у трека фонотеки —
 * его ищет `PlayerViewModel` по файлу очереди. Автор не нашёл, как поставить
 * лайк; здесь настоящее ядро на эмуляторе, без заглушек лайков: сердце видно у
 * трека, который заиграл из списка, и у трека очереди, восстановленной после
 * перезапуска (очередь пишет и читает настоящий `FileQueueStore`), и касание
 * его работает. Не прошло бы — меню плеера не выручило бы: лайк в нём стоит на
 * том же месте поиска трека.
 */
class PlayerHeartRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "player-heart-" + UUID.randomUUID())
    private lateinit var core: PlinthCore

    @Before
    fun setUp() {
        File(dir, "queue").mkdirs()
        core = PlinthCore(LogLevel.INFO, File(dir, "core"), CoreErrors())
        core.seedForTest(listOf(file(SONG, "Song"), file(OTHER, "Other")))
    }

    @After
    fun tearDown() {
        core.close()
        dir.deleteRecursively()
    }

    @Test
    fun a_queue_restored_after_a_restart_shows_the_heart_of_its_library_track() =
        runBlocking {
            val store = FileQueueStore(File(dir, "queue"), Dispatchers.IO)
            val item = QueueItem(AudioSource.LocalFile(SONG), "Song")
            val queue = PlaybackQueue.EMPTY.replace(QueueContext.Liked, listOf(item), start = 0)
            store.saveQueue(queue)

            // «Перезапуск»: новый контроллер получает то, что лежит на диске.
            val playback = newPlayback()
            val saved = checkNotNull(store.load())
            playback.restore(saved.queue, saved.position)
            val viewModel = viewModel(playback)

            assertThat(heart(viewModel)).isFalse()
            viewModel.onLike()
            assertThat(withTimeout(TIMEOUT) { viewModel.uiState.first { it.liked == true } }.liked).isTrue()
        }

    @Test
    fun a_track_started_from_the_real_list_shows_the_heart() =
        runBlocking {
            val tracks = CoreLibraryRepository(core, Dispatchers.IO).tracks(TrackSort.TITLE).first { it.size == 2 }
            val playback = newPlayback()
            val viewModel = viewModel(playback)

            playback.play(QueueContext.Tracks, tracks.map { it.toQueueItem() }, start = 1)

            assertThat(heart(viewModel)).isFalse()
        }

    private fun newPlayback() = PlaybackController(FakeAudioEngine(), CoroutineScope(Dispatchers.Unconfined))

    private fun viewModel(playback: PlaybackController): PlayerViewModel {
        val userData = CoreUserDataRepository(core, Dispatchers.IO)
        val actions = TrackActions(playback, userData, FakePlaylistRepository(), CoroutineScope(Dispatchers.Unconfined))
        return PlayerViewModel(playback, userData, actions)
    }

    /** Лайк текущего трека, когда плеер его нашёл; не нашёл за [TIMEOUT] — тест падает. */
    private suspend fun heart(viewModel: PlayerViewModel): Boolean? =
        withTimeout(TIMEOUT) { viewModel.uiState.first { it.liked != null } }.liked

    private fun file(
        uri: String,
        title: String,
    ) = CoreTestFile(uri = uri, folder = "Music/", title = title, artist = "Queen")

    private companion object {
        const val SONG = "/storage/emulated/0/Music/song.mp3"
        const val OTHER = "/storage/emulated/0/Music/other.mp3"
        val TIMEOUT = 5.seconds
    }
}
