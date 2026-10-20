package io.github.puflik.plinth.audio.media3

import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.StreamLookup
import io.github.puflik.plinth.audio.engine.StreamResolver
import io.github.puflik.plinth.online.TinyHttpServer
import io.github.puflik.plinth.online.TinyHttpServer.Reply
import io.github.puflik.plinth.online.TinyHttpServer.Request
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Настоящий ExoPlayer, собранный `ExoPlayerFactory`, читает сетевой трек с сервера на петле (Ю3в): ссылка раскрывается
 * `OnlineResolver`, заголовки провайдера доходят до сети, а отказ 403 стоит плееру одного повторного вопроса — и он
 * всё равно доигрывает до готовности. Страж проводки: `ExoPlayerFactory` обязан собирать источник данных через
 * `onlineDataSourceFactory`, иначе остальные тесты пути его не заметят.
 *
 * HTTP на `127.0.0.1` открыт только debug-сборке (`app/src/debug/res/xml/network_security_config.xml`).
 */
class ExoPlayerStreamTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val wav: ByteArray =
        File(instrumentation.targetContext.cacheDir, "yu3v-stream.wav").run {
            SilentWav.write(this, 2.seconds)
            readBytes().also { delete() }
        }
    private var respond: (Request) -> Reply = { rangedReply(wav, it) }
    private val server = TinyHttpServer { respond(it) }
    private var player: ExoPlayer? = null
    private val asked = AtomicInteger()

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { player?.release() }
        server.close()
    }

    @Test
    fun the_player_asks_the_server_as_the_client_the_provider_named() {
        val outcome =
            prepare {
                found(
                    "videoplayback?n=${asked.incrementAndGet()}",
                    "User-Agent" to "yt-client/1.0",
                    "Range" to "bytes=0-",
                )
            }

        assertThat(outcome.ready).isTrue()
        val first = server.requests.first()
        assertThat(first.headers["User-Agent"]).isEqualTo("yt-client/1.0")
        assertThat(first.headers["Range"]).isEqualTo("bytes=0-")
        assertThat(first.path).startsWith("/videoplayback?n=1")
    }

    @Test
    fun without_a_user_agent_from_the_provider_the_player_is_the_app() {
        val outcome = prepare { found("plain.wav") }

        assertThat(outcome.ready).isTrue()
        assertThat(server.requests.first().headers["User-Agent"]).isEqualTo("Plinth/test")
    }

    @Test
    fun a_refused_address_costs_one_more_question_and_the_player_is_ready_anyway() {
        respond = { request -> if ("n=1" in request.path) Reply(FORBIDDEN) else rangedReply(wav, request) }

        val outcome =
            prepare { found("videoplayback?n=${asked.incrementAndGet()}", "User-Agent" to "yt-client/1.0") }

        assertThat(outcome.ready).isTrue()
        assertThat(asked.get()).isEqualTo(2)
        assertThat(server.requests.map { it.path.substringAfter("n=").substringBefore('&') }.take(2))
            .containsExactly("1", "2")
            .inOrder()
    }

    @Test
    fun a_refusal_that_repeats_ends_in_a_playback_error_after_two_questions_for_the_first_open() {
        respond = { Reply(FORBIDDEN) }

        val outcome = prepare { found("videoplayback?n=${asked.incrementAndGet()}") }

        assertThat(outcome.ready).isFalse()
        assertThat(outcome.error?.errorCode).isEqualTo(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        assertThat(asked.get()).isEqualTo(2)
    }

    /** Готов ли плеер к игре или чем кончилась подготовка. */
    private class Outcome(
        val ready: Boolean,
        val error: PlaybackException?,
    )

    private fun found(
        path: String,
        vararg headers: Pair<String, String>,
    ) = StreamLookup.Found("${server.url}/$path", headers.toMap())

    private fun prepare(lookup: () -> StreamLookup): Outcome {
        val done = CountDownLatch(1)
        var ready = false
        var error: PlaybackException? = null
        val streams = StreamResolver { lookup() }
        instrumentation.runOnMainSync {
            val factory = ExoPlayerFactory(instrumentation.targetContext, streams, "Plinth/test")
            val exo = factory.create(Looper.getMainLooper())
            player = exo
            exo.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            ready = true
                            done.countDown()
                        }
                    }

                    override fun onPlayerError(playerError: PlaybackException) {
                        error = playerError
                        done.countDown()
                    }
                },
            )
            exo.setMediaItem(MediaItemMapper.map(AudioSource.Online("0192f7c4-0000-7000-8000-00000000000a")))
            exo.prepare()
        }
        assertThat(done.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        return Outcome(ready, error)
    }

    private companion object {
        const val FORBIDDEN = 403
        const val WAIT_SECONDS = 60L
    }
}
