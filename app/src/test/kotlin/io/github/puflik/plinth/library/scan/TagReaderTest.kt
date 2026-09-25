package io.github.puflik.plinth.library.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class TagReaderTest {
    @Test
    fun `row becomes a track as it is`() {
        val track = TagReader.read(row())

        assertThat(track)
            .isEqualTo(
                ScannedTrack(
                    id = 7,
                    uri = "content://media/external/audio/media/7",
                    title = "Mustapha",
                    artist = "Queen",
                    album = "Jazz",
                    albumArtist = "Queen",
                    discNumber = null,
                    trackNumber = 1,
                    duration = 183_000.milliseconds,
                    folder = "Music/Queen/Jazz/",
                    modifiedAt = MODIFIED,
                ),
            )
    }

    @Test
    fun `track column packs disc and number`() {
        val track = TagReader.read(row(track = 2003))

        assertThat(track.discNumber).isEqualTo(2)
        assertThat(track.trackNumber).isEqualTo(3)
    }

    @Test
    fun `zero or negative parts of track column mean no tag`() {
        assertThat(TagReader.read(row(track = 2000)).trackNumber).isNull()
        assertThat(TagReader.read(row(track = 2000)).discNumber).isEqualTo(2)
        assertThat(TagReader.read(row(track = 0)).trackNumber).isNull()
        assertThat(TagReader.read(row(track = -1)).trackNumber).isNull()
        assertThat(TagReader.read(row(track = null)).discNumber).isNull()
    }

    @Test
    fun `unknown and blank tags are empty`() {
        val track = TagReader.read(row(artist = "<unknown>", album = "  ", albumArtist = ""))

        assertThat(track.artist).isNull()
        assertThat(track.album).isNull()
        assertThat(track.albumArtist).isNull()
    }

    @Test
    fun `tags are trimmed`() {
        assertThat(TagReader.read(row(artist = " Queen ")).artist).isEqualTo("Queen")
    }

    @Test
    fun `track without title is named after its file`() {
        assertThat(TagReader.read(row(title = null)).title).isEqualTo("01 Mustapha")
        assertThat(TagReader.read(row(title = " ")).title).isEqualTo("01 Mustapha")
        assertThat(TagReader.read(row(title = "<unknown>")).title).isEqualTo("01 Mustapha")
    }

    @Test
    fun `file name without extension is kept whole`() {
        assertThat(TagReader.read(row(title = null, displayName = "Mustapha")).title).isEqualTo("Mustapha")
        assertThat(TagReader.read(row(title = null, displayName = ".mp3")).title).isEqualTo(".mp3")
    }

    @Test
    fun `track without title and file name is named by its id`() {
        assertThat(TagReader.read(row(title = null, displayName = null)).title).isEqualTo("7")
    }

    @Test
    fun `unknown duration is zero`() {
        assertThat(TagReader.read(row(durationMs = null)).duration).isEqualTo(Duration.ZERO)
        assertThat(TagReader.read(row(durationMs = -5)).duration).isEqualTo(Duration.ZERO)
    }

    private fun row(
        title: String? = "Mustapha",
        displayName: String? = "01 Mustapha.flac",
        artist: String? = "Queen",
        album: String? = "Jazz",
        albumArtist: String? = "Queen",
        track: Int? = 1,
        durationMs: Long? = 183_000,
    ) = MediaStoreRow(
        id = 7,
        uri = "content://media/external/audio/media/7",
        displayName = displayName,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        track = track,
        durationMs = durationMs,
        folder = "Music/Queen/Jazz/",
        dateModified = MODIFIED,
    )

    private companion object {
        const val MODIFIED = 1_700_000_000L
    }
}
