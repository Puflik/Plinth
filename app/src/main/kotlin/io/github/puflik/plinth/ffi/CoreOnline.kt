package io.github.puflik.plinth.ffi

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import io.github.puflik.plinth.ffi.generated.Format as RustFormat
import io.github.puflik.plinth.ffi.generated.NetAnswer as RustNetAnswer
import io.github.puflik.plinth.ffi.generated.NetHeader as RustNetHeader
import io.github.puflik.plinth.ffi.generated.NetRequest as RustNetRequest
import io.github.puflik.plinth.ffi.generated.NetTransport as RustNetTransport
import io.github.puflik.plinth.ffi.generated.OnlineAlbumReply as RustOnlineAlbumReply
import io.github.puflik.plinth.ffi.generated.OnlineCancel as RustOnlineCancel
import io.github.puflik.plinth.ffi.generated.OnlineKind as RustOnlineKind
import io.github.puflik.plinth.ffi.generated.OnlineOrigin as RustOnlineOrigin
import io.github.puflik.plinth.ffi.generated.OnlineProblem as RustOnlineProblem
import io.github.puflik.plinth.ffi.generated.OnlineRadioReply as RustOnlineRadioReply
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
    /**
     * Включает источники поверх [transport]: Internet Archive (если [archive]) и [providers] приложения (YouTube)
     * в порядке списка. Без [archive] Internet Archive не создаётся — ни одного запроса к нему. Повторный вызов
     * заменяет транспорт и провайдеров и сбрасывает здоровье. Негодный провайдер (имя, пустой `finds`, повтор
     * имени) — [CoreFailure], и прежнее подключение остаётся.
     */
    fun connect(
        transport: NetTransport,
        providers: List<OnlineProvider> = emptyList(),
        archive: Boolean = true,
    ) = core.call { it.connectOnline(transport.toRust(), providers.map(OnlineProvider::toRust), archive) }

    fun disconnect() = core.call { it.disconnectOnline() }

    /**
     * Секция на каждого провайдера. Источники выключены или запрос пуст —
     * секций нет. Блокирует на время ответа сети, но не дольше срока поиска
     * в ядре (15 с); [cancel] обрывает поиск между шагами — ответом будет то,
     * что собрано до отмены. Идущий запрос сети отмена не прерывает: он
     * кончится сам, не позже остатка срока.
     */
    fun search(
        query: String,
        limit: Int,
        cancel: OnlineCancel = OnlineCancel(),
    ): List<OnlineSection> {
        require(limit > 0) { "limit: $limit" }
        return core.call { rust -> rust.onlineSearch(query, limit.toUInt(), cancel.rust).map { it.toApp() } }
    }

    /**
     * Треки собрания [item] провайдера [provider] — или беда провайдера ([OnlineAlbumReply.problem]):
     * нет сети, не отвечает, просит подождать, ответ не понять. Выключено или неверный ID — [CoreFailure].
     */
    fun album(
        provider: String,
        item: String,
    ): OnlineAlbumReply = core.quietCall { rust -> rust.onlineAlbum(provider, item).toApp() }

    /**
     * Страница радио от [seed] провайдера [provider] (id трека или его источника); [page] — ключ прошлой страницы,
     * `null` — первая. Беда провайдера — [OnlineRadioReply.problem]; выключено или негодный аргумент — [CoreFailure].
     */
    fun radio(
        provider: String,
        seed: String,
        page: String? = null,
    ): OnlineRadioReply = core.quietCall { rust -> rust.onlineRadio(provider, seed, page).toApp() }

    /** Где трек каталога играется; файл с диска и неизвестный трек — пусто. */
    fun origins(track: TrackId): List<OnlineOrigin> =
        core.call { rust -> rust.onlineOrigins(track.value).map { it.toApp() } }

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
     * сеть (MP3), иначе лучший вариант; форматы из [undecodable] устройство
     * не декодирует — они пробуются последними. Выключено или вариантов нет —
     * [CoreFailure] вида `Unavailable`.
     */
    fun stream(
        track: TrackId,
        metered: Boolean,
        undecodable: Set<AudioFormat> = emptySet(),
    ): StreamAddress =
        core.quietCall { it.onlineStream(track.value, metered, undecodable.map(AudioFormat::toRust)).toApp() }
}

/**
 * Отмена одного вызова [CoreOnline.search]: зови [cancel], когда ответ уже не
 * нужен. Объект не закрывается вручную: [cancel] может прийти после конца
 * вызова, а закрытый объект UniFFI бросает `IllegalStateException` — хватит
 * очистки по сборке мусора.
 */
class OnlineCancel {
    internal val rust = RustOnlineCancel()

    fun cancel() = rust.cancel()
}

/**
 * Сеть платформы для ядра: один GET без повторов, ответ с любым статусом — [NetAnswer.Response]
 * (вместе с заголовками: из `Retry-After` ядро узнаёт, сколько просят подождать).
 */
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
    /** Ответ с любым статусом; [headers] — пары как пришли, повторное имя — по паре на значение. */
    class Response(
        val status: Int,
        val body: ByteArray,
        val headers: List<Pair<String, String>> = emptyList(),
    ) : NetAnswer

    /** Соединения не вышло: нет сети, DNS, оборвалось. [reason] — без адреса: в нём бывает запрос. */
    data class NoAnswer(
        val reason: String,
    ) : NetAnswer

    /** Не дождались ответа: срок вышел. Это не «нет сети» — сеть могла быть, но сервер молчал. */
    data object TimedOut : NetAnswer

    data object TooLarge : NetAnswer
}

enum class OnlineKind {
    TRACK,

    /** Собрание: альбом, концерт, элемент Internet Archive. */
    ALBUM,
}

/**
 * Почему секции (или альбому) нечего показать. Три разные беды: соединения не вышло —
 * [NO_NETWORK], и только тогда говорят «нет сети»; сервер отказал, не дождались ответа или
 * провайдер не отвечал несколько раз подряд и отдыхает — [PROVIDER_DOWN]; сервер просит
 * подождать — [RATE_LIMITED]; ответ не понять — [BROKEN].
 */
enum class OnlineProblem {
    NO_NETWORK,
    PROVIDER_DOWN,
    RATE_LIMITED,
    BROKEN,
}

/** Ответ на просьбу открыть альбом: треки — или беда провайдера (тогда [tracks] пуст). */
data class OnlineAlbumReply(
    val tracks: List<OnlineTrack>,
    val problem: OnlineProblem?,
)

/** Страница радио: треки подборки по порядку и ключ следующей страницы; `null` — подборка кончилась. */
data class OnlineRadioPage(
    val tracks: List<OnlineTrack>,
    val next: String? = null,
)

/** Ответ на просьбу о радио: страница — или беда провайдера (тогда [page] пуста). */
data class OnlineRadioReply(
    val page: OnlineRadioPage,
    val problem: OnlineProblem?,
)

/** Где трек каталога играется: провайдер и его источник (вариант). */
data class OnlineOrigin(
    val provider: String,
    val source: String,
)

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

/**
 * Что отдать плееру: адрес, заголовки и сколько адрес годен с получения ([validFor]; у YouTube — часы, `null` —
 * не стареет, нуль — уже негоден).
 */
data class StreamAddress(
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val validFor: Duration? = null,
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
        is NetAnswer.Response ->
            RustNetAnswer.Response(status.toUShort(), body, headers.map { (name, value) -> RustNetHeader(name, value) })
        is NetAnswer.NoAnswer -> RustNetAnswer.NoAnswer(reason)
        NetAnswer.TimedOut -> RustNetAnswer.TimedOut
        NetAnswer.TooLarge -> RustNetAnswer.TooLarge
    }

internal fun RustOnlineSearch.toApp() = OnlineSection(provider, results.map { it.toApp() }, problem?.toApp())

internal fun RustOnlineAlbumReply.toApp() = OnlineAlbumReply(tracks.map { it.toApp() }, problem?.toApp())

internal fun RustOnlineRadioReply.toApp() =
    OnlineRadioReply(OnlineRadioPage(tracks.map { it.toApp() }, next), problem?.toApp())

internal fun RustOnlineOrigin.toApp() = OnlineOrigin(provider, source)

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
        RustOnlineProblem.RATE_LIMITED -> OnlineProblem.RATE_LIMITED
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

internal fun RustStreamAddress.toApp() =
    StreamAddress(url, headers.map { it.name to it.value }, validForMs?.toLong()?.milliseconds)

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
