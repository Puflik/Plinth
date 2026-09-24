package io.github.puflik.plinth.audio.media3

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.TrackInfo

/**
 * `AudioSource` → `MediaItem` (B2.2).
 *
 * Идентификатор элемента — [AudioSource.key]: по нему источник узнаётся в
 * логах и событиях плеера. Подписи [TrackInfo] становятся `mediaMetadata`
 * элемента — их видит сессия, а значит, уведомление и экран блокировки.
 * ExoPlayer ставит их выше тегов файла, а пустые поля добирает из тегов.
 */
internal object MediaItemMapper {
    /**
     * @throws UnsupportedOperationException если у потока есть заголовки:
     *   передавать их в запрос плеер пока не умеет. Они нужны сетевым
     *   провайдерам v0.2 и придут вместе с ними; молча потерять заголовок
     *   авторизации хуже, чем упасть.
     */
    fun map(
        source: AudioSource,
        info: TrackInfo? = null,
    ): MediaItem =
        when (source) {
            is AudioSource.LocalFile -> item(source.key, source.uri, info)
            is AudioSource.Remote -> {
                if (source.headers.isNotEmpty()) {
                    throw UnsupportedOperationException("заголовки потока пока не поддержаны: ${source.headers.keys}")
                }
                item(source.key, source.url, info)
            }
        }

    private fun item(
        id: String,
        uri: String,
        info: TrackInfo?,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(id)
            .setUri(uri)
            .apply { info?.let { setMediaMetadata(metadata(it)) } }
            .build()

    private fun metadata(info: TrackInfo): MediaMetadata =
        MediaMetadata
            .Builder()
            .setTitle(info.title)
            .setArtist(info.artist)
            .setAlbumTitle(info.album)
            .build()
}
