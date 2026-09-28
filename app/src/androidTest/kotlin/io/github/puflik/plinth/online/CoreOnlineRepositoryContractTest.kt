package io.github.puflik.plinth.online

import androidx.test.platform.app.InstrumentationRegistry
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.NetAnswer
import io.github.puflik.plinth.ffi.NetRequest
import io.github.puflik.plinth.ffi.NetTransport
import io.github.puflik.plinth.ffi.PlinthCore
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.UUID

/**
 * `CoreOnlineRepository` проходит общий контракт онлайн-источников (E3b) —
 * тот же класс, что `FakeOnlineRepository` проходит на JVM. Ядро настоящее,
 * провайдер — настоящий Internet Archive ядра; сеть отвечает так, как
 * ответил бы archive.org с концертом [TestConcert].
 */
class CoreOnlineRepositoryContractTest : OnlineRepositoryContractTest() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    override fun world(): World = CoreWorld(File(context.cacheDir, "core-online-" + UUID.randomUUID()))

    override fun close(world: World) = (world as CoreWorld).close()

    private class CoreWorld(
        private val dataDir: File,
    ) : World {
        private val core = PlinthCore(LogLevel.INFO, dataDir, CoreErrors())

        override var networkUp = true

        override val online = CoreOnlineRepository(core, ConcertArchive { networkUp }, Dispatchers.IO)

        fun close() {
            core.close()
            dataDir.deleteRecursively()
        }
    }

    /** Сеть с archive.org, на котором один [TestConcert]; [up] — есть ли сеть. */
    private class ConcertArchive(
        private val up: () -> Boolean,
    ) : NetTransport {
        override fun get(request: NetRequest): NetAnswer {
            if (!up()) return NetAnswer.NoAnswer("UnknownHostException")
            val url = request.url
            return when {
                url.startsWith("https://archive.org/advancedsearch.php?") -> ok(SEARCH)
                url == "https://archive.org/metadata/${TestConcert.ITEM}" -> ok(metadata())
                url.startsWith("https://archive.org/metadata/") -> ok("{}")
                else -> NetAnswer.Response(NOT_FOUND, ByteArray(0))
            }
        }

        private fun ok(json: String) = NetAnswer.Response(OK, json.toByteArray())

        private fun metadata(): String {
            val files =
                TestConcert.files.mapIndexed { index, (file, millis) ->
                    val title = file.substringAfter(' ')
                    val number = (index + 1).toString().padStart(2, '0')
                    val tags = """"title": "$title", "creator": "${TestConcert.ARTIST}", "track": "$number""""
                    """
                    {"name": "$file.flac", "source": "original", "format": "Flac", "length": "${millis / 1000.0}", $tags},
                    {"name": "$file.mp3", "source": "derivative", "format": "VBR MP3", "original": "$file.flac",
                     "bitrate": "${TestConcert.MP3_KBPS}", "length": "${millis / 1000.0}", $tags}
                    """.trimIndent()
                }
            return """
                {"metadata": {"identifier": "${TestConcert.ITEM}", "title": "${TestConcert.TITLE}",
                  "creator": "${TestConcert.ARTIST}", "year": "${TestConcert.YEAR}", "mediatype": "etree"},
                 "files": [${files.joinToString(",")}]}
                """.trimIndent()
        }

        private companion object {
            const val OK = 200
            const val NOT_FOUND = 404
            val SEARCH =
                """
                {"response": {"numFound": 1, "start": 0, "docs": [
                  {"identifier": "${TestConcert.ITEM}", "title": "${TestConcert.TITLE}",
                   "creator": "${TestConcert.ARTIST}", "year": ${TestConcert.YEAR}}]}}
                """.trimIndent()
        }
    }
}
