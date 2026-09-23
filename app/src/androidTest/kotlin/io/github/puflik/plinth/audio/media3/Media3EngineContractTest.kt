package io.github.puflik.plinth.audio.media3

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioEngine
import io.github.puflik.plinth.audio.engine.AudioEngineContractTest
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackEvent
import io.github.puflik.plinth.audio.engine.PlaybackParams
import io.github.puflik.plinth.audio.engine.PlaybackState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `Media3Engine` проходит общий контракт движка (B2.2, B1.4) — тот же класс,
 * что `FakeAudioEngine` проходит на JVM, здесь прогоняется на настоящем
 * ExoPlayer. Поэтому тесты и живут на эмуляторе: декодер, `AudioTrack` и
 * поток плеера на JVM не подделать.
 */
class Media3EngineContractTest : AudioEngineContractTest() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    private val track =
        File(context.cacheDir, "contract-silence.wav").also { SilentWav.write(it, TRACK_LENGTH) }

    private val missing = File(context.cacheDir, "contract-missing.wav").also { it.delete() }

    // Настоящему плееру нужно время на поток, декодер и AudioTrack;
    // на холодном эмуляторе пять секунд контракта впритык.
    override val timeout = 10.seconds

    // В приложении плеер и движок живут на главном потоке (этого требует
    // MediaSessionService), значит, и контракт проверяется там же.
    override fun createEngine(): AudioEngine {
        var engine: AudioEngine? = null
        instrumentation.runOnMainSync {
            engine = Media3Engine(ExoPlayerFactory(context).create(Looper.getMainLooper()))
        }
        return checkNotNull(engine)
    }

    override fun playableSource(): AudioSource = AudioSource.LocalFile(Uri.fromFile(track).toString())

    override fun unavailableSource(): AudioSource = AudioSource.LocalFile(Uri.fromFile(missing).toString())

    /**
     * Слушать трек целиком незачем: перемотка к хвосту проводит декодер
     * до конца файла тем же путём, что и обычное воспроизведение.
     */
    override suspend fun playToEnd(engine: AudioEngine) {
        engine.seekTo(TRACK_LENGTH - TAIL)
    }

    // Ниже — то, чего контракт не требует от всех движков, но без чего
    // Media3Engine бесполезен: полоса перемотки живёт на этих событиях.

    @Test
    fun playback_reports_progress() =
        runBlocking<Unit> {
            val engine = createEngine()
            try {
                withTimeout(timeout) {
                    val progressed =
                        awaitEvent(engine) { it is PlaybackEvent.PositionChanged && it.position >= PROGRESS }

                    engine.prepare(playableSource(), PlaybackParams(autoPlay = true))

                    progressed.await()
                }
            } finally {
                engine.release()
            }
        }

    @Test
    fun losing_audio_focus_pauses_playback() =
        runBlocking<Unit> {
            val engine = createEngine()
            val audio = context.getSystemService(AudioManager::class.java)
            // Фокус раздаётся по слушателям, а не по приложениям: этот запрос
            // для плеера — то же, что другой музыкальный плеер, начавший играть.
            val rival = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).build()
            try {
                withTimeout(timeout) {
                    engine.prepare(playableSource(), PlaybackParams(autoPlay = true))
                    engine.awaitState { it is PlaybackState.Playing }

                    assertThat(audio.requestAudioFocus(rival)).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

                    assertThat(engine.awaitState { it is PlaybackState.Paused }).isEqualTo(PlaybackState.Paused)
                }
            } finally {
                audio.abandonAudioFocusRequest(rival)
                engine.release()
            }
        }

    @Test
    fun engine_is_created_on_its_player_thread_only() {
        var player: ExoPlayer? = null
        instrumentation.runOnMainSync { player = ExoPlayerFactory(context).create(Looper.getMainLooper()) }
        try {
            assertThrows(IllegalStateException::class.java) { Media3Engine(checkNotNull(player)) }
        } finally {
            instrumentation.runOnMainSync { player?.release() }
        }
    }

    private companion object {
        val TRACK_LENGTH = 3.seconds
        val TAIL = 300.milliseconds
        val PROGRESS = 300.milliseconds
    }
}
