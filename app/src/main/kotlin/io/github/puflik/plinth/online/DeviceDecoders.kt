package io.github.puflik.plinth.online

import android.media.MediaCodecList
import io.github.puflik.plinth.ffi.AudioFormat

/**
 * Форматы, которых система не декодирует (F): на Android 8.0 нет FLAC, на
 * 8–11 — ALAC. Ядро ставит такие варианты сетевого трека последними — иначе
 * по Wi-Fi выбирался FLAC и трек не играл, хотя рядом лежит MP3.
 * Считается один раз, при первом адресе потока, — в потоке загрузчика.
 */
object DeviceDecoders {
    private val cached: Set<AudioFormat> by lazy {
        val decoders =
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .filterNot { it.isEncoder }
                .flatMap { info -> info.supportedTypes.map { it.lowercase() } }
                .toSet()
        missingAmong(decoders)
    }

    fun missing(): Set<AudioFormat> = cached

    /** По MIME-типам декодеров — форматы без декодера. PCM (WAV, AIFF) и прочее не в счёт: их играет сам Media3. */
    internal fun missingAmong(decoders: Set<String>): Set<AudioFormat> =
        MIME_TYPES.filterValues { it !in decoders }.keys

    private val MIME_TYPES =
        mapOf(
            AudioFormat.FLAC to "audio/flac",
            AudioFormat.ALAC to "audio/alac",
            AudioFormat.MP3 to "audio/mpeg",
            AudioFormat.AAC to "audio/mp4a-latm",
            AudioFormat.VORBIS to "audio/vorbis",
            AudioFormat.OPUS to "audio/opus",
        )
}
