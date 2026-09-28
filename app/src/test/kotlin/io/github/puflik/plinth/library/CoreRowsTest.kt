package io.github.puflik.plinth.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.CoreTrack
import io.github.puflik.plinth.ffi.TrackId
import org.junit.Test

/** Строка ядра → трек фонотеки (D3b, E3). */
class CoreRowsTest {
    @Test
    fun `a file without a title is named after the file`() {
        val track = row(title = "", uri = "/storage/emulated/0/Music/intro.mp3").toLibraryTrack()

        assertThat(track?.title).isEqualTo("intro.mp3")
        assertThat(track?.online).isFalse()
    }

    /** «Любимое», плейлисты и «Недавнее» показывают и сетевые треки (E3a): пути у них нет. */
    @Test
    fun `a row without a file is an online track`() {
        val track = row(title = "Opening", uri = null).toLibraryTrack()

        assertThat(track?.uri).isNull()
        assertThat(track?.online).isTrue()
        assertThat(track?.title).isEqualTo("Opening")
    }

    @Test
    fun `a row with neither a title nor a file is not shown`() {
        assertThat(row(title = " ", uri = null).toLibraryTrack()).isNull()
        assertThat(row(title = "Opening", uri = " ").toLibraryTrack()?.online).isTrue()
    }

    private fun row(
        title: String,
        uri: String?,
    ) = CoreTrack(
        id = TrackId("0192f7c4-0000-7000-8000-000000000001"),
        title = title,
        artistCredit = "Plinth Band",
        album = null,
        albumTitle = null,
        duration = null,
        uri = uri,
        liked = true,
        playCount = 0,
    )
}
