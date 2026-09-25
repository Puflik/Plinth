package io.github.puflik.plinth.library.model

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.TrackId
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class LibraryTrackTest {
    @Test
    fun `album belongs to the album artist`() {
        val track = track(artist = "Queen", albumArtist = "Various Artists")

        assertThat(track.albumOwner).isEqualTo("Various Artists")
    }

    @Test
    fun `without album artist the album belongs to the track artist`() {
        assertThat(track(artist = "Queen").albumOwner).isEqualTo("Queen")
    }

    @Test
    fun `album owner is unknown when both artists are`() {
        assertThat(track().albumOwner).isNull()
    }

    @Test
    fun `track needs an uri and a title`() {
        assertThrows(IllegalArgumentException::class.java) { track(uri = " ") }
        assertThrows(IllegalArgumentException::class.java) { track(title = "") }
    }

    @Test
    fun `disc and track numbers start from one`() {
        assertThrows(IllegalArgumentException::class.java) { track(discNumber = 0) }
        assertThrows(IllegalArgumentException::class.java) { track(trackNumber = 0) }
    }

    /** Длительность знают не все форматы без чтения файла целиком. */
    @Test
    fun `duration may be unknown`() {
        assertThat(track(duration = null).duration).isNull()
    }

    private fun track(
        uri: String = "/storage/emulated/0/Music/Queen/Bohemian Rhapsody.flac",
        title: String = "Bohemian Rhapsody",
        artist: String? = null,
        albumArtist: String? = null,
        discNumber: Int? = null,
        trackNumber: Int? = null,
        duration: Duration? = 6.minutes,
    ) = LibraryTrack(
        id = TrackId("0192f4a0-0000-7000-8000-000000000001"),
        uri = uri,
        title = title,
        artist = artist,
        album = "A Night at the Opera",
        albumArtist = albumArtist,
        discNumber = discNumber,
        trackNumber = trackNumber,
        duration = duration,
        folder = "Music/Queen/",
    )
}
