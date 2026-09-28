package io.github.puflik.plinth.audio.media3

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.PlaybackError
import org.junit.Test

/**
 * Ошибки ExoPlayer → типизированные `PlaybackError` (B2.2).
 *
 * От типа зависит реакция приложения (см. `PlaybackError`), поэтому
 * сопоставление кодов проверяется явно, код за кодом.
 */
class PlayerListenerAdapterTest {
    @Test
    fun missing_file_is_source_unavailable() {
        assertThat(errorFor(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
            .isInstanceOf(PlaybackError.SourceUnavailable::class.java)
    }

    @Test
    fun revoked_permission_is_source_unavailable() {
        assertThat(errorFor(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
            .isInstanceOf(PlaybackError.SourceUnavailable::class.java)
    }

    @Test
    fun failed_connection_is_network() {
        assertThat(errorFor(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            .isInstanceOf(PlaybackError.Network::class.java)
    }

    @Test
    fun bad_http_status_is_network() {
        assertThat(errorFor(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
            .isInstanceOf(PlaybackError.Network::class.java)
    }

    /**
     * Провайдер ответил, что файла нет (E3: 404 и 410, как у ядра), — это
     * пропавший источник, его очередь пропускает; прочие статусы — сеть.
     */
    @Test
    fun a_stream_gone_from_the_provider_is_source_unavailable() {
        val gone = listOf(404, 410).map { status -> httpError(status).toPlaybackError() }
        val busy = httpError(503).toPlaybackError()

        gone.forEach { assertThat(it).isInstanceOf(PlaybackError.SourceUnavailable::class.java) }
        assertThat(busy).isInstanceOf(PlaybackError.Network::class.java)
    }

    @Test
    fun unknown_container_is_unsupported_format() {
        assertThat(errorFor(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
            .isInstanceOf(PlaybackError.UnsupportedFormat::class.java)
    }

    @Test
    fun unknown_codec_is_unsupported_format() {
        assertThat(errorFor(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
            .isInstanceOf(PlaybackError.UnsupportedFormat::class.java)
    }

    /** Битый файл очередь пропускает так же, как чужой формат (G3). */
    @Test
    fun damaged_container_is_malformed() {
        assertThat(errorFor(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
            .isInstanceOf(PlaybackError.Malformed::class.java)
    }

    @Test
    fun unexplained_failure_is_unknown() {
        assertThat(errorFor(PlaybackException.ERROR_CODE_UNSPECIFIED))
            .isInstanceOf(PlaybackError.Unknown::class.java)
    }

    @Test
    fun detail_names_the_player_error_code() {
        assertThat(errorFor(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND).detail)
            .contains("ERROR_CODE_IO_FILE_NOT_FOUND")
    }

    private fun errorFor(code: Int): PlaybackError = PlaybackException("test", null, code).toPlaybackError()

    private fun httpError(status: Int) =
        PlaybackException(
            "test",
            HttpDataSource.InvalidResponseCodeException(
                status,
                null,
                null,
                emptyMap(),
                DataSpec(Uri.EMPTY),
                ByteArray(0),
            ),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
}
