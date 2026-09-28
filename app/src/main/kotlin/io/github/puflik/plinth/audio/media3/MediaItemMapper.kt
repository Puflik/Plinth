package io.github.puflik.plinth.audio.media3

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.TrackInfo
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * `AudioSource` → `MediaItem` (B2.2).
 *
 * Идентификатор элемента — [AudioSource.key]: по нему источник узнаётся в
 * логах и событиях плеера. Подписи [TrackInfo] становятся `mediaMetadata`
 * элемента — их видит сессия, а значит, уведомление и экран блокировки.
 * ExoPlayer ставит их выше тегов файла, а пустые поля добирает из тегов.
 *
 * Путь к файлу без схемы (фонотека ядра, D3b) становится `file://` через
 * [Uri.fromFile]: прочитанный как адрес, он потерял бы имя после `#` или `?`,
 * а `%` принял бы за экранирование.
 */
internal object MediaItemMapper {
    /**
     * Сетевой трек ([AudioSource.Online]) становится ссылкой
     * `plinth://online/<ID>?load=<N>`: адрес и заголовки провайдера подставит
     * [OnlineResolver] в момент загрузки. `load` у каждой подготовки свой —
     * адрес держится одну загрузку, следующая выбирает его заново.
     *
     * @throws UnsupportedOperationException если у готового адреса
     *   ([AudioSource.Remote]) есть заголовки: их плеер не передаёт — у
     *   провайдеров они идут через [OnlineResolver]. Молча потерять заголовок
     *   авторизации хуже, чем упасть.
     */
    fun map(
        source: AudioSource,
        info: TrackInfo? = null,
    ): MediaItem =
        when (source) {
            is AudioSource.LocalFile -> item(source.key, local(source.uri), info)
            is AudioSource.Remote -> {
                if (source.headers.isNotEmpty()) {
                    throw UnsupportedOperationException("заголовки потока пока не поддержаны: ${source.headers.keys}")
                }
                item(source.key, Uri.parse(source.url), info)
            }
            is AudioSource.Online -> item(source.key, link(source.track), info)
        }

    /** ID сетевого трека из ссылки [map]; файл или адрес — `null`. */
    fun onlineTrack(uri: Uri): String? =
        uri.takeIf { it.scheme == SCHEME && it.authority == ONLINE }?.lastPathSegment?.takeIf(String::isNotBlank)

    private fun link(track: String): Uri =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority(ONLINE)
            .appendPath(track)
            .appendQueryParameter(LOAD, loads.incrementAndGet().toString())
            .build()

    private fun local(uri: String): Uri = if (uri.startsWith('/')) Uri.fromFile(File(uri)) else Uri.parse(uri)

    private val loads = AtomicLong()

    private const val SCHEME = "plinth"
    private const val ONLINE = "online"
    private const val LOAD = "load"

    private fun item(
        id: String,
        uri: Uri,
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
