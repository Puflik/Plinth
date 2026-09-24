package io.github.puflik.plinth.artwork

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.artwork.ArtworkSize.FULL
import io.github.puflik.plinth.artwork.ArtworkSize.THUMBNAIL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/** Загрузчик обложек: каждый файл читается один раз и не больше нескольких сразу. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkLoaderTest {
    private val source = FakeArtworkSource().apply { covers["song"] = "cover of song" }

    @Test
    fun `artwork is loaded once and then served from memory`() =
        runTest {
            val loader = loader()

            assertThat(loader.load("song", THUMBNAIL)).isEqualTo("cover of song")
            assertThat(loader.load("song", THUMBNAIL)).isEqualTo("cover of song")

            assertThat(loader.peek("song", THUMBNAIL)).isEqualTo("cover of song")
            assertThat(source.requests).hasSize(1)
        }

    @Test
    fun `file without artwork is not read twice`() =
        runTest {
            val loader = loader()

            assertThat(loader.load("bare", THUMBNAIL)).isNull()
            assertThat(loader.load("bare", THUMBNAIL)).isNull()

            assertThat(loader.peek("bare", THUMBNAIL)).isNull()
            assertThat(source.requests).hasSize(1)
        }

    @Test
    fun `each size is read at its own pixel size`() =
        runTest {
            val loader = loader()

            loader.load("song", THUMBNAIL)
            loader.load("song", FULL)

            assertThat(source.requests)
                .containsExactly("song" to THUMBNAIL.pixels, "song" to FULL.pixels)
                .inOrder()
        }

    @Test
    fun `failed read is not remembered`() =
        runTest {
            val loader = loader()
            source.failure = IOException("file is busy")

            assertThat(loader.load("song", THUMBNAIL)).isNull()
            assertThat(loader.load("song", THUMBNAIL)).isEqualTo("cover of song")
        }

    @Test
    fun `no more files are read at once than allowed`() =
        runTest {
            val loader = loader(parallelism = 2)
            val gate = CompletableDeferred<Unit>().also { source.gate = it }

            val loads = listOf("a", "b", "c").map { async { loader.load(it, THUMBNAIL) } }
            runCurrent()

            assertThat(source.running).isEqualTo(2)
            gate.complete(Unit)
            loads.awaitAll()
            assertThat(source.mostRunning).isEqualTo(2)
            assertThat(source.requests.map { it.first }).containsExactly("a", "b", "c")
        }

    @Test
    fun `request cancelled while waiting reads nothing`() =
        runTest {
            val loader = loader(parallelism = 1)
            val gate = CompletableDeferred<Unit>().also { source.gate = it }
            val first = async { loader.load("a", THUMBNAIL) }
            val second = async { loader.load("b", THUMBNAIL) }
            runCurrent()

            second.cancel()
            gate.complete(Unit)
            first.await()
            runCurrent()

            assertThat(source.requests.map { it.first }).containsExactly("a")
        }

    @Test
    fun `request waiting for the same file takes what the first one read`() =
        runTest {
            val loader = loader(parallelism = 1)
            val gate = CompletableDeferred<Unit>().also { source.gate = it }
            val first = async { loader.load("song", THUMBNAIL) }
            val second = async { loader.load("song", THUMBNAIL) }
            runCurrent()

            gate.complete(Unit)

            assertThat(listOf(first.await(), second.await())).containsExactly("cover of song", "cover of song")
            assertThat(source.requests).hasSize(1)
        }

    private fun loader(parallelism: Int = 4) =
        ArtworkLoader(source, ArtworkCache(maxBytes = 1_000) { it.length }, parallelism)

    /** Обложки-строки; запоминает запросы и сколько их выполняется сразу. */
    private class FakeArtworkSource : ArtworkSource<String> {
        val covers = mutableMapOf<String, String>()
        val requests = mutableListOf<Pair<String, Int>>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        var running = 0
        var mostRunning = 0

        override suspend fun load(
            uri: String,
            size: Int,
        ): String? {
            requests += uri to size
            running++
            mostRunning = maxOf(mostRunning, running)
            try {
                gate?.await()
                failure?.let {
                    failure = null
                    throw it
                }
                return covers[uri]
            } finally {
                running--
            }
        }
    }
}
