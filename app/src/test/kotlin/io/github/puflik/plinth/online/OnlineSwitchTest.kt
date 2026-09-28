package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.StreamLookup
import io.github.puflik.plinth.ffi.AudioFormat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Переключатель «Онлайн-источники» (E3b) ведёт ядро, поток выбирается по сети этой минуты. */
class OnlineSwitchTest {
    private val online = FakeOnlineRepository()

    @Test
    fun `sources are on by default and follow the switch`() =
        runTest {
            val settings = FakeOnlineSettings()
            OnlineSwitch(settings, online, backgroundScope).start()

            runCurrent()
            val first = online.enabled
            settings.setEnabled(false)
            runCurrent()
            val off = online.enabled
            settings.setEnabled(true)
            runCurrent()

            assertThat(listOf(first, off, online.enabled)).containsExactly(true, false, true).inOrder()
        }

    @Test
    fun `the stream is chosen by the network at the moment of loading`() =
        runTest {
            online.setEnabled(true)
            val track = checkNotNull(online.add(FakeOnlineRepository.PROVIDER, TestConcert.tracks)).first()
            var metered = false
            val streams = OnlineStreams(online) { metered }

            val wifi = streams.resolve(track.value) as StreamLookup.Found
            metered = true
            val cellular = streams.resolve(track.value) as StreamLookup.Found

            assertThat(wifi.url).endsWith(".flac")
            assertThat(cellular.url).endsWith(".mp3")
        }

    /** Android 8.0 (F): FLAC не декодируется — по Wi-Fi играет MP3. */
    @Test
    fun `a format the device cannot decode is not asked for`() =
        runTest {
            online.setEnabled(true)
            val track = checkNotNull(online.add(FakeOnlineRepository.PROVIDER, TestConcert.tracks)).first()
            val streams = OnlineStreams(online, { setOf(AudioFormat.FLAC) }) { false }

            val wifi = streams.resolve(track.value) as StreamLookup.Found

            assertThat(wifi.url).endsWith(".mp3")
        }
}
