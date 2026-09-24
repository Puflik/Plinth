package io.github.puflik.plinth.audio.media3

import android.content.ComponentName
import android.net.Uri
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackParams
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.di.AudioEntryPoint
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
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

    @Test
    fun external_next_and_previous_go_to_the_app_queue() =
        runBlocking<Unit> {
            val items =
                (1..2).map { number ->
                    val file = File(context.cacheDir, "queue-$number.wav").also { SilentWav.write(it, 3.seconds) }
                    QueueItem(AudioSource.LocalFile(Uri.fromFile(file).toString()), title = "queue-$number")
                }
            val playback = onMain { EntryPointAccessors.fromApplication<AudioEntryPoint>(context).playbackController() }
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val controller =
                MediaController
                    .Builder(context, token)
                    .setApplicationLooper(Looper.getMainLooper())
                    .buildAsync()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            try {
                withTimeout(TIMEOUT) {
                    onMain { playback.play(QueueContext.Album("Service", null), items, start = 0) }
                    playback.state.first { it is PlaybackState.Playing }

                    // Снаружи видно, что звук идёт: события плеера доходят до сессии.
                    while (!onMain { controller.isPlaying }) delay(POLL)
                    assertThat(onMain { controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) }).isTrue()
                    onMain { controller.seekToNext() }
                    playback.queue.first { it.current == items[1] }

                    onMain { controller.seekToPrevious() }
                    playback.queue.first { it.current == items[0] }
                }
            } finally {
                onMain {
                    controller.release()
                    playback.togglePlayPause()
                }
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
        val POLL = 50.milliseconds
    }
}
