package io.github.puflik.plinth.audio.media3

import androidx.media3.common.PlaybackException
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
}
