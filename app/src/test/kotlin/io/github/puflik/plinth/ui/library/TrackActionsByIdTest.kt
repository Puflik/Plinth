package io.github.puflik.plinth.ui.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.TaggedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Р6: плеер знает не `LibraryTrack`, а `TrackId` играющего трека, поэтому «В
 * плейлист» у `TrackActions` есть и по ID — с теми же правилами, что и по треку
 * (`TrackActionsTest`): в конец плейлиста, имя обрезается, пустое имя — ничего.
 */
class TrackActionsByIdTest {
    private val playback = PlaybackController(FakeAudioEngine(), CoroutineScope(Dispatchers.Unconfined))
    private val library = FakeLibraryRepository()
    private val playlists = FakePlaylistRepository(library)
    private val actions =
        TrackActions(playback, FakeUserDataRepository(), playlists, CoroutineScope(Dispatchers.Unconfined))
    private val tracks = library.add(listOf(TaggedFile("/music/song.mp3"), TaggedFile("/music/other.mp3")))

    @Test
    fun `a track id goes to the end of the chosen playlist`() =
        runTest {
            val (song, other) = tracks
            val mix = playlists.create("Mix")

            for (id in listOf(song.id, other.id, song.id)) actions.addToPlaylist(id, mix)

            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(song, other, song).inOrder()
        }

    @Test
    fun `a track id goes to a new playlist with a trimmed name`() =
        runTest {
            val (song) = tracks

            actions.addToNewPlaylist(song.id, "  Road  ")

            val road = playlists.playlists().first().single()
            assertThat(road.name).isEqualTo("Road")
            assertThat(playlists.tracks(road.id).first().map { it.track }).containsExactly(song)
        }

    @Test
    fun `a blank name makes no playlist for a track id`() =
        runTest {
            actions.addToNewPlaylist(tracks.first().id, "   ")

            assertThat(playlists.playlists().first()).isEmpty()
        }
}
