package io.github.puflik.plinth.spike

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.utils.JavaScript
import java.io.IOException

/**
 * Спайк Ю3а (одноразовый код, в `main` не идёт): гейт перед провайдером YouTube.
 * Сценарии:
 * - `cold`  — инициализация, первый поиск, первый поток (замер холодного старта);
 * - `all`   — `cold` + четыре поиска + выбор потока + игра в Media3 + Mix `RD<id>`.
 */
class SpikeRunner(
    private val context: Context,
    private val report: Report,
) {
    private val downloader = SpikeDownloader(report)
    private val localization = Localization.DEFAULT
    private val processStart = Process.getStartElapsedRealtime()

    private class Candidate(
        val title: String,
        val artist: String,
        val url: String,
        val durationSec: Long,
    ) {
        val videoId: String get() = Uri.parse(url).getQueryParameter("v").orEmpty()
    }

    private class Playable(
        val stream: AudioStream,
        val userAgent: String,
    )

    private class PlayResult {
        var playedMs = 0L
        var bytes = 0L
        var requests = 0
        var timedOut = false
        var startMs = -1L
        var stalls = 0
        var stallMs = 0L
        val errors = ArrayList<String>()
        val seekResumeMs = ArrayList<Long>()
        val ok get() = errors.isEmpty() && !timedOut
    }

    suspend fun run(scenario: String) =
        withContext(Dispatchers.IO) {
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            report.line(
                "SCENARIO $scenario sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.first()} " +
                    "pkg=${context.packageName} debuggable=$debuggable runner=${SpikeRunner::class.java.name}",
            )
            val sinceStart = SystemClock.elapsedRealtime() - processStart
            report.line("T process start -> SpikeRunner.run: $sinceStart ms")
            try {
                val t0 = SystemClock.elapsedRealtime()
                NewPipe.init(downloader, localization, ContentCountry.DEFAULT)
                report.line("T NewPipe.init: ${SystemClock.elapsedRealtime() - t0} ms")

                val first = coldPath()
                if (scenario == "cold" || first == null) return@withContext
                when (scenario) {
                    "probe" -> {
                        probe(first.second)
                        return@withContext
                    }
                    "rhino" -> {
                        rhino(first.first.videoId)
                        return@withContext
                    }
                    "net" -> {
                        netDiag(first.second.stream.content)
                        return@withContext
                    }
                }
                searchMatrix()
                playChecks(first.first, first.second)
                mix(first.first)
                warmPath()
            } catch (t: Throwable) {
                report.error("run", t)
            } finally {
                report.line("DONE $scenario")
            }
        }

    /** Первый поиск и первый поток после холодного старта. */
    private fun coldPath(): Pair<Candidate, Playable>? {
        val t0 = SystemClock.elapsedRealtime()
        val songs = search("Daft Punk Get Lucky", YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
        val tSearch = SystemClock.elapsedRealtime() - t0
        report.line("T FIRST SEARCH (music_songs, latin): $tSearch ms; since process start ${SystemClock.elapsedRealtime() - processStart} ms")
        printItems("songs/latin", songs)
        val candidates = songs.filterIsInstance<StreamInfoItem>().filter { it.duration in MIN_SEC..MAX_SEC }
        for (item in candidates.take(3)) {
            val candidate = Candidate(item.name, item.uploaderName.orEmpty(), item.url, item.duration)
            try {
                val t1 = SystemClock.elapsedRealtime()
                val playable = loadStream(candidate)
                report.line(
                    "T FIRST STREAM (fetchPage + audioStreams): ${SystemClock.elapsedRealtime() - t1} ms; " +
                        "since process start ${SystemClock.elapsedRealtime() - processStart} ms",
                )
                return candidate to (playable ?: continue)
            } catch (t: Throwable) {
                report.error("first stream for ${candidate.videoId}", t)
            }
        }
        return null
    }

    private fun search(
        query: String,
        filter: String,
    ): List<InfoItem> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        extractor.fetchPage()
        return extractor.initialPage.items
    }

    private fun printItems(
        label: String,
        items: List<InfoItem>,
    ) {
        var withAll = 0
        for (item in items) {
            when (item) {
                is StreamInfoItem -> if (item.name.isNotBlank() && !item.uploaderName.isNullOrBlank() && item.duration > 0) withAll++
                is PlaylistInfoItem -> if (item.name.isNotBlank() && !item.uploaderName.isNullOrBlank()) withAll++
                else -> {}
            }
        }
        report.line("SEARCH $label: ${items.size} items, $withAll with name+artist(+duration for songs)")
        items.take(SHOWN).forEachIndexed { i, item ->
            when (item) {
                is StreamInfoItem -> report.line("  #$i song '${item.name}' / '${item.uploaderName}' / ${item.duration}s / ${item.url.substringAfter("watch?v=")}")
                is PlaylistInfoItem ->
                    report.line(
                        "  #$i album '${item.name}' / '${item.uploaderName}' / tracks=${item.streamCount} / ${item.url.substringAfter("list=")}",
                    )
                else -> report.line("  #$i ${item.javaClass.simpleName} '${item.name}'")
            }
        }
    }

    private fun searchMatrix() {
        val matrix =
            listOf(
                Triple("songs/cyrillic", "Кино Звезда по имени Солнце", YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                Triple("albums/latin", "Daft Punk Random Access Memories", YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS),
                Triple("albums/cyrillic", "Кино Группа крови", YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS),
            )
        for ((label, query, filter) in matrix) {
            try {
                val t = SystemClock.elapsedRealtime()
                val items = search(query, filter)
                report.line("T search $label: ${SystemClock.elapsedRealtime() - t} ms")
                printItems(label, items)
            } catch (t: Throwable) {
                report.error("search $label", t)
            }
        }
    }

    /** Поток для трека: Opus 251, иначе AAC 140, иначе лучший по битрейту. */
    private fun loadStream(candidate: Candidate): Playable? {
        val extractor = ServiceList.YouTube.getStreamExtractor(candidate.url)
        extractor.fetchPage()
        val streams = extractor.audioStreams
        report.line("STREAMS for ${candidate.videoId} '${extractor.name}' len=${extractor.length}s: ${streams.size} audio")
        for (s in streams) {
            val url = s.content
            val uri = Uri.parse(url)
            val expireIn = uri.getQueryParameter("expire")?.toLongOrNull()?.let { it - System.currentTimeMillis() / 1000 }
            report.line(
                "  itag=${s.itag} fmt=${s.format} avg=${s.averageBitrate}kbps bitrate=${s.bitrate} codec=${s.codec} " +
                    "delivery=${s.deliveryMethod} track=${s.audioTrackId}/${s.audioTrackType} " +
                    "clen=${uri.getQueryParameter("clen")} c=${uri.getQueryParameter("c")} host=${uri.host?.substringAfter('.')} " +
                    "expireIn=${expireIn?.let { "${it / 60}min" }}",
            )
        }
        val picked =
            streams.firstOrNull { it.itag == ITAG_OPUS }
                ?: streams.firstOrNull { it.itag == ITAG_AAC }
                ?: streams.maxByOrNull { it.averageBitrate }
                ?: return null
        report.line("PICKED itag=${picked.itag} fmt=${picked.format} avg=${picked.averageBitrate}kbps")
        return Playable(picked, userAgentFor(picked.content))
    }

    private fun userAgentFor(url: String): String =
        when {
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(localization)
            else -> "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
        }

    /** Игра в Media3: сначала как есть в Plinth (свой User-Agent), потом с User-Agent клиента. */
    private suspend fun playChecks(
        candidate: Candidate,
        playable: Playable,
    ) {
        val url = playable.stream.content
        report.line("UA of client = '${playable.userAgent}'")
        val stock = playOnce("STOCK: as in Plinth now (no Range on first request), UA 'Plinth'", url, "Plinth", 15_000, withSeeks = false, rangeFix = false)
        report.line(
            "PLAY RESULT stock: ok=${stock.ok} played=${stock.playedMs}ms startMs=${stock.startMs} stalls=${stock.stalls}/${stock.stallMs}ms " +
                "bytes=${stock.bytes} errors=${stock.errors}",
        )
        val main = playOnce("FIX: Range bytes=0- added, client UA, itag=${playable.stream.itag}", url, playable.userAgent, 60_000, withSeeks = true, rangeFix = true)
        report.line(
            "PLAY RESULT fix itag=${playable.stream.itag}: ok=${main.ok} played=${main.playedMs}ms startMs=${main.startMs} " +
                "stalls=${main.stalls}/${main.stallMs}ms bytes=${main.bytes} requests=${main.requests} seekResume=${main.seekResumeMs} errors=${main.errors}",
        )
        try {
            alternateCheck(candidate, playable)
        } catch (t: Throwable) {
            report.error("alternate format", t)
        }
    }

    /** Второй формат (AAC, если выбран Opus, и наоборот) — 20 с с одной перемоткой. */
    private suspend fun alternateCheck(
        candidate: Candidate,
        picked: Playable,
    ) {
        val extractor = ServiceList.YouTube.getStreamExtractor(candidate.url)
        extractor.fetchPage()
        val other =
            extractor.audioStreams.firstOrNull { it.itag == if (picked.stream.itag == ITAG_OPUS) ITAG_AAC else ITAG_OPUS }
        if (other == null) {
            report.line("ALT format: none available (picked itag=${picked.stream.itag})")
            return
        }
        val result = playOnce("alt format itag=${other.itag}", other.content, userAgentFor(other.content), 20_000, withSeeks = true, rangeFix = true)
        report.line(
            "PLAY RESULT alt itag=${other.itag}: ok=${result.ok} played=${result.playedMs}ms startMs=${result.startMs} " +
                "stalls=${result.stalls}/${result.stallMs}ms seekResume=${result.seekResumeMs} errors=${result.errors}",
        )
    }

    @OptIn(UnstableApi::class)
    private suspend fun playOnce(
        label: String,
        url: String,
        userAgent: String,
        targetPlayedMs: Long,
        withSeeks: Boolean,
        rangeFix: Boolean,
    ): PlayResult =
        withContext(Dispatchers.Main) {
            report.line("PLAY START $label (target ${targetPlayedMs}ms, seeks=$withSeeks)")
            val result = PlayResult()
            val transfers =
                object : TransferListener {
                    override fun onTransferInitializing(
                        source: DataSource,
                        dataSpec: DataSpec,
                        isNetwork: Boolean,
                    ) = Unit

                    override fun onTransferStart(
                        source: DataSource,
                        dataSpec: DataSpec,
                        isNetwork: Boolean,
                    ) {
                        result.requests++
                        report.line("  REQ #${result.requests} pos=${dataSpec.position} len=${dataSpec.length} range-header=${dataSpec.httpRequestHeaders["Range"]}")
                    }

                    override fun onBytesTransferred(
                        source: DataSource,
                        dataSpec: DataSpec,
                        isNetwork: Boolean,
                        bytesTransferred: Int,
                    ) {
                        result.bytes += bytesTransferred
                    }

                    override fun onTransferEnd(
                        source: DataSource,
                        dataSpec: DataSpec,
                        isNetwork: Boolean,
                    ) = Unit
                }
            val http =
                DefaultHttpDataSource
                    .Factory()
                    .setUserAgent(userAgent)
                    .setTransferListener(transfers)
            // Как в Plinth: ResolvingDataSource над DefaultDataSource. С rangeFix добавляет Range: bytes=0-
            // (DefaultHttpDataSource сам шлёт Range только если позиция не 0 или длина известна).
            val dataFactory: DataSource.Factory =
                if (rangeFix) {
                    ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http)) { spec ->
                        spec.buildUpon().setHttpRequestHeaders(spec.httpRequestHeaders + mapOf("Range" to "bytes=0-")).build()
                    }
                } else {
                    DefaultDataSource.Factory(context, http)
                }
            val player =
                ExoPlayer
                    .Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataFactory))
                    .setAudioAttributes(MUSIC, true)
                    .build()
            player.volume = 0.15f
            player.addListener(
                object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val cause = error.cause
                        val code = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                        val text = "${error.errorCodeName} cause=${cause?.javaClass?.simpleName} http=$code msg=${cause?.message}"
                        result.errors += text
                        report.line("  PLAYER ERROR $text")
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        report.line("  state=$state pos=${player.currentPosition}")
                    }

                    override fun onPlayWhenReadyChanged(
                        playWhenReady: Boolean,
                        reason: Int,
                    ) {
                        report.line("  playWhenReady=$playWhenReady reason=$reason")
                    }
                },
            )
            player.addAnalyticsListener(
                object : AnalyticsListener {
                    override fun onLoadError(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData,
                        error: IOException,
                        wasCanceled: Boolean,
                    ) {
                        val code = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                        report.line("  LOAD ERROR ${error.javaClass.simpleName} http=$code canceled=$wasCanceled msg=${error.message}")
                    }
                },
            )
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true

            val plan =
                listOf<Pair<Long, (Long) -> Long>>(
                    10_000L to { d -> if (d > 0) d * 35 / 100 else 30_000 },
                    22_000L to { d -> if (d > 0) (d - 40_000).coerceAtLeast(0) else 60_000 },
                    34_000L to { _ -> 5_000 },
                    46_000L to { d -> if (d > 0) d * 60 / 100 else 90_000 },
                )
            val started = SystemClock.elapsedRealtime()
            var last = started
            var played = 0L
            var nextSeek = 0
            var pendingTarget = -1L
            var pendingAt = 0L
            var lastLog = started
            var stalling = false
            while (true) {
                delay(POLL_MS)
                val now = SystemClock.elapsedRealtime()
                val playing = player.isPlaying
                if (playing && result.startMs < 0) result.startMs = now - started
                if (!playing && result.startMs >= 0 && pendingTarget < 0 && player.playbackState == Player.STATE_BUFFERING) {
                    result.stallMs += now - last
                    if (!stalling) result.stalls++
                    stalling = true
                } else {
                    stalling = false
                }
                if (playing) played += now - last
                last = now
                if (pendingTarget >= 0 && playing && player.currentPosition >= pendingTarget + 300) {
                    result.seekResumeMs += now - pendingAt
                    report.line("  seek to ${pendingTarget}ms resumed after ${now - pendingAt}ms")
                    pendingTarget = -1
                }
                if (withSeeks && pendingTarget < 0 && nextSeek < plan.size && played >= plan[nextSeek].first) {
                    val target = plan[nextSeek].second(player.duration.takeIf { it != C.TIME_UNSET } ?: -1)
                    report.line("  SEEK #${nextSeek + 1} to ${target}ms (duration=${player.duration}ms, played=${played}ms)")
                    player.seekTo(target)
                    pendingTarget = target
                    pendingAt = now
                    nextSeek++
                }
                if (now - lastLog >= LOG_EVERY_MS) {
                    lastLog = now
                    report.line(
                        "  wall=${now - started}ms played=${played}ms pos=${player.currentPosition} buffered=${player.bufferedPosition} " +
                            "state=${player.playbackState} playing=$playing bytes=${result.bytes}",
                    )
                }
                if (result.errors.isNotEmpty()) break
                if (played >= targetPlayedMs && pendingTarget < 0) break
                if (now - started > targetPlayedMs + TIMEOUT_EXTRA_MS) {
                    result.timedOut = true
                    report.line("  TIMEOUT: wall=${now - started}ms played=${played}ms")
                    break
                }
            }
            result.playedMs = played
            player.release()
            result
        }

    /** Какие параметры у адреса потока (только имена) и есть ли `n` — нужен ли Rhino. */
    private fun urlShape(url: String): String {
        val uri = Uri.parse(url)
        return uri.queryParameterNames.sorted().joinToString(",")
    }

    /** Скорость чтения одного и того же адреса при разных видах Range (HttpURLConnection, как у DefaultHttpDataSource). */
    private fun probe(playable: Playable) {
        val url = playable.stream.content
        report.line("PROBE url params: ${urlShape(url)}")
        report.line("PROBE host: ${Uri.parse(url).host?.substringBefore('.')}")
        fun fetch(
            label: String,
            ua: String,
            range: String?,
            extra: String = "",
            maxMs: Long = 8_000,
            maxBytes: Long = 6_000_000,
        ) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val connection = java.net.URL(url + extra).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", ua)
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (range != null) connection.setRequestProperty("Range", range)
                val code = connection.responseCode
                val ttfb = SystemClock.elapsedRealtime() - t0
                val contentRange = connection.getHeaderField("Content-Range")
                val length = connection.getHeaderField("Content-Length")
                var total = 0L
                var firstSecond = 0L
                var error: String? = null
                try {
                    val stream = if (code >= 400) connection.errorStream else connection.inputStream
                    val buffer = ByteArray(64 * 1024)
                    stream?.use {
                        while (true) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - t0 > maxMs || total >= maxBytes) break
                            val n = it.read(buffer)
                            if (n < 0) break
                            total += n
                            if (SystemClock.elapsedRealtime() - t0 <= ttfb + 1_000) firstSecond = total
                        }
                    }
                } catch (e: IOException) {
                    error = "${e.javaClass.simpleName}: ${e.message}"
                }
                val ms = SystemClock.elapsedRealtime() - t0
                val readMs = (ms - ttfb).coerceAtLeast(1)
                report.line(
                    "PROBE $label: code=$code ttfb=${ttfb}ms contentRange=$contentRange contentLength=$length " +
                        "bytes=$total in ${readMs}ms = ${total * 1000 / readMs / 1024} KiB/s (first second after headers: ${firstSecond / 1024} KiB)" +
                        (error?.let { " ERROR $it" } ?: ""),
                )
                connection.disconnect()
            } catch (e: IOException) {
                report.line("PROBE $label: FAILED ${e.javaClass.simpleName}: ${e.message} after ${SystemClock.elapsedRealtime() - t0}ms")
            }
        }
        val ua = playable.userAgent
        fetch("A open-ended bytes=0-   (client UA)", ua, "bytes=0-")
        fetch("B closed bytes=0-1048575 (client UA)", ua, "bytes=0-1048575")
        fetch("C url &range=0-1048575, no Range header", ua, null, "&range=0-1048575")
        fetch("D open-ended bytes=2000000- (client UA)", ua, "bytes=2000000-")
        fetch("E open-ended bytes=0-   (second time)", ua, "bytes=0-")
        fetch("F open-ended bytes=0-   (UA 'Plinth')", "Plinth", "bytes=0-")
        fetch("G no Range header at all (client UA)", ua, null)
        fetch("H url &range=0-  (open, client UA)", ua, null, "&range=0-")
    }

    /** Явно гоняем Rhino: адрес с `n` и подпись - так, как их разбирал бы экстрактор у клиента с шифром. */
    private fun rhino(videoId: String) {
        try {
            val js = "function f(a){var o=[];for(var i=0;i<a.length;i++){o.push(a.charCodeAt(i)+1);}return o.join(',');}"
            val js2 = "function g(a){return JSON.stringify({x:a.replace(/b/g,'B'),n:[1,2,3].map(function(v){return v*2;}),d:new Date(0).getTime(),s:'x'.padStart(3,'-')});}"
            val tj = SystemClock.elapsedRealtime()
            val r1 = JavaScript.run(js, "f", "abc")
            report.line("T RHINO direct JavaScript.run #1 (Rhino init+compile+run): ${SystemClock.elapsedRealtime() - tj} ms -> '$r1' (want 98,99,100)")
            val tk = SystemClock.elapsedRealtime()
            val r2 = JavaScript.run(js2, "g", "abc")
            report.line("T RHINO direct JavaScript.run #2 (regex, JSON, map, Date, padStart): ${SystemClock.elapsedRealtime() - tk} ms -> $r2")
            val t0 = SystemClock.elapsedRealtime()
            val ts = YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
            report.line("T RHINO getSignatureTimestamp (player JS download+parse): ${SystemClock.elapsedRealtime() - t0} ms -> $ts")
            val n = "AbCdEfGhIjKlMnOpQr"
            val fake = "https://rr1---sn-test.googlevideo.com/videoplayback?expire=1999999999&itag=251&n=$n"
            val t1 = SystemClock.elapsedRealtime()
            val out = YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, fake)
            report.line(
                "T RHINO n-param FIRST (extract function + Rhino compile + run): ${SystemClock.elapsedRealtime() - t1} ms; " +
                    "changed=${!out.contains("n=$n")} newN=${Uri.parse(out).getQueryParameter("n")}",
            )
            val t2 = SystemClock.elapsedRealtime()
            val out2 = YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, fake.replace(n, "ZyXwVuTsRqPoNmLkJi"))
            report.line("T RHINO n-param SECOND (cached function): ${SystemClock.elapsedRealtime() - t2} ms; newN=${Uri.parse(out2).getQueryParameter("n")}")
            val t3 = SystemClock.elapsedRealtime()
            val sig = YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, "A".repeat(10) + "0123456789abcdefghijklmnopqrstuvwxyz".repeat(3))
            report.line("T RHINO signature FIRST: ${SystemClock.elapsedRealtime() - t3} ms -> len=${sig.length}")
        } catch (t: Throwable) {
            report.error("rhino", t)
        }
    }

    /** Для службы: инициализация, поиск, поток; возвращает (адрес, User-Agent) или null. */
    fun prepareForService(): Pair<String, String>? {
        NewPipe.init(downloader, localization, ContentCountry.DEFAULT)
        val first = coldPath() ?: return null
        return first.second.stream.content to first.second.userAgent
    }

    /** Сеть: тип, адреса, DNS, подключение по IPv4 и IPv6 к хостам YouTube и к хосту потока. */
    private fun netDiag(streamUrl: String) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val lp = net?.let { cm.getLinkProperties(it) }
        val transports =
            listOf(
                "CELLULAR" to android.net.NetworkCapabilities.TRANSPORT_CELLULAR,
                "WIFI" to android.net.NetworkCapabilities.TRANSPORT_WIFI,
                "VPN" to android.net.NetworkCapabilities.TRANSPORT_VPN,
                "ETHERNET" to android.net.NetworkCapabilities.TRANSPORT_ETHERNET,
            ).filter { caps?.hasTransport(it.second) == true }.map { it.first }
        report.line(
            "NET active transports=$transports validated=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                "notVpn=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)} " +
                "notMetered=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}",
        )
        report.line(
            "NET link iface=${lp?.interfaceName} v4addrs=${lp?.linkAddresses?.count { it.address is java.net.Inet4Address }} " +
                "v6addrs=${lp?.linkAddresses?.count { it.address is java.net.Inet6Address }} dns=${lp?.dnsServers?.joinToString { it.hostAddress.orEmpty() }} " +
                "defaultRoutes(v4/v6)=${lp?.routes?.count { it.isDefaultRoute && it.gateway is java.net.Inet4Address }}/${lp?.routes?.count { it.isDefaultRoute && it.gateway is java.net.Inet6Address }}",
        )
        val hosts = listOfNotNull("music.youtube.com", "www.youtube.com", "youtubei.googleapis.com", Uri.parse(streamUrl).host)
        for (h in hosts) {
            val short = h.substringBefore('.')
            val t0 = SystemClock.elapsedRealtime()
            val addrs =
                try {
                    java.net.InetAddress.getAllByName(h).toList()
                } catch (e: Exception) {
                    report.line("NET DNS $short.. FAILED ${e.javaClass.simpleName}: ${e.message}")
                    continue
                }
            report.line(
                "NET DNS $short.. -> ${addrs.size} addrs, order=${addrs.joinToString("") { if (it is java.net.Inet6Address) "6" else "4" }} " +
                    "in ${SystemClock.elapsedRealtime() - t0} ms",
            )
            for (a in addrs.take(6)) {
                val t1 = SystemClock.elapsedRealtime()
                val family = if (a is java.net.Inet6Address) "v6" else "v4"
                try {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(a, 443), 5_000) }
                    report.line("NET connect $short.. $family ok in ${SystemClock.elapsedRealtime() - t1} ms")
                } catch (e: Exception) {
                    report.line("NET connect $short.. $family FAILED ${e.javaClass.simpleName}: ${e.message} after ${SystemClock.elapsedRealtime() - t1} ms")
                }
            }
        }
    }

    /** Mix `RD<id>` — радио: длина, продолжение, поля. */
    private fun mix(seed: Candidate) {
        val url = "https://www.youtube.com/watch?v=${seed.videoId}&list=RD${seed.videoId}"
        try {
            val t = SystemClock.elapsedRealtime()
            val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
            extractor.fetchPage()
            val page1 = extractor.initialPage
            report.line("T mix fetch: ${SystemClock.elapsedRealtime() - t} ms")
            val items = page1.items
            val streams = items.filterIsInstance<StreamInfoItem>()
            report.line(
                "MIX RD${seed.videoId} (${extractor.javaClass.simpleName}) name='${extractor.name}': page1=${items.size} items " +
                    "(${streams.size} streams, ${streams.count { it.duration > 0 }} with duration, " +
                    "${streams.count { !it.uploaderName.isNullOrBlank() }} with artist), hasNext=${page1.hasNextPage()}",
            )
            streams.take(SHOWN).forEachIndexed { i, s ->
                report.line("  #$i '${s.name}' / '${s.uploaderName}' / ${s.duration}s / ${s.url.substringAfter("watch?v=").substringBefore('&')}")
            }
            report.line("  first item is seed: ${streams.firstOrNull()?.url?.contains(seed.videoId)}")
            if (page1.hasNextPage()) {
                val t2 = SystemClock.elapsedRealtime()
                val page2 = extractor.getPage(page1.nextPage)
                report.line("MIX page2: ${page2.items.size} items hasNext=${page2.hasNextPage()} (${SystemClock.elapsedRealtime() - t2} ms)")
            }
        } catch (t: Throwable) {
            report.error("mix", t)
        }
    }

    /** Тёплые замеры: второй поиск и второй поток в том же процессе. */
    private fun warmPath() {
        try {
            val t = SystemClock.elapsedRealtime()
            val items = search("Radiohead Creep", YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
            report.line("T WARM SEARCH: ${SystemClock.elapsedRealtime() - t} ms (${items.size} items)")
            val song = items.filterIsInstance<StreamInfoItem>().firstOrNull { it.duration in MIN_SEC..MAX_SEC } ?: return
            val t1 = SystemClock.elapsedRealtime()
            loadStream(Candidate(song.name, song.uploaderName.orEmpty(), song.url, song.duration))
            report.line("T WARM STREAM: ${SystemClock.elapsedRealtime() - t1} ms")
        } catch (t: Throwable) {
            report.error("warm", t)
        }
    }

    private companion object {
        const val ITAG_OPUS = 251
        const val ITAG_AAC = 140
        const val MIN_SEC = 120L
        const val MAX_SEC = 600L
        const val SHOWN = 5
        const val POLL_MS = 200L
        const val LOG_EVERY_MS = 5_000L
        const val TIMEOUT_EXTRA_MS = 90_000L

        val MUSIC: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
    }
}
