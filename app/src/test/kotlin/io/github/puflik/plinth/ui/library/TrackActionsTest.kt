package io.github.puflik.plinth.ui.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.TaggedFile
import io.github.puflik.plinth.queue.QueueContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Действия с треком (D4b): «В плейлист» и игра с того места, куда коснулись. */
class TrackActionsTest {
    private val engine = FakeAudioEngine()
    private val playback = PlaybackController(engine, CoroutineScope(Dispatchers.Unconfined))
    private val library = FakeLibraryRepository()
    private val playlists = FakePlaylistRepository(library)
    private val actions =
        TrackActions(playback, FakeUserDataRepository(), playlists, CoroutineScope(Dispatchers.Unconfined))
    private val tracks = library.add(listOf(TaggedFile(SONG), TaggedFile(OTHER)))

    @Test
    fun `a track goes to the end of the chosen playlist`() =
        runTest {
            val (song, other) = tracks
            val mix = playlists.create("Mix")

            for (track in listOf(song, other, song)) actions.addToPlaylist(track, mix)

            assertThat(playlists.tracks(mix).first().map { it.track }).containsExactly(song, other, song).inOrder()
        }

    @Test
    fun `a new playlist gets the track and a trimmed name`() =
        runTest {
            val (song) = tracks

            actions.addToNewPlaylist(song, "  Road  ")

            val road = playlists.playlists().first().single()
            assertThat(road.name).isEqualTo("Road")
            assertThat(playlists.tracks(road.id).first().map { it.track }).containsExactly(song)
        }

    @Test
    fun `a blank name makes no playlist`() =
        runTest {
            actions.addToNewPlaylist(tracks.first(), "   ")

            assertThat(playlists.playlists().first()).isEmpty()
        }

    /** «В плейлист» из меню — выбор плейлиста, а не очередь. */
    @Test
    fun `add to playlist leaves the queue alone`() {
        val (song) = tracks

        actions.act(TrackAction.ADD_TO_PLAYLIST, QueueContext.Liked, tracks, song)

        assertThat(engine.preparedSources).isEmpty()
        assertThat(playback.queue.value.current).isNull()
    }

    /** В плейлисте трек бывает дважды: играет то место, которого коснулись, а не первое. */
    @Test
    fun `a repeated track plays from the tapped place`() {
        val (song, other) = tracks

        actions.act(TrackAction.PLAY, QueueContext.Liked, listOf(song, other, song), song, at = 2)

        assertThat(engine.preparedSources).containsExactly(AudioSource.LocalFile(song.uri))
        assertThat(playback.queue.value.upcoming).isEmpty()
    }

    private companion object {
        const val SONG = "/storage/emulated/0/Music/song.mp3"
        const val OTHER = "/storage/emulated/0/Music/other.mp3"
    }
}
