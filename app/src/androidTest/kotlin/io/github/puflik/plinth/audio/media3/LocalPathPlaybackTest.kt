package io.github.puflik.plinth.audio.media3

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
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Трек фонотеки ядра играет по голому пути к файлу (D3b): ядро хранит путь,
 * а не `content://`. Знаки, которые в адресе что-то значат (`#`, `?`, `%`),
 * в имени файла — просто часть имени.
 */
@RunWith(Parameterized::class)
class LocalPathPlaybackTest(
    private val name: String,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val folder = File(instrumentation.targetContext.cacheDir, "path-" + UUID.randomUUID())
    private var engine: AudioEngine? = null

    @After
    fun release() {
        instrumentation.runOnMainSync { engine?.release() }
        folder.deleteRecursively()
    }

    @Test
    fun plays_to_the_end() =
        runBlocking {
            val file = File(folder, name).apply { parentFile?.mkdirs() }
            instrumentation.context.assets.open("formats/silence.mp3").use { input ->
                file.outputStream().use(input::copyTo)
            }
            instrumentation.runOnMainSync {
                engine = Media3Engine(ExoPlayerFactory(instrumentation.targetContext).create(Looper.getMainLooper()))
            }
            val engine = checkNotNull(engine)

            val outcome =
                withTimeout(TIMEOUT) {
                    val ended =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            engine.events.first { it == PlaybackEvent.TrackEnded || it is PlaybackEvent.Failed }
                        }
                    engine.prepare(AudioSource.LocalFile(file.path), PlaybackParams(autoPlay = true))
                    ended.await()
                }

            assertThat(outcome).isEqualTo(PlaybackEvent.TrackEnded)
        }

    companion object {
        private val TIMEOUT = 10.seconds

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun names() = listOf("Plain.mp3", "Track #1.mp3", "What?.mp3", "100% Pure.mp3", "Ёлка — Прованс.mp3")
    }
}
