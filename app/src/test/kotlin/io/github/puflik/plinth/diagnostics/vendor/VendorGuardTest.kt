package io.github.puflik.plinth.diagnostics.vendor

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Производители, которые убивают фоновую игру (G2, фича 141). */
@OptIn(ExperimentalCoroutinesApi::class)
class VendorGuardTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `manufacturers are grouped by the vendor that restricts them`() {
        assertThat(Vendor.of("Xiaomi")).isEqualTo(Vendor.XIAOMI)
        assertThat(Vendor.of("Redmi")).isEqualTo(Vendor.XIAOMI)
        assertThat(Vendor.of("POCO")).isEqualTo(Vendor.XIAOMI)
        assertThat(Vendor.of("samsung")).isEqualTo(Vendor.SAMSUNG)
        assertThat(Vendor.of("HUAWEI")).isEqualTo(Vendor.HUAWEI)
        assertThat(Vendor.of("HONOR")).isEqualTo(Vendor.HUAWEI)
        assertThat(Vendor.of("OPPO")).isEqualTo(Vendor.OPPO)
        assertThat(Vendor.of("realme")).isEqualTo(Vendor.OPPO)
        assertThat(Vendor.of("OnePlus")).isEqualTo(Vendor.OPPO)
        assertThat(Vendor.of("Google")).isEqualTo(Vendor.OTHER)
    }

    @Test
    fun `named vendors have their own settings screens, others use the generic ones`() {
        Vendor.entries.filter { it != Vendor.OTHER }.forEach { vendor ->
            assertThat(VendorIntents.targets(vendor)).isNotEmpty()
        }
        assertThat(VendorIntents.targets(Vendor.OTHER)).isEmpty()
    }

    @Test
    fun `guide links to the vendor page on dontkillmyapp`() {
        assertThat(VendorIntents.guideUrl(Vendor.XIAOMI)).isEqualTo("https://dontkillmyapp.com/xiaomi")
        assertThat(VendorIntents.guideUrl(Vendor.OTHER)).isEqualTo("https://dontkillmyapp.com/")
    }

    @Test
    fun `only a death while playing that nobody asked for is a kill`() {
        assertThat(KillVerdict.killed(wasPlaying = false, exit = ExitReason.SYSTEM, crashed = false)).isFalse()
        assertThat(KillVerdict.killed(wasPlaying = true, exit = ExitReason.SYSTEM, crashed = false)).isTrue()
        assertThat(KillVerdict.killed(wasPlaying = true, exit = ExitReason.USER, crashed = false)).isFalse()
        assertThat(KillVerdict.killed(wasPlaying = true, exit = ExitReason.CRASH, crashed = false)).isFalse()
        assertThat(KillVerdict.killed(wasPlaying = true, exit = ExitReason.UPDATE, crashed = false)).isFalse()
        // Android 10 и старше причину не называют: наш сбой видно по отчёту о нём.
        assertThat(KillVerdict.killed(wasPlaying = true, exit = ExitReason.UNKNOWN, crashed = false)).isTrue()
        assertThat(KillVerdict.killed(wasPlaying = true, exit = ExitReason.UNKNOWN, crashed = true)).isFalse()
    }

    /** Ревью №9: телефон сел или его перезагрузили посреди игры — прошивка тут ни при чём. */
    @Test
    fun `reboot while playing is not a kill`() {
        for (exit in listOf(ExitReason.UNKNOWN, ExitReason.SYSTEM)) {
            assertThat(KillVerdict.killed(wasPlaying = true, exit = exit, crashed = false, rebooted = true)).isFalse()
        }
    }

    @Test
    fun `marker is set while sound plays and cleared when it stops`() =
        runTest(UnconfinedTestDispatcher()) {
            val marker = KillMarker(temp.root.resolve("vendor"))
            val playback = PlaybackController(FakeAudioEngine(), backgroundScope)
            KillWatch(playback, marker, backgroundScope).start()
            assertThat(marker.isSet()).isFalse()

            val item = QueueItem(AudioSource.LocalFile("content://plinth.test/a.mp3"), "a")
            playback.play(QueueContext.File, listOf(item), start = 0)
            assertThat(marker.isSet()).isTrue()

            playback.togglePlayPause()
            assertThat(marker.isSet()).isFalse()
        }
}
