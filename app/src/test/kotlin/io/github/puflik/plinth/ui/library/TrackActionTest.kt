package io.github.puflik.plinth.ui.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.model.LibraryTrack
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/** Трек библиотеки как элемент очереди: всё, что покажут плеер и уведомление, и путь к альбому. */
class TrackActionTest {
    @Test
    fun `queue item keeps titles and the album owner`() {
        val track =
            LibraryTrack(
                id = TrackId("track-1"),
                uri = "/storage/emulated/0/Music/track-1.mp3",
                title = "Bicycle Race",
                artist = "Queen",
                album = "Now 1",
                albumArtist = "Various Artists",
                duration = 3.minutes,
                folder = "Music/",
            )

        val item = track.toQueueItem()

        assertThat(item.source).isEqualTo(AudioSource.LocalFile(track.uri))
        assertThat(listOf(item.title, item.artist, item.album))
            .containsExactly("Bicycle Race", "Queen", "Now 1")
            .inOrder()
        assertThat(item.albumOwner).isEqualTo("Various Artists")
        assertThat(item.duration).isEqualTo(3.minutes)
    }
}
