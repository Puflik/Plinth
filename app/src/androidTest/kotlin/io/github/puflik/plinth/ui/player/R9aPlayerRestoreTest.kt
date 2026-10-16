package io.github.puflik.plinth.ui.player

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.CoreTestFile
import io.github.puflik.plinth.ffi.MirrorFile
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.library.CorePlaylistRepository
import io.github.puflik.plinth.library.CoreUserDataRepository
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Р9а (ревью R1, №1) на настоящем ядре: сердце и «В плейлист…» из плеера, когда во время
 * игры нажали «Восстановить». Новая установка отсканировала файл — у песни свой ID, по
 * нему плеер её и узнал; «Восстановить» вливает копию журнала, перепривязка переводит песню
 * на ID журнала, и прежнего ID в каталоге больше нет. Лайк и добавление в плейлист на него
 * ложатся в журнал, но «Любимое» и плейлист их не видят.
 *
 * Скан изображает `seedForTest`, как в `ReinstallRestoreTest`; копия журнала прошлой
 * установки — настоящая (`mirror.copy`). Та же картина без ядра — `R9aPlayerRelinkTest`.
 */
class R9aPlayerRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "r9a-player-" + UUID.randomUUID())
    private val cores = mutableListOf<PlinthCore>()

    @After
    fun tearDown() {
        cores.forEach(PlinthCore::close)
        dir.deleteRecursively()
    }

    /** В копии песня с лайком: «Восстановить» возвращает его — сердце показывает, касание снимает. */
    @Test
    fun the_heart_of_a_playing_song_follows_a_restore() =
        runBlocking<Unit> {
            val copy = copyOfOldInstallation(liked = true)
            val core = newInstallation()
            val viewModel = playingIn(core)
            assertThat(heart(viewModel)).isFalse()

            core.mirror.restore(listOf(copy))

            assertThat(awaitHeart(viewModel, liked = true)).isTrue()
            viewModel.onLike()
            assertThat(awaitHeart(viewModel, liked = false)).isFalse()
            assertThat(core.library.likedTracks()).isEmpty()
        }

    /** В копии песня только в плейлисте: лайк из плеера после «Восстановить» ложится на песню каталога. */
    @Test
    fun a_like_from_the_player_after_a_restore_lands_on_the_song_the_catalog_has_now() =
        runBlocking<Unit> {
            val copy = copyOfOldInstallation(liked = false)
            val core = newInstallation()
            val viewModel = playingIn(core)
            assertThat(heart(viewModel)).isFalse()

            core.mirror.restore(listOf(copy))
            viewModel.onLike()

            assertThat(awaitHeart(viewModel, liked = true)).isTrue()
            assertThat(core.library.likedTracks().map { it.title }).containsExactly(SONG_TITLE)
        }

    /** Выбор плейлиста открыт до «Восстановить», запись — после: она идёт на песню каталога. */
    @Test
    fun the_picker_of_the_player_keeps_up_with_a_restore() =
        runBlocking<Unit> {
            val copy = copyOfOldInstallation(liked = false)
            val core = newInstallation()
            val mix = core.journal.createPlaylist("Mix").id
            val viewModel = playingIn(core)
            assertThat(heart(viewModel)).isFalse()
            viewModel.onPlaylistPickerShown()

            core.mirror.restore(listOf(copy))
            viewModel.onAddToPlaylist(mix)

            val tracks = eventually { core.library.playlistTracks(mix) }
            assertThat(tracks.map { it.track.title }).containsExactly(SONG_TITLE)
        }

    /** Прошлая установка: песня с лайком ([liked]) или только в плейлисте «Дорога»; копия её журнала. */
    private fun copyOfOldInstallation(liked: Boolean): MirrorFile {
        val old = install()
        old.seedForTest(listOf(SONG_FILE))
        val song = checkNotNull(old.library.trackAt(SONG))
        if (liked) {
            old.journal.like(song)
        } else {
            old.journal.addToPlaylist(old.journal.createPlaylist("Дорога").id, song)
        }
        return old.mirror.copy()
    }

    /** Новая установка: скан дал песне новый ID. */
    private fun newInstallation(): PlinthCore = install().also { it.seedForTest(listOf(SONG_FILE)) }

    private fun install(): PlinthCore =
        PlinthCore(LogLevel.INFO, File(dir, "core-${cores.size}"), CoreErrors()).also { cores += it }

    /** Плеер на ядре [core]; песня уже играет. */
    private fun playingIn(core: PlinthCore): PlayerViewModel {
        val playback = PlaybackController(FakeAudioEngine(), CoroutineScope(Dispatchers.Unconfined))
        val userData = CoreUserDataRepository(core, Dispatchers.IO)
        val playlists = CorePlaylistRepository(core, Dispatchers.IO)
        val actions = TrackActions(playback, userData, playlists, CoroutineScope(Dispatchers.Unconfined))
        playback.play(QueueContext.Tracks, listOf(QueueItem(AudioSource.LocalFile(SONG), SONG_TITLE)), start = 0)
        return PlayerViewModel(playback, userData, actions)
    }

    /** Сердце, когда плеер нашёл трек; не нашёл за [TIMEOUT] — тест падает. */
    private suspend fun heart(viewModel: PlayerViewModel): Boolean? =
        withTimeout(TIMEOUT) { viewModel.uiState.first { it.liked != null } }.liked

    private suspend fun awaitHeart(
        viewModel: PlayerViewModel,
        liked: Boolean,
    ): Boolean? = withTimeout(TIMEOUT) { viewModel.uiState.first { it.liked == liked } }.liked

    /** Запись из плеера идёт в фоне приложения: ждём, пока [read] что-нибудь вернёт. */
    private suspend fun <T> eventually(read: () -> List<T>): List<T> =
        withTimeout(TIMEOUT) {
            var found = read()
            while (found.isEmpty()) {
                delay(POLL_MS)
                found = read()
            }
            found
        }

    private companion object {
        const val SONG = "/storage/emulated/0/Music/song.mp3"
        const val SONG_TITLE = "Song"
        const val POLL_MS = 50L
        val TIMEOUT = 5.seconds
        val SONG_FILE = CoreTestFile(uri = SONG, folder = "Music/", title = SONG_TITLE, artist = "Queen")
    }
}
