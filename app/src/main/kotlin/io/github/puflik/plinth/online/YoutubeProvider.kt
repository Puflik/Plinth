package io.github.puflik.plinth.online

import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineProvider
import io.github.puflik.plinth.ffi.OnlineQuality
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.OnlineVariant
import io.github.puflik.plinth.ffi.ProviderException
import io.github.puflik.plinth.ffi.StreamAddress
import io.github.puflik.plinth.online.youtube.YoutubeException
import io.github.puflik.plinth.online.youtube.YoutubeFormat
import io.github.puflik.plinth.online.youtube.YoutubeKind
import io.github.puflik.plinth.online.youtube.YoutubeResult
import io.github.puflik.plinth.online.youtube.YoutubeSource
import io.github.puflik.plinth.online.youtube.YoutubeTrack
import io.github.puflik.plinth.online.youtube.YoutubeVariant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * YouTube как провайдер ядра (Ю3в): [YoutubeSource] на языке [OnlineProvider]. Имя и формат идентификаторов уходят
 * в каталог и журнал пользователя и после выпуска не меняются: имя `youtube`, трек — `<видео>`, вариант —
 * `<видео>/<itag>` (251 — Opus 160, 140 — AAC 128). Идентификаторы идут из источника в ядро и обратно дословно.
 *
 * Лежит не в `online/youtube/`: пакет источника ядра не знает (`YoutubeBoundaryTest`).
 */
class YoutubeProvider(
    private val youtube: YoutubeSource,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : OnlineProvider {
    override val id = "youtube"
    override val finds = setOf(OnlineKind.TRACK, OnlineKind.ALBUM)
    override val bestQuality = OnlineQuality.STANDARD

    override fun search(
        query: String,
        limit: Int,
    ): List<OnlineResult> = asked { youtube.search(query, limit).map(YoutubeResult::toApp) }

    override fun resolve(item: String): List<OnlineTrack> = asked { youtube.resolve(item).map(YoutubeTrack::toApp) }

    override fun stream(source: String): StreamAddress =
        asked {
            val stream = youtube.stream(source)
            StreamAddress(stream.url, stream.headers, validFor(stream.url))
        }

    /**
     * Сколько ссылка ещё годна: `expire=` (секунды Unix) из её запроса без десяти минут запаса, не больше двух часов.
     * Нет внятного `expire` — полчаса. Часы устройства могут врать, поэтому срок — совет плееру, а страховка от
     * просроченной ссылки — повтор после 403.
     */
    private fun validFor(url: String): Duration {
        val expire = expireSeconds(url) ?: return NO_EXPIRY_LIFE
        val left = (expire * MILLIS_IN_SECOND).milliseconds - wallClock().milliseconds - MARGIN
        return left.coerceIn(Duration.ZERO, MAX_LIFE)
    }

    /** `expire` — параметр запроса целиком (не `xexpire`), только цифры и не длиннее [MAX_EXPIRE_DIGITS]. */
    private fun expireSeconds(url: String): Long? =
        url
            .substringAfter('?', "")
            .substringBefore('#')
            .split('&')
            .firstOrNull { it.substringBefore('=') == "expire" && it.contains('=') }
            ?.substringAfter('=')
            ?.takeIf { it.length in 1..MAX_EXPIRE_DIGITS && it.all(Char::isDigit) }
            ?.toLong()

    private companion object {
        val MARGIN = 10.minutes
        val MAX_LIFE = 2.hours
        val NO_EXPIRY_LIFE = 30.minutes
        const val MILLIS_IN_SECOND = 1000L
        const val MAX_EXPIRE_DIGITS = 12
    }
}

/** Беда источника — того же вида и с тем же текстом, исходное исключение в `cause`; прочее идёт наружу как есть. */
private inline fun <T> asked(call: () -> T): T =
    try {
        call()
    } catch (e: YoutubeException) {
        throw e.toProvider()
    }

private fun YoutubeException.toProvider(): ProviderException =
    when (this) {
        is YoutubeException.Network -> ProviderException.Network(text, this)
        is YoutubeException.Parse -> ProviderException.Parse(text, this)
        is YoutubeException.Unavailable -> ProviderException.Unavailable(text, this)
    }

private fun YoutubeResult.toApp() =
    OnlineResult(
        external = id,
        kind =
            when (kind) {
                YoutubeKind.TRACK -> OnlineKind.TRACK
                YoutubeKind.ALBUM -> OnlineKind.ALBUM
            },
        title = title,
        artist = artist,
        year = null,
        duration = duration,
    )

private fun YoutubeTrack.toApp() =
    OnlineTrack(
        external = id,
        title = title,
        artist = artist,
        album = album,
        number = number,
        year = null,
        duration = duration,
        mbid = null,
        variants = variants.map(YoutubeVariant::toApp),
    )

private fun YoutubeVariant.toApp() =
    OnlineVariant(
        external = id,
        format =
            when (format) {
                YoutubeFormat.OPUS -> AudioFormat.OPUS
                YoutubeFormat.AAC -> AudioFormat.AAC
            },
        bitrateKbps = bitrateKbps,
    )
