package io.github.puflik.plinth.library.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
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

    private fun track(
        uri: String = "content://media/external/audio/media/1",
        title: String = "Bohemian Rhapsody",
        artist: String? = null,
        albumArtist: String? = null,
        discNumber: Int? = null,
        trackNumber: Int? = null,
    ) = LibraryTrack(
        id = 1,
        uri = uri,
        title = title,
        artist = artist,
        album = "A Night at the Opera",
        albumArtist = albumArtist,
        discNumber = discNumber,
        trackNumber = trackNumber,
        duration = 6.minutes,
        folder = "Music/Queen/",
        modifiedAt = 0,
    )
}
