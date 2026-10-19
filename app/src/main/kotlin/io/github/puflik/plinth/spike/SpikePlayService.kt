package io.github.puflik.plinth.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Спайк Ю3а (одноразовый код): игра в фоне. Служба переднего плана (mediaPlayback) ищет трек, берёт поток,
 * играет заданное число секунд и пишет раз в секунду строку в `yu3a-bg-report.txt`. Разрыв между тиками
 * больше 2,5 с (по elapsedRealtime) - признак того, что систему заморозила процесс.
 */
class SpikePlayService : Service() {
    private var player: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val channel = "yu3a"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(channel, "Yu3a spike", NotificationManager.IMPORTANCE_LOW))
        val notification =
            Notification
                .Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Yu3a spike: playing")
                .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        val seconds = intent?.getIntExtra("seconds", 75) ?: 75
        val file = File(getExternalFilesDir(null), "yu3a-bg-report.txt")
        file.delete()
        val report = Report(file)
        scope.launch { play(report, seconds) }
        return START_NOT_STICKY
    }

    @OptIn(UnstableApi::class)
    private suspend fun play(
        report: Report,
        seconds: Int,
    ) {
        var errors = ""
        try {
            report.line("BG service started sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} target=${seconds}s")
            val prepared = withContext(Dispatchers.IO) { SpikeRunner(applicationContext, report).prepareForService() }
            if (prepared == null) {
                report.line("BG no stream")
                return
            }
            val (url, userAgent) = prepared
            val http = DefaultHttpDataSource.Factory().setUserAgent(userAgent)
            val data =
                ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
                    spec.buildUpon().setHttpRequestHeaders(spec.httpRequestHeaders + mapOf("Range" to "bytes=0-")).build()
                }
            val p =
                ExoPlayer
                    .Builder(this)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(data))
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        true,
                    ).setWakeMode(C.WAKE_MODE_NETWORK)
                    .build()
            player = p
            p.volume = 0.15f
            p.addListener(
                object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        errors += "${error.errorCodeName}/${error.cause?.javaClass?.simpleName};"
                        report.line("BG PLAYER ERROR ${error.errorCodeName} ${error.cause}")
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        report.line("BG isPlaying=$isPlaying pos=${p.currentPosition}")
                    }
                },
            )
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
            val power = getSystemService(POWER_SERVICE) as PowerManager
            val started = SystemClock.elapsedRealtime()
            var last = started
            var played = 0L
            var gaps = 0
            var maxGap = 0L
            var offScreenPlayed = 0L
            while (played < seconds * 1000L && SystemClock.elapsedRealtime() - started < (seconds + 90) * 1000L && errors.isEmpty()) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val gap = now - last
                if (gap > 2500) {
                    gaps++
                    maxGap = maxOf(maxGap, gap)
                    report.line("BG GAP ${gap}ms between ticks (process frozen?)")
                }
                val interactive = power.isInteractive
                if (p.isPlaying) {
                    played += gap
                    if (!interactive) offScreenPlayed += gap
                }
                last = now
                report.line(
                    "BG wall=${now - started}ms played=${played}ms pos=${p.currentPosition} buffered=${p.bufferedPosition} " +
                        "state=${p.playbackState} playing=${p.isPlaying} screenOn=$interactive",
                )
            }
            report.line("BG RESULT played=${played}ms playedWithScreenOff=${offScreenPlayed}ms gaps=$gaps maxGap=${maxGap}ms errors=[$errors]")
        } catch (t: Throwable) {
            report.error("bg", t)
        } finally {
            player?.release()
            player = null
            report.line("DONE bg")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        player?.release()
        super.onDestroy()
    }
}
