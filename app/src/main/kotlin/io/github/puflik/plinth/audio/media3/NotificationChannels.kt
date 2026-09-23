package io.github.puflik.plinth.audio.media3

/**
 * Каналы уведомлений (B3.1).
 *
 * Канал воспроизведения создаёт сам Media3 (`DefaultMediaNotificationProvider`)
 * по этому идентификатору и имени из ресурсов. Каналы сканирования и
 * диагностики появятся вместе со сканером (C2) и логированием (G1).
 */
object NotificationChannels {
    const val PLAYBACK = "playback"
}
