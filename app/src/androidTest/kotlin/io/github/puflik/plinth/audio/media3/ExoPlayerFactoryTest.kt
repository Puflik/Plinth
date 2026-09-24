package io.github.puflik.plinth.audio.media3

import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** Настройка ExoPlayer (B2.1). */
class ExoPlayerFactoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun player_lives_on_the_given_looper_and_plays_music() {
        var looper: Looper? = null
        var attributes: AudioAttributes? = null

        // ExoPlayer отвечает только в своём потоке; проверки — снаружи, чтобы
        // упавшее утверждение не уронило главный поток вместе с процессом.
        instrumentation.runOnMainSync {
            val player = ExoPlayerFactory(instrumentation.targetContext).create(Looper.getMainLooper())
            looper = player.applicationLooper
            attributes = player.audioAttributes
            player.release()
        }

        assertThat(looper).isEqualTo(Looper.getMainLooper())
        assertThat(attributes?.usage).isEqualTo(C.USAGE_MEDIA)
        assertThat(attributes?.contentType).isEqualTo(C.AUDIO_CONTENT_TYPE_MUSIC)
    }

    /**
     * С выключенным экраном процессор засыпает, если его никто не держит (H2):
     * пока звук идёт, плеер держит частичный wake lock. Media3 1.11 включает
     * его сам, пока режим не задан явно, — тест стережёт это умолчание.
     */
    @Test
    fun playing_player_keeps_the_cpu_awake() {
        val track = File(instrumentation.targetContext.cacheDir, "wake-silence.wav")
        SilentWav.write(track, 10.seconds)
        var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            player =
                ExoPlayerFactory(instrumentation.targetContext).create(Looper.getMainLooper()).apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(track)))
                    prepare()
                    play()
                }
        }
        try {
            assertThat(awaitWakeLock()).isTrue()
        } finally {
            instrumentation.runOnMainSync { player?.release() }
        }
    }

    /** Блокировку ExoPlayer берёт в своём потоке — ждём её до [WAKE_LOCK_WAIT_MS]. */
    private fun awaitWakeLock(): Boolean {
        val deadline = SystemClock.uptimeMillis() + WAKE_LOCK_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (WAKE_LOCK_TAG in heldWakeLocks()) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    /**
     * Только блокировки, взятые сейчас: ниже в `dumpsys power` идёт журнал
     * прошлых — там тег ExoPlayer бывает и от чужих приложений.
     */
    private fun heldWakeLocks(): String =
        dumpsysPower()
            .substringAfter("Wake Locks: size=", missingDelimiterValue = "")
            .substringBefore("\n\n")

    private fun dumpsysPower(): String =
        instrumentation.uiAutomation
            .executeShellCommand("dumpsys power")
            .let(::AutoCloseInputStream)
            .bufferedReader()
            .use { it.readText() }

    private companion object {
        const val WAKE_LOCK_TAG = "ExoPlayer:WakeLockManager"
        const val WAKE_LOCK_WAIT_MS = 5_000L
        const val POLL_MS = 200L
    }
}
