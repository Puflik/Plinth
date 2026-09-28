package io.github.puflik.plinth.audio.media3

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.StreamLookup
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Адрес сетевого трека в момент загрузки (E3): загрузчик ExoPlayer раскрывает
 * ссылку на трек у `StreamResolver`, а беды — кодами, по которым очередь
 * решает, пропустить трек или встать (G3).
 */
class OnlineResolverTest {
    private val asked = mutableListOf<String>()

    @Test
    fun a_found_stream_replaces_the_link_with_its_address_and_headers() {
        val resolver =
            resolver(StreamLookup.Found("https://archive.org/download/x/01.flac", mapOf("Authorization" to "t")))
        val link =
            DataSpec
                .Builder()
                .setUri(link())
                .setPosition(4_096)
                .build()

        val resolved = resolver.resolveDataSpec(link)

        assertThat(asked).containsExactly(TRACK)
        assertThat(resolved.uri.toString()).isEqualTo("https://archive.org/download/x/01.flac")
        assertThat(resolved.httpRequestHeaders).containsExactly("Authorization", "t")
        assertThat(resolved.position).isEqualTo(4_096)
    }

    @Test
    fun a_file_or_an_address_is_left_alone() {
        val resolver = resolver(StreamLookup.Unavailable)
        val file = DataSpec(Uri.parse("file:///storage/emulated/0/Music/a.mp3"))
        val address = DataSpec(Uri.parse("https://example.org/stream.opus"))

        assertThat(resolver.resolveDataSpec(file)).isSameInstanceAs(file)
        assertThat(resolver.resolveDataSpec(address)).isSameInstanceAs(address)
        assertThat(asked).isEmpty()
    }

    /**
     * Перемотка открывает поток заново с байта файла: сменись посреди трека
     * Wi-Fi на сотовую, FLAC превратился бы в MP3 с чужого места. Адрес
     * держится на всю загрузку, новая загрузка того же трека спрашивает снова.
     */
    @Test
    fun one_load_keeps_its_address_and_a_new_load_asks_again() {
        val answers =
            ArrayDeque(
                listOf(
                    StreamLookup.Found("https://archive.org/download/x/01.flac"),
                    StreamLookup.Found("https://archive.org/download/x/01.mp3"),
                ),
            )
        val resolver =
            OnlineResolver { track ->
                asked += track
                answers.removeFirst()
            }
        val load = link()

        val start = resolver.resolveDataSpec(DataSpec(load))
        val seek =
            resolver.resolveDataSpec(
                DataSpec
                    .Builder()
                    .setUri(load)
                    .setPosition(1_000_000)
                    .build(),
            )
        val again = resolver.resolveDataSpec(DataSpec(link()))

        assertThat(listOf(start.uri, seek.uri, again.uri).map(Uri::toString))
            .containsExactly(
                "https://archive.org/download/x/01.flac",
                "https://archive.org/download/x/01.flac",
                "https://archive.org/download/x/01.mp3",
            ).inOrder()
        assertThat(seek.position).isEqualTo(1_000_000)
        assertThat(asked).containsExactly(TRACK, TRACK)
    }

    /** Не вышло — не запоминается: повтор загрузчика спросит снова, сеть могла вернуться. */
    @Test
    fun a_failed_lookup_is_asked_again() {
        val answers =
            ArrayDeque(listOf(StreamLookup.NoNetwork, StreamLookup.Found("https://archive.org/download/x/01.flac")))
        val resolver =
            OnlineResolver { track ->
                asked += track
                answers.removeFirst()
            }
        val load = link()

        assertThrows(DataSourceException::class.java) { resolver.resolveDataSpec(DataSpec(load)) }
        val retried = resolver.resolveDataSpec(DataSpec(load))

        assertThat(retried.uri.toString()).isEqualTo("https://archive.org/download/x/01.flac")
        assertThat(asked).hasSize(2)
    }

    @Test
    fun nothing_to_play_is_a_missing_file_and_no_network_a_failed_connection() {
        val reasons =
            listOf(StreamLookup.Unavailable, StreamLookup.NoNetwork, StreamLookup.Failed).map { lookup ->
                assertThrows(
                    DataSourceException::class.java,
                ) { resolver(lookup).resolveDataSpec(DataSpec(link())) }.reason
            }

        assertThat(reasons)
            .containsExactly(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            ).inOrder()
    }

    private fun resolver(answer: StreamLookup) =
        OnlineResolver { track ->
            asked += track
            answer
        }

    private fun link(): Uri = checkNotNull(MediaItemMapper.map(AudioSource.Online(TRACK)).localConfiguration).uri

    private companion object {
        const val TRACK = "0192f7c4-0000-7000-8000-00000000000a"
    }
}
