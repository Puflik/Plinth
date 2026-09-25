package io.github.puflik.plinth.audio.media3

import androidx.media3.common.MediaMetadata
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.TrackInfo
import org.junit.Assert.assertThrows
import org.junit.Test

/** `AudioSource` → `MediaItem` (B2.2). */
class MediaItemMapperTest {
    @Test
    fun local_file_keeps_its_uri() {
        val item = MediaItemMapper.map(AudioSource.LocalFile(SAF_URI))

        assertThat(item.localConfiguration?.uri?.toString()).isEqualTo(SAF_URI)
    }

    /** Путь фонотеки ядра (D3b) — файл, а не адрес: `#`, `?` и `%` — часть имени. */
    @Test
    fun local_path_becomes_a_file_uri_with_the_whole_name() {
        val item = MediaItemMapper.map(AudioSource.LocalFile(PATH))

        assertThat(item.localConfiguration?.uri?.scheme).isEqualTo("file")
        assertThat(item.localConfiguration?.uri?.path).isEqualTo(PATH)
        assertThat(item.mediaId).isEqualTo(PATH)
    }

    @Test
    fun source_key_becomes_media_id() {
        val source = AudioSource.LocalFile(SAF_URI)

        assertThat(MediaItemMapper.map(source).mediaId).isEqualTo(source.key)
    }

    /** Чего нет в подписях, система возьмёт из тегов файла — поэтому пустое остаётся пустым. */
    @Test
    fun track_info_becomes_media_metadata() {
        val full = MediaItemMapper.map(AudioSource.LocalFile(SAF_URI), TrackInfo("Yesterday", "The Beatles", "Help!"))
        val titleOnly = MediaItemMapper.map(AudioSource.LocalFile(SAF_URI), TrackInfo("track.flac"))

        assertThat(full.mediaMetadata.title.toString()).isEqualTo("Yesterday")
        assertThat(full.mediaMetadata.artist.toString()).isEqualTo("The Beatles")
        assertThat(full.mediaMetadata.albumTitle.toString()).isEqualTo("Help!")
        assertThat(titleOnly.mediaMetadata.title.toString()).isEqualTo("track.flac")
        assertThat(titleOnly.mediaMetadata.artist).isNull()
        assertThat(titleOnly.mediaMetadata.albumTitle).isNull()
    }

    @Test
    fun without_info_metadata_is_left_to_the_file() {
        assertThat(MediaItemMapper.map(AudioSource.LocalFile(SAF_URI)).mediaMetadata).isEqualTo(MediaMetadata.EMPTY)
    }

    @Test
    fun remote_stream_keeps_its_url() {
        val item = MediaItemMapper.map(AudioSource.Remote(STREAM_URL))

        assertThat(item.localConfiguration?.uri?.toString()).isEqualTo(STREAM_URL)
    }

    @Test
    fun remote_headers_are_rejected_until_providers_arrive() {
        val source = AudioSource.Remote(STREAM_URL, headers = mapOf("Authorization" to "Bearer token"))

        assertThrows(UnsupportedOperationException::class.java) { MediaItemMapper.map(source) }
    }

    private companion object {
        const val SAF_URI = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Ftrack.flac"
        const val STREAM_URL = "https://example.org/stream.opus"
        const val PATH = "/storage/emulated/0/Music/AC/DC #1 ?live 100%.mp3"
    }
}
