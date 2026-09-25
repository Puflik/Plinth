package io.github.puflik.plinth.audio.media3

import android.app.PendingIntent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import io.github.puflik.plinth.R
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.QueueKeeper
import io.github.puflik.plinth.audio.engine.TrackInfo
import io.github.puflik.plinth.di.ApplicationScope
import io.github.puflik.plinth.diagnostics.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Фоновое воспроизведение (B3.1 📌): `MediaSessionService` с сессией на
 * плеере приложения.
 *
 * Сессия — дверь для внешних пультов: уведомление, гарнитура, Bluetooth,
 * экран блокировки. Плеер у неё тот же, что у движка (`AudioModule`),
 * поэтому команда с пульта доходит до движка, а экран видит её через
 * `AudioEngine.state`. Foreground-режимом и уведомлением управляет Media3:
 * служба выходит на передний план, пока звук идёт.
 *
 * Плеер служба не освобождает: он живёт столько же, сколько процесс.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject
    lateinit var player: ExoPlayer

    @Inject
    lateinit var playback: PlaybackController

    @Inject
    lateinit var keeper: QueueKeeper

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider
                .Builder(this)
                .setChannelId(NotificationChannels.PLAYBACK)
                .setChannelName(R.string.notification_channel_playback)
                .build(),
        )
        // Соседние треки знает очередь приложения, а не ExoPlayer с его единственным треком.
        val sessionPlayer = QueueCommandsPlayer(player, onNext = playback::next, onPrevious = playback::previous)
        val builder = MediaSession.Builder(this, sessionPlayer).setCallback(Resumption())
        openApp()?.let(builder::setSessionActivity)
        session = builder.build()
        AppLog.i(TAG, "created")
    }

    /**
     * Медиакнопка, когда службы нет (ревью №12): её поднимает
     * `MediaButtonReceiver` из манифеста, и если плеер пуст — процесс начался
     * с этой кнопки, — сессия спрашивает, что играть. Играем сохранённую
     * очередь с той же секунды: её восстанавливает [QueueKeeper] при старте
     * процесса, дожидаемся его. Если процесс жив и трек в плеере, Media3 просто
     * включает его — сюда не приходит.
     */
    private inner class Resumption : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val resumed = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                keeper.restored()
                val item = playback.queue.value.current
                if (item == null) {
                    resumed.setException(UnsupportedOperationException("nothing saved to resume"))
                } else {
                    val media = MediaItemMapper.map(item.source, TrackInfo(item.title, item.artist, item.album))
                    val position = playback.progress.value.position.inWholeMilliseconds
                    resumed.set(MediaSession.MediaItemsWithStartPosition(listOf(media), 0, position))
                }
            }
            return resumed
        }
    }

    /**
     * Что открывает касание уведомления и медиакарточки (ревью №5): своего
     * умолчания у Media3 нет. Намерение запуска — у системы: служба не знает
     * об экранах, а запуск из лаунчера выводит вперёд уже открытую задачу.
     */
    private fun openApp(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        AppLog.i(TAG, "destroyed")
        session?.release()
        session = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PlaybackService"
    }
}
