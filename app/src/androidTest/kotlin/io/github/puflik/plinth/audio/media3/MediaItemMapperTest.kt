package io.github.puflik.plinth.audio.media3

import android.net.Uri
import androidx.media3.common.MediaMetadata
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.TrackInfo
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

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

    /** Сетевой трек (E3): адреса до загрузки нет — в элементе ссылка на трек, её раскроет загрузчик. */
    @Test
    fun online_track_becomes_a_link_resolved_at_load() {
        val source = AudioSource.Online(TRACK)

        val item = MediaItemMapper.map(source, TrackInfo("Opening", "Plinth Band"))

        assertThat(item.mediaId).isEqualTo(source.key)
        assertThat(item.localConfiguration?.uri?.let(MediaItemMapper::onlineTrack)).isEqualTo(TRACK)
        assertThat(item.mediaMetadata.title.toString()).isEqualTo("Opening")
        assertThat(MediaItemMapper.onlineTrack(Uri.parse(STREAM_URL))).isNull()
        assertThat(MediaItemMapper.onlineTrack(Uri.fromFile(File(PATH)))).isNull()
    }

    /** Каждая подготовка — своя загрузка: адрес для неё выберут заново, по сети этой минуты. */
    @Test
    fun every_load_of_an_online_track_is_its_own_link() {
        val first = MediaItemMapper.map(AudioSource.Online(TRACK)).localConfiguration?.uri
        val second = MediaItemMapper.map(AudioSource.Online(TRACK)).localConfiguration?.uri

        assertThat(first).isNotEqualTo(second)
        assertThat(listOf(first, second).map { it?.let(MediaItemMapper::onlineTrack) }).containsExactly(TRACK, TRACK)
    }

    private companion object {
        const val TRACK = "0192f7c4-0000-7000-8000-00000000000a"
        const val SAF_URI = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Ftrack.flac"
        const val STREAM_URL = "https://example.org/stream.opus"
        const val PATH = "/storage/emulated/0/Music/AC/DC #1 ?live 100%.mp3"
    }
}
