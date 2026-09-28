package io.github.puflik.plinth.ffi

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import io.github.puflik.plinth.ffi.generated.Format as RustFormat
import io.github.puflik.plinth.ffi.generated.NetAnswer as RustNetAnswer
import io.github.puflik.plinth.ffi.generated.NetRequest as RustNetRequest
import io.github.puflik.plinth.ffi.generated.NetTransport as RustNetTransport
import io.github.puflik.plinth.ffi.generated.OnlineKind as RustOnlineKind
import io.github.puflik.plinth.ffi.generated.OnlineProblem as RustOnlineProblem
import io.github.puflik.plinth.ffi.generated.OnlineResult as RustOnlineResult
import io.github.puflik.plinth.ffi.generated.OnlineSearch as RustOnlineSearch
import io.github.puflik.plinth.ffi.generated.OnlineTrackInfo as RustOnlineTrackInfo
import io.github.puflik.plinth.ffi.generated.OnlineVariant as RustOnlineVariant
import io.github.puflik.plinth.ffi.generated.StreamAddress as RustStreamAddress

/**
 * Онлайн-источники ядра (E3) — `api/online_api.rs`. Сеть делает приложение
 * ([NetTransport]), политику (User-Agent, повторы, здоровье провайдеров) —
 * ядро. Результаты поиска и треки альбома живут в памяти; в каталог трек
 * попадает при действии — [add].
 *
 * Сеть, мусор провайдера и пропавший трек объясняют экраны (ADR 0010):
 * отказы [album] и [stream] в `CoreErrors` не идут.
 */
class CoreOnline internal constructor(
    private val core: PlinthCore,
) {
    /** Включает источники поверх [transport]; повторный вызов сбрасывает здоровье провайдеров. */
    fun connect(transport: NetTransport) = core.call { it.connectOnline(transport.toRust()) }

    fun disconnect() = core.call { it.disconnectOnline() }

    /**
     * Секция на каждого провайдера. Источники выключены или запрос пуст —
     * секций нет. Блокирует на время ответа сети.
     */
    fun search(
        query: String,
        limit: Int,
    ): List<OnlineSection> {
        require(limit > 0) { "limit: $limit" }
        return core.call { rust -> rust.onlineSearch(query, limit.toUInt()).map { it.toApp() } }
    }

    /** Треки собрания [item] провайдера [provider]; выключено, нет сети, пропало — [CoreFailure]. */
    fun album(
        provider: String,
        item: String,
    ): List<OnlineTrack> = core.quietCall { rust -> rust.onlineAlbum(provider, item).map { it.toApp() } }

    /** Какие из [tracks] уже в каталоге — ID или `null`, в том же порядке; каталог не меняется. */
    fun known(
        provider: String,
        tracks: List<OnlineTrack>,
    ): List<TrackId?> =
        core.call { rust ->
            rust.knownOnlineTracks(provider, tracks.map(OnlineTrack::toRust)).map { it?.let(::TrackId) }
        }

    /** Заводит [tracks] в каталоге — ID в том же порядке; уже заведённые находятся. */
    fun add(
        provider: String,
        tracks: List<OnlineTrack>,
    ): List<TrackId> =
        core
            .call { rust -> rust.addOnlineTracks(provider, tracks.map(OnlineTrack::toRust)).map(::TrackId) }
            .also { core.catalogChanged() }

    /**
     * Адрес потока трека каталога — в момент загрузки: [metered] — сотовая
     * сеть (MP3), иначе лучший вариант. Выключено или вариантов нет —
     * [CoreFailure] вида `Unavailable`.
     */
    fun stream(
        track: TrackId,
        metered: Boolean,
    ): StreamAddress = core.quietCall { it.onlineStream(track.value, metered).toApp() }
}

/** Сеть платформы для ядра: один GET без повторов, ответ с любым статусом — [NetAnswer.Response]. */
fun interface NetTransport {
    fun get(request: NetRequest): NetAnswer
}

/**
 * Запрос ядра: [headers] — по порядку; тело длиннее [maxBodyBytes] —
 * [NetAnswer.TooLarge].
 */
data class NetRequest(
    val url: String,
    val headers: List<Pair<String, String>>,
    val timeout: Duration,
    val maxBodyBytes: Long,
)

sealed interface NetAnswer {
    class Response(
        val status: Int,
        val body: ByteArray,
    ) : NetAnswer

    /** Ответа нет: нет сети, таймаут, оборвалось. [reason] — без адреса: в нём бывает запрос. */
    data class NoAnswer(
        val reason: String,
    ) : NetAnswer

    data object TooLarge : NetAnswer
}

enum class OnlineKind {
    TRACK,

    /** Собрание: альбом, концерт, элемент Internet Archive. */
    ALBUM,
}

/**
 * Почему секции нечего показать: нет сети; провайдер не ответил несколько
 * раз подряд и отдыхает; ответ не понять.
 */
enum class OnlineProblem {
    NO_NETWORK,
    PROVIDER_DOWN,
    BROKEN,
}

/** Секция поиска: провайдер и его результаты — или беда. */
data class OnlineSection(
    val provider: String,
    val results: List<OnlineResult>,
    val problem: OnlineProblem? = null,
)

data class OnlineResult(
    val external: String,
    val kind: OnlineKind,
    val title: String,
    val artist: String? = null,
    val year: Int? = null,
    val duration: Duration? = null,
)

enum class AudioFormat {
    FLAC,
    ALAC,
    WAV,
    AIFF,
    MP3,
    AAC,
    VORBIS,
    OPUS,
    OTHER,
}

/** Вариант источника трека: FLAC, MP3, Ogg. */
data class OnlineVariant(
    val external: String,
    val format: AudioFormat,
    val bitrateKbps: Int? = null,
)

/** Трек альбома провайдера — ещё не в каталоге. */
data class OnlineTrack(
    val external: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val number: Int? = null,
    val year: Int? = null,
    val duration: Duration? = null,
    val mbid: String? = null,
    val variants: List<OnlineVariant>,
)

/** Что отдать плееру: адрес и заголовки. */
data class StreamAddress(
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
)

/**
 * Сеть приложения как колбэк ядра. Исключение через границу не пропускается:
 * ядро ждёт ответ, поэтому любой отказ — «нет ответа» с именем причины.
 */
internal fun NetTransport.toRust(): RustNetTransport =
    object : RustNetTransport {
        @Suppress("TooGenericExceptionCaught") // Колбэк ядра не бросает: любая беда сети — «нет ответа».
        override fun get(request: RustNetRequest): RustNetAnswer =
            try {
                this@toRust.get(request.toApp()).toRust()
            } catch (e: Exception) {
                RustNetAnswer.NoAnswer(e.javaClass.simpleName)
            }
    }

private fun RustNetRequest.toApp() =
    NetRequest(
        url = url,
        headers = headers.map { it.name to it.value },
        timeout = timeoutMs.toLong().milliseconds,
        maxBodyBytes = maxBodyBytes.toLong(),
    )

private fun NetAnswer.toRust(): RustNetAnswer =
    when (this) {
        is NetAnswer.Response -> RustNetAnswer.Response(status.toUShort(), body)
        is NetAnswer.NoAnswer -> RustNetAnswer.NoAnswer(reason)
        NetAnswer.TooLarge -> RustNetAnswer.TooLarge
    }

internal fun RustOnlineSearch.toApp() = OnlineSection(provider, results.map { it.toApp() }, problem?.toApp())

private fun RustOnlineResult.toApp() =
    OnlineResult(
        external = external,
        kind =
            when (kind) {
                RustOnlineKind.TRACK -> OnlineKind.TRACK
                RustOnlineKind.ALBUM -> OnlineKind.ALBUM
            },
        title = title,
        artist = artist,
        year = year?.toInt(),
        duration = durationMs?.toLong()?.milliseconds,
    )

private fun RustOnlineProblem.toApp() =
    when (this) {
        RustOnlineProblem.NO_NETWORK -> OnlineProblem.NO_NETWORK
        RustOnlineProblem.PROVIDER_DOWN -> OnlineProblem.PROVIDER_DOWN
        RustOnlineProblem.BROKEN -> OnlineProblem.BROKEN
    }

internal fun RustOnlineTrackInfo.toApp() =
    OnlineTrack(
        external = external,
        title = title,
        artist = artist,
        album = album,
        number = number?.toInt(),
        year = year?.toInt(),
        duration = durationMs?.toLong()?.milliseconds,
        mbid = mbid,
        variants = variants.map { OnlineVariant(it.external, it.format.toApp(), it.bitrateKbps?.toInt()) },
    )

internal fun OnlineTrack.toRust() =
    RustOnlineTrackInfo(
        external = external,
        title = title,
        artist = artist,
        album = album,
        number = number?.toUShort(),
        year = year?.toUShort(),
        durationMs = duration?.inWholeMilliseconds?.toULong(),
        mbid = mbid,
        variants = variants.map { RustOnlineVariant(it.external, it.format.toRust(), it.bitrateKbps?.toUInt()) },
    )

internal fun RustStreamAddress.toApp() = StreamAddress(url, headers.map { it.name to it.value })

internal fun AudioFormat.toRust() =
    when (this) {
        AudioFormat.FLAC -> RustFormat.FLAC
        AudioFormat.ALAC -> RustFormat.ALAC
        AudioFormat.WAV -> RustFormat.WAV
        AudioFormat.AIFF -> RustFormat.AIFF
        AudioFormat.MP3 -> RustFormat.MP3
        AudioFormat.AAC -> RustFormat.AAC
        AudioFormat.VORBIS -> RustFormat.VORBIS
        AudioFormat.OPUS -> RustFormat.OPUS
        AudioFormat.OTHER -> RustFormat.OTHER
    }

internal fun RustFormat.toApp() =
    when (this) {
        RustFormat.FLAC -> AudioFormat.FLAC
        RustFormat.ALAC -> AudioFormat.ALAC
        RustFormat.WAV -> AudioFormat.WAV
        RustFormat.AIFF -> AudioFormat.AIFF
        RustFormat.MP3 -> AudioFormat.MP3
        RustFormat.AAC -> AudioFormat.AAC
        RustFormat.VORBIS -> AudioFormat.VORBIS
        RustFormat.OPUS -> AudioFormat.OPUS
        RustFormat.OTHER -> AudioFormat.OTHER
    }
