package io.github.puflik.plinth.spike

import android.os.SystemClock
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Спайк Ю3а: минимальный `Downloader` экстрактора на `HttpURLConnection` —
 * GET и POST, gzip (`Accept-Encoding: gzip` ставим сами, разбираем сами),
 * `User-Agent` берётся из запроса экстрактора, а если его там нет — запасной.
 * Ответ 429 — `ReCaptchaException`, как у `DownloaderImpl` в NewPipe.
 *
 * Каждый запрос пишется в журнал без query (в ней бывают посетительские
 * данные): метод, хост и путь, статус, размер тела, время.
 */
class SpikeDownloader(
    private val report: Report,
) : Downloader() {
    @Volatile
    var requests = 0
        private set

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val started = SystemClock.elapsedRealtime()
        val url = request.url()
        val method = request.httpMethod()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_MS
            connection.readTimeout = READ_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", FALLBACK_UA)
            for ((name, values) in request.headers()) {
                values.forEachIndexed { index, value ->
                    if (index == 0) {
                        connection.setRequestProperty(name, value)
                    } else {
                        connection.addRequestProperty(name, value)
                    }
                }
            }
            // Только gzip: br и deflate разбирать нечем, а экстрактор мог попросить и их.
            connection.setRequestProperty("Accept-Encoding", "gzip")
            val payload = request.dataToSend()
            if (payload != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            val failed = status >= HttpURLConnection.HTTP_BAD_REQUEST
            val raw: InputStream? = if (failed) connection.errorStream else connection.inputStream
            val gzip = connection.getHeaderField("Content-Encoding").equals("gzip", ignoreCase = true)
            val bytes =
                if (raw == null || method == "HEAD") {
                    ByteArray(0)
                } else {
                    (if (gzip) GZIPInputStream(raw) else raw).use { it.readAllBytesCompat() }
                }
            val headers =
                connection.headerFields
                    .filterKeys { it != null }
                    .mapKeys { it.key!! }
            val latestUrl = connection.url.toString()
            requests++
            trace(method, url, status, bytes.size, SystemClock.elapsedRealtime() - started, gzip)
            if (status == TOO_MANY_REQUESTS) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            return Response(status, connection.responseMessage ?: "", headers, String(bytes, Charsets.UTF_8), latestUrl)
        } catch (e: IOException) {
            requests++
            report.line("HTTP $method ${shorten(url)} FAILED ${e.javaClass.simpleName}: ${e.message} (${SystemClock.elapsedRealtime() - started} ms)")
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun trace(
        method: String,
        url: String,
        status: Int,
        size: Int,
        ms: Long,
        gzip: Boolean,
    ) {
        report.line("HTTP $method ${shorten(url)} -> $status ${size}B ${ms}ms${if (gzip) " gzip" else ""}")
    }

    private fun shorten(url: String): String {
        val noQuery = url.substringBefore('?')
        return noQuery.removePrefix("https://")
    }

    private fun InputStream.readAllBytesCompat(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
        }
    }

    private companion object {
        const val CONNECT_MS = 15_000
        const val READ_MS = 30_000
        const val BUFFER = 16 * 1024
        const val TOO_MANY_REQUESTS = 429
        const val FALLBACK_UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
