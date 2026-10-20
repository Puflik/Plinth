package io.github.puflik.plinth.audio.media3

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.StreamLookup
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Чтение сетевого трека, на которое сервер ответил 403 (Ю3в, `yu3a.md`, раздел 8): ссылка YouTube привязана к IP
 * и к сроку, и на телефоне свежая ссылка бывала отвергнута. Загрузчик ExoPlayer повторял бы ту же ссылку и получал
 * тот же отказ; здесь на первый 403 загрузки адрес забывается и спрашивается заново — один раз на загрузку.
 * Остальные отказы, файлы и обычные адреса идут как есть.
 *
 * Источник данных — настоящие `ResolvingDataSource` и `OnlineResolver`, а сеть под ними — сценарий статусов.
 * `ReResolvingDataSource(upstream, resolver)` — новый API (сигнатура в `docs/work/yu3v.md`).
 */
@OptIn(UnstableApi::class)
class ReResolvingDataSourceTest {
    private val asked = mutableListOf<String>()
    private val lookups = ArrayDeque<StreamLookup>()
    private val network = ScriptedNetwork()

    private val resolver =
        OnlineResolver { track ->
            asked += track
            lookups.removeFirst()
        }
    private val source: DataSource = ReResolvingDataSource(ResolvingDataSource(network, resolver), resolver)

    private fun answers(
        vararg paths: String,
        statuses: List<Int>,
    ) {
        paths.forEach { lookups += StreamLookup.Found("https://rr1.test/$it", mapOf("X-Client" to "yt")) }
        network.statuses += statuses
    }

    @Test
    fun a_refusal_of_the_address_is_answered_with_a_new_address_once() {
        answers("a", "b", statuses = listOf(403, 200))

        val length = source.open(DataSpec(link()))

        assertThat(length).isEqualTo(LENGTH)
        assertThat(network.opened.map { it.uri.toString() })
            .containsExactly("https://rr1.test/a", "https://rr1.test/b")
            .inOrder()
        assertThat(asked).hasSize(2)
    }

    @Test
    fun the_refused_attempt_is_closed_before_the_second_one_opens() {
        answers("a", "b", statuses = listOf(403, 200))

        source.open(DataSpec(link()))

        assertThat(network.events).containsExactly("open:a", "close", "open:b").inOrder()
    }

    @Test
    fun the_new_attempt_asks_for_the_same_bytes_and_carries_the_headers_of_the_new_address() {
        answers("a", "b", statuses = listOf(403, 200))
        val request =
            DataSpec
                .Builder()
                .setUri(link())
                .setPosition(4_096)
                .setLength(1_000)
                .build()

        source.open(request)

        assertThat(network.opened.map { it.position }).containsExactly(4_096L, 4_096L)
        assertThat(network.opened.map { it.length }).containsExactly(1_000L, 1_000L)
        assertThat(network.opened.map { it.httpRequestHeaders["X-Client"] }).containsExactly("yt", "yt")
    }

    @Test
    fun a_second_refusal_in_the_same_load_reaches_the_player_with_its_status() {
        answers("a", "b", statuses = listOf(403, 403))

        val refusal = refusalOf { source.open(DataSpec(link())) }

        assertThat(refusal.responseCode).isEqualTo(403)
        assertThat(asked).hasSize(2)
        assertThat(network.opened).hasSize(2)
    }

    @Test
    fun the_players_own_retries_of_that_load_keep_the_last_address_and_ask_nothing() {
        val load = link()
        answers("a", "b", statuses = listOf(403, 403, 403, 403, 403))
        refusalOf { source.open(DataSpec(load)) }

        repeat(3) {
            refusalOf { source.open(DataSpec(load)) }
        }

        assertThat(asked).hasSize(2)
        assertThat(network.opened.map { it.uri.toString() }.distinct())
            .containsExactly("https://rr1.test/a", "https://rr1.test/b")
            .inOrder()
        assertThat(lastOpened()).isEqualTo("https://rr1.test/b")
    }

    @Test
    fun every_load_has_its_own_retry() {
        val first = link()
        answers("a", "b", statuses = listOf(403, 403))
        refusalOf { source.open(DataSpec(first)) }
        answers("c", "d", statuses = listOf(403, 200))

        source.open(DataSpec(link()))

        assertThat(lastOpened()).isEqualTo("https://rr1.test/d")
        assertThat(asked).hasSize(4)
    }

    @Test
    fun other_refusals_are_not_answered() {
        for (status in listOf(404, 410, 416, 429, 500, 503)) {
            answers("a", statuses = listOf(status))
            val before = asked.size

            val refusal = refusalOf { source.open(DataSpec(link())) }

            assertThat(refusal.responseCode).isEqualTo(status)
            assertThat(asked.size - before).isEqualTo(1)
        }
    }

    @Test
    fun a_refusal_of_an_address_that_is_not_ours_is_left_alone() {
        network.statuses += 403

        refusalOf { source.open(DataSpec(Uri.parse("https://example.org/stream.opus"))) }

        assertThat(asked).isEmpty()
        assertThat(network.opened).hasSize(1)
    }

    @Test
    fun a_lookup_that_fails_on_the_retry_ends_the_open_with_its_reason_and_the_next_open_asks_again() {
        val load = link()
        lookups += StreamLookup.Found("https://rr1.test/a")
        lookups += StreamLookup.NoNetwork
        lookups += StreamLookup.Found("https://rr1.test/c")
        network.statuses += listOf(403, 200)

        val failure = assertThrows(DataSourceException::class.java) { source.open(DataSpec(load)) }
        source.open(DataSpec(load))

        assertThat(failure.reason).isEqualTo(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertThat(lastOpened()).isEqualTo("https://rr1.test/c")
        assertThat(asked).hasSize(3)
    }

    @Test
    fun reading_the_address_and_closing_go_to_the_opened_stream() {
        answers("a", statuses = listOf(200))
        source.open(DataSpec(link()))

        val read = source.read(ByteArray(8), 0, 8)
        val uri = source.uri
        source.close()

        assertThat(read).isEqualTo(3)
        assertThat(uri.toString()).isEqualTo("https://rr1.test/a")
        assertThat(network.events.last()).isEqualTo("close")
    }

    @Test
    fun transfer_listeners_reach_the_stream() {
        source.addTransferListener(Silent)

        assertThat(network.listeners).isEqualTo(1)
    }

    private object Silent : TransferListener {
        override fun onTransferInitializing(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) = Unit

        override fun onTransferStart(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) = Unit

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) = Unit

        override fun onTransferEnd(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) = Unit
    }

    /** Сеть под источником: на каждое открытие — следующий статус из сценария (200 — открылось). */
    private class ScriptedNetwork : DataSource {
        val statuses = ArrayDeque<Int>()
        val opened = mutableListOf<DataSpec>()
        val events = mutableListOf<String>()
        var listeners = 0
        private var current: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners++
        }

        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec
            events += "open:" + dataSpec.uri.lastPathSegment
            current = dataSpec.uri
            val status = statuses.removeFirst()
            if (status != 200) {
                throw HttpDataSource.InvalidResponseCodeException(
                    status,
                    null,
                    null,
                    emptyMap(),
                    dataSpec,
                    ByteArray(0),
                )
            }
            return LENGTH
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = if (length > 0) 3 else C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = current

        override fun close() {
            events += "close"
        }
    }

    private fun refusalOf(open: () -> Unit): HttpDataSource.InvalidResponseCodeException =
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java, open)

    private fun lastOpened(): String {
        val uri = network.opened.last().uri
        return uri.toString()
    }

    private fun link(): Uri = checkNotNull(MediaItemMapper.map(AudioSource.Online(TRACK)).localConfiguration).uri

    private companion object {
        const val TRACK = "0192f7c4-0000-7000-8000-00000000000a"
        const val LENGTH = 4_096L
    }
}
