package io.github.puflik.plinth.ui.player

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.GatedUserData
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackActions
import io.github.puflik.plinth.ui.library.toQueueItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
 * Р9а (ревью R1, №1): лайк и «В плейлист…» из плеера после перепривязки. Скан после
 * переустановки и «Восстановить» переводят трек под тем же путём на ID журнала
 * (C4, `rekey_track`), а плеер узнавал трек по пути один раз, при смене источника, —
 * и писал на ID, которого в каталоге уже нет: сердце закрашено, в «Любимом» песни нет.
 *
 * Перепривязку здесь изображает `FakeUserDataRepository.rekey`: путь тот же, ID другой, и
 * фонотека объявила перемену, как `PlinthCore.catalogChanged()` после скана. На настоящем
 * ядре — `R9aPlayerRestoreTest` (эмулятор).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R9aPlayerRelinkTest {
    private val playback = PlaybackController(FakeAudioEngine(), CoroutineScope(Dispatchers.Unconfined))
    private val library = FakeLibraryRepository()
    private val userData = FakeUserDataRepository()
    private val playlists = FakePlaylistRepository(library)
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

    /** Журнал помнит лайк под ID прежней установки; скан дал песне новый ID, перепривязка вернула старый. */
    @Test
    fun `the heart follows the song to the id the relink gave it`() =
        runTest(UnconfinedTestDispatcher()) {
            val song = libraryTrack(SONG, "Song")
            userData.setLiked(JOURNAL_ID, true)
            play(song)
            val before = viewModel.uiState.value.liked

            relink(song)

            assertThat(before).isFalse()
            assertThat(viewModel.uiState.value.liked).isTrue()
        }

    /** С прежним ID сердце «закрашено», а снять лайк было нечем: касание писало на новый ID. */
    @Test
    fun `a like comes off the song under the id the relink gave it`() =
        runTest(UnconfinedTestDispatcher()) {
            val song = libraryTrack(SONG, "Song")
            userData.setLiked(JOURNAL_ID, true)
            play(song)
            relink(song)

            viewModel.onLike()

            assertThat(userData.liked(JOURNAL_ID).first()).isFalse()
            assertThat(viewModel.uiState.value.liked).isFalse()
        }

    @Test
    fun `a like after the relink lands on the song the catalog has now`() =
        runTest(UnconfinedTestDispatcher()) {
            val song = libraryTrack(SONG, "Song")
            play(song)
            relink(song)

            viewModel.onLike()

            assertThat(userData.liked(JOURNAL_ID).first()).isTrue()
            assertThat(userData.liked(song.id).first()).isFalse()
            assertThat(viewModel.uiState.value.liked).isTrue()
        }

    /** Каталог ID сменил, сигнал «каталог изменился» ещё не дошёл: запись всё равно берёт ID каталога. */
    @Test
    fun `a like tapped before the heart caught up lands on the song the catalog has now`() =
        runTest(UnconfinedTestDispatcher()) {
            val song = libraryTrack(SONG, "Song")
            play(song)
            userData.rekey(SONG, JOURNAL_ID, announce = false)

            viewModel.onLike()

            assertThat(userData.liked(JOURNAL_ID).first()).isTrue()
            assertThat(userData.liked(song.id).first()).isFalse()
        }

    /** Выбор плейлиста открыт на песне; пока он открыт, скан кончился перепривязкой. */
    @Test
    fun `the song the picker was opened on keeps up with a relink`() =
        runTest(UnconfinedTestDispatcher()) {
            val song = libraryTrack(SONG, "Song")
            play(song)
            viewModel.onPlaylistPickerShown()
            val relinked = relink(song)
            val mix = playlists.create("Mix")

            viewModel.onAddToPlaylist(mix)

            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(relinked)
        }

    @Test
    fun `the song the picker was opened on keeps up with a relink for a new playlist`() =
        runTest(UnconfinedTestDispatcher()) {
            val song = libraryTrack(SONG, "Song")
            play(song)
            viewModel.onPlaylistPickerShown()
            val relinked = relink(song)

            viewModel.onAddToNewPlaylist("Road")

            val road = playlists.playlists().first().single()
            assertThat(playlists.tracks(road.id).first().map { it.track }).containsExactly(relinked)
        }

    /** Очередь ушла дальше, песня выбора перепривязана позже: в плейлист идёт она, с ID каталога. */
    @Test
    fun `the picker keeps its song when the queue moves on and the relink comes after`() =
        runTest(UnconfinedTestDispatcher()) {
            val song = libraryTrack(SONG, "Song")
            val other = libraryTrack(OTHER, "Other")
            play(song, other)
            viewModel.onPlaylistPickerShown()
            viewModel.onNext()
            val relinked = relink(song)
            val mix = playlists.create("Mix")

            viewModel.onAddToPlaylist(mix)

            assertThat(viewModel.uiState.value.title).isEqualTo("Other")
            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(relinked)
        }

    @Test
    fun `an add to a playlist without a picker takes the song the catalog has now`() =
        runTest(UnconfinedTestDispatcher()) {
            val song = libraryTrack(SONG, "Song")
            play(song)
            val relinked = relink(song)
            val mix = playlists.create("Mix")

            viewModel.onAddToPlaylist(mix)

            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(relinked)
        }

    /**
     * Охранный (проходит и на старом коде): «В плейлист» пишет область приложения, а не
     * плеера (Р6) — плеер можно свернуть сразу после выбора. Поиск ID в момент записи
     * идёт там же: пока он ждёт, область плеера отменяют, и запись всё равно доходит.
     */
    @Test
    fun `an add to a playlist that the player left behind is still written`() =
        runTest(UnconfinedTestDispatcher()) {
            val gated = GatedUserData(userData)
            val gatedActions = TrackActions(playback, gated, playlists, CoroutineScope(Dispatchers.Unconfined))
            val model = PlayerViewModel(playback, gated, gatedActions)
            val song = libraryTrack(SONG, "Song")
            backgroundScope.launch { model.uiState.collect {} }
            playback.play(context, listOf(song.toQueueItem()), start = 0)
            val mix = playlists.create("Mix")
            val gate = CompletableDeferred<Unit>().also { gated.gate = it }

            model.onAddToPlaylist(mix)
            model.viewModelScope.cancel()
            gate.complete(Unit)

            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(song)
        }

    /** Играет [tracks] с первого; модель подписана на состояние. */
    private fun TestScope.play(vararg tracks: LibraryTrack) {
        backgroundScope.launch { viewModel.uiState.collect {} }
        playback.play(context, tracks.map { it.toQueueItem() }, start = 0)
    }

    /** Трек фонотеки: файл знает хранилище лайков, строку списка — фонотека; ID у них общий. */
    private fun libraryTrack(
        uri: String,
        title: String,
    ): LibraryTrack {
        val id = userData.add(uri).single()
        return LibraryTrack(id = id, uri = uri, title = title, duration = null, folder = "Music/")
            .also { library.upsert(listOf(it)) }
    }

    /** Перепривязка: файл [track] получает [JOURNAL_ID], прежнего трека в каталоге нет; возвращает трек каталога. */
    private fun relink(track: LibraryTrack): LibraryTrack {
        val relinked = track.copy(id = JOURNAL_ID)
        library.markMissing(listOf(track.id))
        library.upsert(listOf(relinked))
        userData.rekey(checkNotNull(track.uri), JOURNAL_ID)
        return relinked
    }

    private companion object {
        const val SONG = "/storage/emulated/0/Music/song.mp3"
        const val OTHER = "/storage/emulated/0/Music/other.mp3"

        /** ID, который журнал помнил за песней с прежней установки. */
        val JOURNAL_ID = TrackId("journal-song")
    }
}
