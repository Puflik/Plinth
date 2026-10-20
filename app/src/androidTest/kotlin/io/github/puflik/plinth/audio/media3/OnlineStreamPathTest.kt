package io.github.puflik.plinth.audio.media3

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.StreamLookup
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.NetAnswer
import io.github.puflik.plinth.ffi.NetTransport
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.online.CoreOnlineRepository
import io.github.puflik.plinth.online.FakeYoutubeSource
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.SONG
import io.github.puflik.plinth.online.OnlineAlbum
import io.github.puflik.plinth.online.OnlineStreams
import io.github.puflik.plinth.online.TinyHttpServer
import io.github.puflik.plinth.online.TinyHttpServer.Reply
import io.github.puflik.plinth.online.TinyHttpServer.Request
import io.github.puflik.plinth.online.YoutubeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.hours

/**
 * Путь потока YouTube от ядра до сети (Ю3в): провайдер приложения → внешний `trait` → ядро → `OnlineStreams` →
 * `OnlineResolver` → источник данных Media3 → настоящий HTTP на петле. Проверяется то, что видит сервер:
 * `User-Agent` клиента, выдавшего ссылку, а не приложения (`DefaultHttpDataSource.setUserAgent` его перекрывал),
 * `Range: bytes=0-` с первого байта, повторный вопрос после 403 — один, и ссылка, которая прожила свой срок, второй
 * раз не используется.
 *
 * HTTP на `127.0.0.1` открыт только debug-сборке (`app/src/debug/res/xml/network_security_config.xml`).
 */
@OptIn(UnstableApi::class)
class OnlineStreamPathTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val body = ByteArray(BODY_SIZE) { (it % MOD).toByte() }
    private var respond: (Request) -> Reply = { rangedReply(body, it) }
    private val server = TinyHttpServer { respond(it) }
    private val youtube = FakeYoutubeSource().apply { linkBase = server.url }
    private val dataDir = File(context.cacheDir, "yu3v-path-" + UUID.randomUUID())
    private val core = PlinthCore(LogLevel.INFO, dataDir, CoreErrors())
    private var nanos = 0L

    private val repository =
        CoreOnlineRepository(
            core,
            NetTransport { NetAnswer.Response(NOT_FOUND, ByteArray(0)) },
            Dispatchers.IO,
            listOf(YoutubeProvider(youtube)),
        )
    private val resolver = OnlineResolver(clock = { nanos }, streams = OnlineStreams(repository) { false })
    private val data: DataSource = onlineDataSourceFactory(context, "Plinth/test", resolver).createDataSource()

    @After
    fun tearDown() {
        data.close()
        server.close()
        core.close()
        dataDir.deleteRecursively()
    }

    @Test
    fun the_stream_is_read_with_the_user_agent_of_the_client_and_a_range_from_the_first_byte() {
        val link = trackLink()

        data.open(DataSpec(link))
        val bytes = readAll()

        val request = server.requests.single()
        assertThat(request.headers["User-Agent"]).isEqualTo(FakeYoutubeSource.UA)
        assertThat(request.headers["Range"]).isEqualTo("bytes=0-")
        assertThat(request.path).contains("source=dQw4w9WgXcQ/251")
        assertThat(bytes).isEqualTo(body)
    }

    @Test
    fun a_seek_asks_from_its_byte_and_keeps_the_user_agent_of_the_client() {
        val link = trackLink()
        data.open(DataSpec(link))
        data.close()

        data.open(seekTo(link))
        val bytes = readAll()

        assertThat(server.requests.map { it.headers["Range"] }).containsExactly("bytes=0-", "bytes=$SEEK-").inOrder()
        assertThat(server.requests.map { it.headers["User-Agent"] }.distinct()).containsExactly(FakeYoutubeSource.UA)
        assertThat(bytes).isEqualTo(body.copyOfRange(SEEK.toInt(), BODY_SIZE))
    }

    @Test
    fun an_address_without_headers_is_read_as_the_app_and_without_a_range() {
        val plain = OnlineResolver { StreamLookup.Found("${server.url}/plain.opus") }
        val source = onlineDataSourceFactory(context, "Plinth/test", plain).createDataSource()

        source.open(DataSpec(link()))
        source.close()

        val request = server.requests.single()
        assertThat(request.headers["User-Agent"]).isEqualTo("Plinth/test")
        assertThat(request.headers).doesNotContainKey("Range")
    }

    @Test
    fun a_refused_address_is_asked_again_once_and_the_new_one_plays() {
        respond = { request -> if ("n=1&" in request.path) Reply(FORBIDDEN) else rangedReply(body, request) }
        val link = trackLink()

        data.open(DataSpec(link))
        val bytes = readAll()

        assertThat(bytes).isEqualTo(body)
        assertThat(linkNumbers())
            .containsExactly("1", "2")
            .inOrder()
        assertThat(youtube.calls.filter { it.startsWith("stream:") }).hasSize(2)
    }

    @Test
    fun a_refusal_that_repeats_ends_with_the_refusal_after_two_requests_and_two_questions() {
        respond = { Reply(FORBIDDEN) }
        val link = trackLink()

        val refusal = refusalOf { data.open(DataSpec(link)) }

        assertThat(refusal.responseCode).isEqualTo(FORBIDDEN)
        assertThat(server.requests).hasSize(2)
        assertThat(youtube.calls.filter { it.startsWith("stream:") }).hasSize(2)
        data.close()
        refusalOf { data.open(DataSpec(link)) }
        assertThat(youtube.calls.filter { it.startsWith("stream:") }).hasSize(2)
    }

    @Test
    fun an_address_that_has_lived_its_time_is_not_used_again() {
        val link = trackLink()
        data.open(DataSpec(link))
        data.close()

        nanos = 2.hours.inWholeNanoseconds - 1
        data.open(seekTo(link))
        data.close()
        nanos = 2.hours.inWholeNanoseconds
        data.open(seekTo(link))
        data.close()

        assertThat(linkNumbers())
            .containsExactly("1", "1", "2")
            .inOrder()
    }

    /** Номера ссылок (`n=`), по которым сервер видел запросы, по порядку. */
    private fun linkNumbers(): List<String> = server.requests.map { numberOf(it.path) }

    private fun numberOf(path: String): String = path.substringAfter("n=").substringBefore('&')

    private fun seekTo(link: Uri): DataSpec =
        DataSpec
            .Builder()
            .setUri(link)
            .setPosition(SEEK)
            .build()

    private fun refusalOf(open: () -> Unit): HttpDataSource.InvalidResponseCodeException =
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java, open)

    private fun readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_SIZE)
        while (true) {
            val count = data.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /** Ссылка на загрузку трека, заведённого в каталоге из выдачи YouTube. */
    private fun trackLink(): Uri =
        runBlocking {
            repository.setEnabled(true)
            val tracks = (repository.album("youtube", SONG) as OnlineAlbum.Tracks).tracks
            val ids = checkNotNull(repository.add("youtube", tracks))
            link(ids.single().value)
        }

    private fun link(track: String = TRACK): Uri =
        checkNotNull(MediaItemMapper.map(AudioSource.Online(track)).localConfiguration).uri

    private companion object {
        const val BODY_SIZE = 4_096
        const val MOD = 251
        const val SEEK = 1_000L
        const val READ_SIZE = 1_024
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val TRACK = "0192f7c4-0000-7000-8000-00000000000a"
    }
}
