package io.github.puflik.plinth.audio.media3

import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

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
}
