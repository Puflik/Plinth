package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.AudioFormat
import org.junit.Test

class DeviceDecodersTest {
    /** Android 8.0: декодеров FLAC и ALAC нет — остальное есть. */
    @Test
    fun `formats without a decoder are the missing ones`() {
        val android8 = setOf("audio/mpeg", "audio/mp4a-latm", "audio/vorbis", "audio/opus", "audio/raw")

        assertThat(DeviceDecoders.missingAmong(android8)).containsExactly(AudioFormat.FLAC, AudioFormat.ALAC, AudioFormat.AIFF)
    }

    /** WAV разбирает сам Media3; неизвестное не наказывается. */
    @Test
    fun `wav and unknown formats never count as missing`() {
        val missing = DeviceDecoders.missingAmong(emptySet())

        assertThat(missing).containsNoneOf(AudioFormat.WAV, AudioFormat.OTHER)
    }

    /** В Media3 нет экстрактора AIFF: он «без декодера» даже там, где декодеры есть все (ревью v0.2, №12). */
    @Test
    fun `aiff is always missing because media3 cannot read it`() {
        val everything = setOf("audio/flac", "audio/alac", "audio/mpeg", "audio/mp4a-latm", "audio/vorbis", "audio/opus")

        assertThat(DeviceDecoders.missingAmong(everything)).containsExactly(AudioFormat.AIFF)
    }
}
