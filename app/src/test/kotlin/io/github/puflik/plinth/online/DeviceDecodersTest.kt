package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.AudioFormat
import org.junit.Test

class DeviceDecodersTest {
    /** Android 8.0: декодеров FLAC и ALAC нет — остальное есть. */
    @Test
    fun `formats without a decoder are the missing ones`() {
        val android8 = setOf("audio/mpeg", "audio/mp4a-latm", "audio/vorbis", "audio/opus", "audio/raw")

        assertThat(DeviceDecoders.missingAmong(android8)).containsExactly(AudioFormat.FLAC, AudioFormat.ALAC)
    }

    /** WAV и AIFF — PCM, их разбирает сам Media3; неизвестное не наказывается. */
    @Test
    fun `pcm and unknown formats never count as missing`() {
        val missing = DeviceDecoders.missingAmong(emptySet())

        assertThat(missing).containsNoneOf(AudioFormat.WAV, AudioFormat.AIFF, AudioFormat.OTHER)
    }
}
