package io.github.puflik.plinth.audio.media3

import androidx.media3.common.MediaItem
import io.github.puflik.plinth.audio.engine.AudioSource

/**
 * `AudioSource` → `MediaItem` (B2.2).
 *
 * Идентификатор элемента — [AudioSource.key]: по нему источник узнаётся в
 * логах и событиях плеера.
 */
internal object MediaItemMapper {
    /**
     * @throws UnsupportedOperationException если у потока есть заголовки:
     *   передавать их в запрос плеер пока не умеет. Они нужны сетевым
     *   провайдерам v0.2 и придут вместе с ними; молча потерять заголовок
     *   авторизации хуже, чем упасть.
     */
    fun map(source: AudioSource): MediaItem =
        when (source) {
            is AudioSource.LocalFile -> item(source.key, source.uri)
            is AudioSource.Remote -> {
                if (source.headers.isNotEmpty()) {
                    throw UnsupportedOperationException("заголовки потока пока не поддержаны: ${source.headers.keys}")
                }
                item(source.key, source.url)
            }
        }

    private fun item(
        id: String,
        uri: String,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(id)
            .setUri(uri)
            .build()
}
