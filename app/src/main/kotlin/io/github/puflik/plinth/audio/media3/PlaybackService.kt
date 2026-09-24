package io.github.puflik.plinth.audio.media3

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import io.github.puflik.plinth.R
import io.github.puflik.plinth.audio.PlaybackController
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
        session = MediaSession.Builder(this, sessionPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
