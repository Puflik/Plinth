package io.github.puflik.plinth.audio.media3

import android.media.MediaCodecList
import android.net.Uri
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioEngine
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackError
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
 * Семь форматов через `Media3Engine` (B2.4): MP3, AAC, FLAC, ALAC, Ogg Vorbis,
 * Opus, WAV. Декодеры — системные: где система формат декодирует, трек
 * играет до конца с длительностью из файла; где нет — честная ошибка
 * «формат не поддерживается», а не тишина с бегущим временем (Н1–Н3 прогона
 * на старых Android: FLAC на 8.0, ALAC на 8–11). Кто что декодирует —
 * спрашиваем у самой системы ([MediaCodecList]); WAV — PCM, декодер не нужен.
 *
 * Файлы — секунда тишины на формат, рецепт — `tools/make_format_fixtures.py`.
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
    fun plays_where_the_system_decodes_it() =
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

            if (!decodable(MIMES.getValue(fixture))) {
                assertThat(outcome).isInstanceOf(PlaybackEvent.Failed::class.java)
                assertThat(
                    (outcome as PlaybackEvent.Failed).error,
                ).isInstanceOf(PlaybackError.UnsupportedFormat::class.java)
                return@runBlocking
            }
            assertThat(outcome).isEqualTo(PlaybackEvent.TrackEnded)
            val duration = checkNotNull(engine.progress.value.duration) { "длительность $fixture неизвестна" }
            assertThat((duration - LENGTH).absoluteValue).isLessThan(DURATION_TOLERANCE)
        }

    /** Есть ли в системе декодер [mime]; `null` — PCM, его играют без декодера. */
    private fun decodable(mime: String?): Boolean =
        mime == null ||
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                !codec.isEncoder && codec.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }

    companion object {
        /** Формат звука в файле фикстуры. */
        private val MIMES: Map<String, String?> =
            mapOf(
                "silence.mp3" to "audio/mpeg",
                "silence-aac.m4a" to "audio/mp4a-latm",
                "silence.flac" to "audio/flac",
                "silence-alac.m4a" to "audio/alac",
                "silence.ogg" to "audio/vorbis",
                "silence.opus" to "audio/opus",
                "silence.wav" to null,
            )
        private val LENGTH: Duration = 1.seconds
        private val DURATION_TOLERANCE: Duration = 100.milliseconds
        private val TIMEOUT: Duration = 10.seconds

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<String> = MIMES.keys.toList()
    }
}
