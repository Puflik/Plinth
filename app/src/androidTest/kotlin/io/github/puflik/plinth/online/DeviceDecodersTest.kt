package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.AudioFormat
import org.junit.Test

/**
 * [DeviceDecoders] на настоящей системе (F): MP3 и AAC декодирует любой
 * Android, так что их среди недостающих нет никогда. FLAC на Android 8.0 —
 * есть: это видно глазами в `docs/testing/v0.2-checklist.md`.
 */
class DeviceDecodersTest {
    @Test
    fun mp3_and_aac_always_have_a_decoder() {
        assertThat(DeviceDecoders.missing()).containsNoneOf(AudioFormat.MP3, AudioFormat.AAC)
    }
}
