package io.github.puflik.plinth.ui.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.GatedUserData
import io.github.puflik.plinth.library.model.LibraryTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Р9а: плеер знает не ID играющего трека, а его источник, и «В плейлист» у `TrackActions`
 * есть и по источнику. ID берётся в момент записи, в области приложения: перепривязка
 * (скан после переустановки, «Восстановить») между выбором плейлиста и записью не оставляет
 * запись на ID, которого в каталоге уже нет. Правила те же, что у «В плейлист» по ID
 * (`TrackActionsByIdTest`): в конец плейлиста, имя обрезается, пустое имя — ничего.
 */
class R9aTrackActionsBySourceTest {
    private val playback = PlaybackController(FakeAudioEngine(), CoroutineScope(Dispatchers.Unconfined))
    private val library = FakeLibraryRepository()
    private val userData = FakeUserDataRepository()
    private val playlists = FakePlaylistRepository(library)
    private val actions = TrackActions(playback, userData, playlists, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `a library file goes to the end of the chosen playlist`() =
        runTest {
            val song = libraryTrack(SONG)
            val other = libraryTrack(OTHER)
            val mix = playlists.create("Mix")

            for (track in listOf(song, other, song)) actions.addToPlaylist(file(track), mix)

            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(song, other, song).inOrder()
        }

    /** Выбор плейлиста и запись разделены: перепривязка успела между ними — запись идёт на ID каталога. */
    @Test
    fun `the id is taken when the track is written, not when the playlist is chosen`() =
        runTest {
            val gated = GatedUserData(userData)
            val gatedActions = TrackActions(playback, gated, playlists, CoroutineScope(Dispatchers.Unconfined))
            val song = libraryTrack(SONG)
            val mix = playlists.create("Mix")
            val gate = CompletableDeferred<Unit>().also { gated.gate = it }

            gatedActions.addToPlaylist(file(song), mix)
            val relinked = song.copy(id = JOURNAL_ID)
            library.markMissing(listOf(song.id))
            library.upsert(listOf(relinked))
            userData.rekey(SONG, JOURNAL_ID)
            gate.complete(Unit)

            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(relinked)
        }

    @Test
    fun `an online track goes under its own id`() =
        runTest {
            val opening = LibraryTrack(TrackId("online-1"), uri = null, title = "Opening", duration = null, folder = "")
            library.upsert(listOf(opening))
            val mix = playlists.create("Mix")

            actions.addToPlaylist(AudioSource.Online("online-1"), mix)

            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(opening)
        }

    /** Файл не из фонотеки (SAF) и готовый адрес треком не становятся: ни записи, ни нового плейлиста. */
    @Test
    fun `a source outside the library goes nowhere`() =
        runTest {
            val mix = playlists.create("Mix")
            val outside = listOf(AudioSource.LocalFile("content://plinth.test/outside.mp3"), AudioSource.Remote(STREAM))

            for (source in outside) {
                actions.addToPlaylist(source, mix)
                actions.addToNewPlaylist(source, "Road")
            }

            assertThat(playlists.tracks(mix).first()).isEmpty()
            assertThat(playlists.playlists().first().map { it.id }).containsExactly(mix)
        }

    @Test
    fun `a source goes to a new playlist with a trimmed name`() =
        runTest {
            val song = libraryTrack(SONG)

            actions.addToNewPlaylist(file(song), "  Road  ")

            val road = playlists.playlists().first().single()
            assertThat(road.name).isEqualTo("Road")
            assertThat(playlists.tracks(road.id).first().map { it.track }).containsExactly(song)
        }

    @Test
    fun `a blank name makes no playlist for a source`() =
        runTest {
            actions.addToNewPlaylist(file(libraryTrack(SONG)), "   ")

            assertThat(playlists.playlists().first()).isEmpty()
        }

    /** Трек фонотеки: файл знает хранилище лайков, строку списка — фонотека; ID у них общий. */
    private fun libraryTrack(uri: String): LibraryTrack {
        val id = userData.add(uri).single()
        return LibraryTrack(id = id, uri = uri, title = uri.substringAfterLast('/'), duration = null, folder = "Music/")
            .also { library.upsert(listOf(it)) }
    }

    private fun file(track: LibraryTrack) = AudioSource.LocalFile(checkNotNull(track.uri))

    private companion object {
        const val SONG = "/storage/emulated/0/Music/song.mp3"
        const val OTHER = "/storage/emulated/0/Music/other.mp3"
        const val STREAM = "https://radio.example/live"
        val JOURNAL_ID = TrackId("journal-song")
    }
}
