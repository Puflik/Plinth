package io.github.puflik.plinth.audio.media3

import android.content.ComponentName
import android.net.Uri
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackParams
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.di.AudioEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * `PlaybackService` отдаёт системе тот же плеер, на котором играет движок
 * приложения (B3.1).
 *
 * Внешний пульт — уведомление, гарнитура, экран блокировки — подключается к
 * сессии службы. Если его команда доходит до движка, значит, сессия и
 * движок держат один и тот же плеер.
 */
class PlaybackServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun external_controller_drives_the_app_engine() =
        runBlocking<Unit> {
            val track = File(context.cacheDir, "service-silence.wav").also { SilentWav.write(it, 3.seconds) }
            val engine = onMain { EntryPointAccessors.fromApplication<AudioEntryPoint>(context).audioEngine() }
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val controller =
                MediaController
                    .Builder(context, token)
                    .setApplicationLooper(Looper.getMainLooper())
                    .buildAsync()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            try {
                withTimeout(TIMEOUT) {
                    val source = AudioSource.LocalFile(Uri.fromFile(track).toString())
                    engine.prepare(source, PlaybackParams(autoPlay = true))
                    engine.state.first { it is PlaybackState.Playing }

                    onMain { controller.pause() }

                    assertThat(engine.state.first { it is PlaybackState.Paused }).isEqualTo(PlaybackState.Paused)
                }
            } finally {
                onMain { controller.release() }
            }
        }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        val TIMEOUT = 10.seconds
    }
}
