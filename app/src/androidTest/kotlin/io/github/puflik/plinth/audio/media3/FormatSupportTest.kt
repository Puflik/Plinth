package io.github.puflik.plinth.audio.media3

import android.net.Uri
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioEngine
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackEvent
import io.github.puflik.plinth.audio.engine.PlaybackParams
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Семь форматов играют через `Media3Engine` (B2.4): MP3, AAC, FLAC, ALAC,
 * Ogg Vorbis, Opus, WAV — до конца, без ошибки, с длительностью из файла.
 *
 * Файлы — секунда тишины на формат, рецепт — `tools/make_format_fixtures.py`.
 * Декодеры — системные, поэтому прогон на каждой версии Android свой: на
 * старых образах эмулятора он и показывает, чего система не умеет.
 */
@RunWith(Parameterized::class)
class FormatSupportTest(
    private val fixture: String,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var engine: AudioEngine? = null

    @After
    fun release() {
        instrumentation.runOnMainSync { engine?.release() }
    }

    @Test
    fun plays_to_the_end() =
        runBlocking {
            val file = File(context.cacheDir, "format-$fixture")
            instrumentation.context.assets.open("formats/$fixture").use { input ->
                file.outputStream().use(input::copyTo)
            }
            instrumentation.runOnMainSync {
                engine = Media3Engine(ExoPlayerFactory(context).create(Looper.getMainLooper()))
            }
            val engine = checkNotNull(engine)

            val outcome =
                withTimeout(TIMEOUT) {
                    val ended =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            engine.events.first { it == PlaybackEvent.TrackEnded || it is PlaybackEvent.Failed }
                        }
                    val source = AudioSource.LocalFile(Uri.fromFile(file).toString())
                    engine.prepare(source, PlaybackParams(autoPlay = true))
                    ended.await()
                }

            assertThat(outcome).isEqualTo(PlaybackEvent.TrackEnded)
            val duration = checkNotNull(engine.progress.value.duration) { "длительность $fixture неизвестна" }
            assertThat((duration - LENGTH).absoluteValue).isLessThan(DURATION_TOLERANCE)
        }

    companion object {
        private val LENGTH: Duration = 1.seconds
        private val DURATION_TOLERANCE: Duration = 100.milliseconds
        private val TIMEOUT: Duration = 10.seconds

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<String> =
            listOf(
                "silence.mp3",
                "silence-aac.m4a",
                "silence.flac",
                "silence-alac.m4a",
                "silence.ogg",
                "silence.opus",
                "silence.wav",
            )
    }
}
