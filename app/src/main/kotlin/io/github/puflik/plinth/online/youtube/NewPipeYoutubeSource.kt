package io.github.puflik.plinth.online.youtube

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [YoutubeSource] на NewPipeExtractor (Ю3б; версия — в `libs.versions.toml`, больше нигде): единственное место
 * приложения, которое знает экстрактор.
 *
 * Состояние экстрактора глобально (`NewPipe.init`), поэтому загрузчик ставится в начале каждого вызова, а не в
 * конструкторе: источники, созданные подряд, работают каждый со своим. Два источника одновременно не рассчитаны.
 * Каждый вызов идёт со сроком ([YoutubeBudgets]): ядро прервать его не может, а экстрактор спрашивает сеть не раз.
 * Что бы ни бросил экстрактор, наружу выходит одна из трёх бед [YoutubeException] — правила в [failureOf].
 *
 * Извлечение одного видео — 7–8 запросов, 2–3 с. Чтобы игра по нажатию стоила одного извлечения, а не двух,
 * `resolve` видео откладывает его потоки в заготовку ([StreamStash]); первый же `stream` этого видео забирает её.
 * Любой другой `stream` — новое извлечение и новая ссылка: ссылку, на которую плеер получил отказ, второй раз
 * не отдаём.
 *
 * Радио (Ю4б) — страницы Mix затравки, по одному запросу на страницу, без извлечения видео. Страница экстрактора
 * (`Page`) в ключ не сериализуется: она лежит в памяти ([RadioPages]), а наружу уходит короткий ключ; после запуска
 * приложения ключей нет, и приложение открывает радио заново.
 *
 * Только первая страница альбома (до 100 треков); прямой эфир и премьера (нет аудиопотоков) — `Parse`, а не
 * `Unavailable`.
 */
class NewPipeYoutubeSource internal constructor(
    downloader: Downloader,
    private val statusTable: HttpStatusTable,
    private val budgets: YoutubeBudgets = YoutubeBudgets(),
    nanoTime: () -> Long = System::nanoTime,
) : YoutubeSource {
    /** Так источник собирается в приложении: свой загрузчик над той же таблицей статусов. */
    constructor(statusTable: HttpStatusTable) : this(YoutubeDownloader(statusTable), statusTable)

    private val network: Downloader = Budgeted(downloader)
    private val stash = StreamStash(nanoTime)
    private val pages = RadioPages(nanoTime)

    override fun search(
        query: String,
        limit: Int,
    ): List<YoutubeResult> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        // Любая беда любого из двух поисков — вся поиск-ошибка: тихая потеря альбомов скрыла бы сломанный формат.
        return call(budgets.search) {
            val songs = found(text, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS).mapNotNull(::songResult)
            val albums = found(text, YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS).mapNotNull(::albumResult)
            withAlbumQuarter(songs, albums, limit.coerceIn(MIN_LIMIT, MAX_LIMIT))
        }
    }

    override fun resolve(id: String): List<YoutubeTrack> =
        when {
            VIDEO_ID.matches(id) -> call(budgets.extract) { listOf(resolveVideo(id)) }
            ALBUM_ID.matches(id) -> call(budgets.extract) { resolveAlbum(id) }
            // Идентификатор уходит в адрес запроса: подставить в него нельзя ничего.
            else -> throw YoutubeException.Unavailable("id")
        }

    override fun stream(variant: String): YoutubeStream {
        val match = VARIANT.matchEntire(variant) ?: throw YoutubeException.Unavailable("variant")
        val (video, itag) = match.destructured
        return call(budgets.extract) { streamOf(video, itag.toInt()) }
    }

    override fun radio(
        seed: String,
        page: String?,
    ): YoutubeRadioPage {
        val video = seedVideo(seed)
        // Ключ проверяется до сети: чужой, неизвестный и просроченный — одно и то же «открой радио заново».
        val kept = page?.let { pages.find(video, it) ?: throw YoutubeException.Unavailable("page") }
        return call(budgets.radio) { radioPage(video, kept) }
    }

    /** Вызов экстрактора: загрузчик, срок и перевод бед. Бросает только [YoutubeException]. */
    @Suppress("TooGenericExceptionCaught") // Экстрактор бросает что угодно: любая беда — одна из трёх.
    private fun <T> call(
        budget: Duration,
        block: () -> T,
    ): T =
        try {
            CallBudget.within(budget) {
                NewPipe.init(network, Localization.DEFAULT, ContentCountry.DEFAULT)
                block()
            }
        } catch (e: Exception) {
            throw failureOf(e, statusTable)
        }

    private fun found(
        text: String,
        filter: String,
    ): List<InfoItem> {
        val extractor = ServiceList.YouTube.getSearchExtractor(text, listOf(filter), "")
        extractor.fetchPage()
        return extractor.initialPage.items
    }

    /** Одно извлечение видео: метаданные для трека и потоки, которые у видео есть, — в заготовку. */
    private fun resolveVideo(id: String): YoutubeTrack {
        val extractor = fetchedVideo(id)
        val offered = extractor.audioStreams
        val streams = playable(offered)
        // Сломанный формат, а не «нет видео»: нет видео — исключение экстрактора.
        requireFormat(offered.isNotEmpty(), "no audio streams")
        requireFormat(streams.isNotEmpty(), "no supported audio stream")
        val title = extractor.name
        requireFormat(!title.isNullOrBlank(), "no title")
        stash.put(id, streams)
        return YoutubeTrack(
            id = id,
            title = title,
            artist = artistOf(extractor.uploaderName),
            duration = secondsOf(extractor.length),
            variants = variantsOf(id, streams.keys),
        )
    }

    private fun resolveAlbum(id: String): List<YoutubeTrack> {
        val extractor = ServiceList.YouTube.getPlaylistExtractor("https://www.youtube.com/playlist?list=$id")
        extractor.fetchPage()
        val album =
            extractor.name
                .removePrefix(ALBUM_PREFIX)
                .trim()
                .takeIf(String::isNotEmpty)
        // Номер — положение в плейлисте по исходному порядку, пропущенные элементы его не сдвигают.
        return extractor.initialPage.items
            .mapIndexedNotNull { index, item -> songOf(item)?.toTrack(album, index + 1) }
            .ifEmpty { throw YoutubeException.Unavailable("empty album") }
    }

    /**
     * Страница Mix затравки [video]: первая — `fetchPage` и `initialPage`, следующая — `getPage` по сохранённой
     * [kept]. Нового экстрактора для продолжения хватает: `getPage` не читает то, что заполняет `fetchPage`.
     */
    private fun radioPage(
        video: String,
        kept: Page?,
    ): YoutubeRadioPage {
        val extractor = ServiceList.YouTube.getPlaylistExtractor("https://www.youtube.com/watch?v=$video&list=RD$video")
        val found =
            if (kept == null) {
                extractor.fetchPage()
                extractor.initialPage
            } else {
                extractor.getPage(kept)
            }
        val tracks = found.items.mapNotNull { radioTrack(it, video) }
        val next = found.nextPage?.takeIf { found.hasNextPage() }?.let { pages.put(video, it) }
        return YoutubeRadioPage(tracks, next)
    }

    /** Поток варианта: из заготовки (один раз) или из нового извлечения. */
    private fun streamOf(
        video: String,
        itag: Int,
    ): YoutubeStream {
        val claim = stash.claim(video, itag)
        val audio =
            if (claim != null) {
                claim.audio
            } else {
                val streams = playable(fetchedVideo(video).audioStreams)
                // Нужного itag нет: потоки остаются для другого варианта, ядро придёт за ним сразу.
                streams[itag].also { if (it == null) stash.put(video, streams) }
            }
        return audio?.let(::streamFrom) ?: throw YoutubeException.Unavailable("variant")
    }

    private fun fetchedVideo(id: String): StreamExtractor {
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$id")
        extractor.fetchPage()
        return extractor
    }
}

/**
 * Загрузчик, который перед каждым запросом экстрактора проверяет срок вызова: экстрактор спрашивает сеть много раз,
 * и вышедший срок обрывает его на ближайшем запросе, а не после всех. Вышел — [SocketTimeoutException].
 */
private class Budgeted(
    private val inner: Downloader,
) : Downloader() {
    override fun execute(request: Request): Response {
        CallBudget.failIfSpent()
        return inner.execute(request)
    }
}

/**
 * Заготовка потоков от `resolve` (Е): одноразовая и короткая. Удачный `stream` её забирает; живёт не дольше
 * [LIFETIME_NANOS]; хранит не больше [CAPACITY] видео; по видео, а не по варианту. Если нужного варианта в заготовке
 * нет, она остаётся — ядро сразу придёт за вторым вариантом. Время — [nanoTime], чтобы тесты двигали его сами.
 */
private class StreamStash(
    private val nanoTime: () -> Long,
) {
    private class Kept(
        val streams: Map<Int, AudioStream>,
        val bornNanos: Long,
    )

    /** Ответ на вопрос к заготовке: [audio] — поток варианта или `null`, если заготовка есть, а варианта в ней нет. */
    class Claim(
        val audio: AudioStream?,
    )

    private val kept = LinkedHashMap<String, Kept>()

    /** Заготовка видео, если она жива: есть вариант [itag] — поток отдан, заготовка снята; нет — осталась. */
    @Synchronized
    fun claim(
        video: String,
        itag: Int,
    ): Claim? {
        dropSpent(nanoTime())
        val entry = kept[video] ?: return null
        val audio = entry.streams[itag]
        if (audio != null) kept.remove(video)
        return Claim(audio)
    }

    @Synchronized
    fun put(
        video: String,
        streams: Map<Int, AudioStream>,
    ) {
        val now = nanoTime()
        dropSpent(now)
        kept.remove(video)
        kept[video] = Kept(streams, now)
        while (kept.size > CAPACITY) kept.remove(kept.keys.first())
    }

    private fun dropSpent(now: Long) {
        kept.values.removeAll { now - it.bornNanos >= LIFETIME_NANOS }
    }
}

/**
 * Ключи страниц радио (Ю4б): страница экстрактора (`Page` — адрес, тело, кукис) в ключ не сериализуется, поэтому лежит
 * здесь, а наружу уходит короткая случайная строка. Живёт [RADIO_LIFETIME_NANOS] от выдачи и не продлевается
 * использованием; хранит не больше [RADIO_CAPACITY] ключей, старейший уходит первым. Ключ привязан к видео затравки и
 * переживает использование: повтор после сбоя получает ту же страницу. Время — [nanoTime], чтобы тесты двигали
 * его сами.
 */
private class RadioPages(
    private val nanoTime: () -> Long,
) {
    private class Kept(
        val video: String,
        val page: Page,
        val bornNanos: Long,
    )

    private val kept = LinkedHashMap<String, Kept>()
    private val random = SecureRandom()

    /** Страница по ключу, если ключ жив и выдан для этой затравки. */
    @Synchronized
    fun find(
        video: String,
        key: String,
    ): Page? {
        dropSpent(nanoTime())
        return kept[key]?.takeIf { it.video == video }?.page
    }

    @Synchronized
    fun put(
        video: String,
        page: Page,
    ): String {
        val now = nanoTime()
        dropSpent(now)
        var key = newKey()
        while (key in kept) key = newKey()
        kept[key] = Kept(video, page, now)
        while (kept.size > RADIO_CAPACITY) kept.remove(kept.keys.first())
        return key
    }

    private fun newKey(): String {
        val letters = CharArray(RADIO_KEY_LENGTH) { RADIO_KEY_ALPHABET[random.nextInt(RADIO_KEY_ALPHABET.length)] }
        return String(letters)
    }

    private fun dropSpent(now: Long) {
        kept.values.removeAll { now - it.bornNanos >= RADIO_LIFETIME_NANOS }
    }
}

/** Поток YouTube, который источник умеет отдавать: номер формата, как его называет источник, и его битрейт. */
private class Itag(
    val number: Int,
    val format: YoutubeFormat,
    val bitrateKbps: Int,
)

/** Песня из поиска или из альбома: то, что обе выдачи знают о видео. */
private class Song(
    val id: String,
    val title: String,
    val artist: String?,
    val duration: Duration?,
)

private fun Song.toTrack(
    album: String?,
    number: Int?,
): YoutubeTrack = YoutubeTrack(id, title, artist, album, number, duration, variantsOf(id, ITAG_NUMBERS))

private fun songResult(item: InfoItem): YoutubeResult? =
    songOf(item)?.let { YoutubeResult(it.id, YoutubeKind.TRACK, it.title, it.artist, it.duration) }

/** Альбом в поиске: длительности и числа треков у него нет. */
private fun albumResult(item: InfoItem): YoutubeResult? =
    (item as? PlaylistInfoItem)?.let { album ->
        val id = queryValue(album.url, "list")?.takeIf(ALBUM_ID::matches)
        val title = album.name
        if (id == null || title.isNullOrBlank()) {
            null
        } else {
            YoutubeResult(id, YoutubeKind.ALBUM, title, artistOf(album.uploaderName))
        }
    }

/**
 * Песня Mix для радио: прямой эфир, премьера и всё без положительной длительности играть нечем (аудиопотоков нет) и
 * пропускаются, как безымянное, чужого вида и сама затравка [seed]. Закрытые и удалённые видео выбрасывает ещё
 * экстрактор.
 */
private fun radioTrack(
    item: InfoItem,
    seed: String,
): YoutubeTrack? {
    val playable = (item as? StreamInfoItem)?.takeIf { it.streamType == StreamType.VIDEO_STREAM && it.duration > 0 }
    return playable
        ?.let(::songOf)
        ?.takeIf { it.id != seed }
        ?.toTrack(album = null, number = null)
}

/**
 * Видео затравки радио: само видео или вариант `<видео>/<itag>`; прочее — [YoutubeException.Unavailable] (`id`)
 * без сети.
 */
private fun seedVideo(seed: String): String =
    if (VIDEO_ID.matches(seed)) {
        seed
    } else {
        VARIANT.matchEntire(seed)?.groupValues?.get(1) ?: throw YoutubeException.Unavailable("id")
    }

/** Элемент без названия или без идентификатора и элемент другого вида пропускаются. */
private fun songOf(item: InfoItem): Song? =
    (item as? StreamInfoItem)?.let { stream ->
        val id = queryValue(stream.url, "v")?.takeIf(VIDEO_ID::matches)
        val title = stream.name
        if (id == null || title.isNullOrBlank()) {
            null
        } else {
            Song(id, title, artistOf(stream.uploaderName), secondsOf(stream.duration))
        }
    }

/**
 * Песни, затем альбомы, всего не больше [limit]. Альбомам — четверть мест: песен не больше `limit - limit / 4`, если
 * альбомы нашлись (иначе [limit]); нехватка песен отдаёт места альбомам.
 */
private fun withAlbumQuarter(
    songs: List<YoutubeResult>,
    albums: List<YoutubeResult>,
    limit: Int,
): List<YoutubeResult> {
    val songPlaces = if (albums.isEmpty()) limit else limit - limit / ALBUM_SHARE
    val chosen = songs.take(songPlaces)
    return chosen + albums.take(limit - chosen.size)
}

/** Автор без суффикса « - Topic», который YouTube приписывает авторским каналам; пустой — `null`. */
private fun artistOf(name: String?): String? =
    name
        ?.trimEnd()
        ?.removeSuffix(TOPIC_SUFFIX)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

/** Длительность в секундах; нуль и «неизвестно» (отрицательное) — `null`. */
private fun secondsOf(seconds: Long): Duration? = if (seconds > 0) seconds.seconds else null

/** Значение параметра `name` из строки запроса адреса; адрес разбирается без исключений. */
private fun queryValue(
    url: String?,
    name: String,
): String? =
    url
        ?.substringBefore('#')
        ?.substringAfter('?', "")
        ?.split('&')
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')

/** Поддерживаемые потоки видео по itag: прямая ссылка и обычная выдача; при повторе itag берётся первый. */
private fun playable(offered: List<AudioStream>): Map<Int, AudioStream> =
    offered
        .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.itag in ITAG_NUMBERS }
        .distinctBy { it.itag }
        .associateBy { it.itag }

/** Варианты видео по тем itag, что есть: Opus раньше AAC. */
private fun variantsOf(
    video: String,
    itags: Collection<Int>,
): List<YoutubeVariant> =
    ITAGS.filter { it.number in itags }.map { YoutubeVariant("$video/${it.number}", it.format, it.bitrateKbps) }

/**
 * Адрес и заголовки плеера. `User-Agent` — у клиента, выдавшего ссылку (VisionOS); ссылке другого клиента чужого не
 * придумываем, плеер оставит свой. `Range: bytes=0-` — без него первый запрос идёт со скоростью 10 КиБ/с.
 */
private fun streamFrom(audio: AudioStream): YoutubeStream {
    val itag = ITAGS.first { it.number == audio.itag }
    val link = audio.content
    val agent =
        if (YoutubeParsingHelper.isVisionOsStreamingUrl(link)) {
            listOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(Localization.DEFAULT))
        } else {
            emptyList()
        }
    return YoutubeStream(link, agent + ("Range" to "bytes=0-"), itag.format, itag.bitrateKbps)
}

/** Как `require`, но беда — [YoutubeException.Parse]: экстрактор вернул не то, что обещает формат. */
private fun requireFormat(
    condition: Boolean,
    what: String,
) {
    if (!condition) throw YoutubeException.Parse(what)
}

/**
 * Исключение экстрактора или загрузчика → беда источника. Цепочка `cause` (до [CAUSE_DEPTH] звеньев) идёт от внешнего
 * звена; на каждом звене первое сработавшее правило решает ([classify]); не сработало ни одно —
 * [YoutubeException.Parse] с именем внешнего исключения. В текст никогда не попадает сообщение исключения: в нём
 * бывают адрес и запрос.
 */
private fun failureOf(
    error: Exception,
    statusTable: HttpStatusTable,
): YoutubeException =
    generateSequence<Throwable>(error) { it.cause }
        .take(CAUSE_DEPTH)
        .firstNotNullOfOrNull { classify(it, statusTable) }
        ?: YoutubeException.Parse(error.kind(), error)

private fun classify(
    error: Throwable,
    statusTable: HttpStatusTable,
): YoutubeException? =
    when (error) {
        is YoutubeException -> error
        // Загрузчик получил отказ по таблице: текст решения — как есть.
        is HttpRefused -> error.verdict.toFailure(error)
        is SocketTimeoutException -> YoutubeException.Network(TIMED_OUT, error)
        // Капча и «подтвердите, что вы не бот» — YouTube просит подождать, а не говорит «видео нет»: как 429 без срока.
        is ReCaptchaException, is SignInConfirmNotBotException ->
            statusTable.verdict(TOO_MANY_REQUESTS, null).toFailure(error)
        is IOException -> YoutubeException.Network(error.kind(), error)
        is ContentNotAvailableException, is ContentNotSupportedException ->
            YoutubeException.Unavailable(error.kind(), error)
        is InterruptedException -> {
            Thread.currentThread().interrupt()
            YoutubeException.Network(INTERRUPTED, error)
        }
        else -> null
    }

private fun HttpVerdict.toFailure(cause: Throwable): YoutubeException =
    when (this) {
        is HttpVerdict.Unavailable -> YoutubeException.Unavailable(text, cause)
        is HttpVerdict.Network -> YoutubeException.Network(text, cause)
        // Таблица не отказала на то, что отказ по определению (429, отказ загрузчика): её сбой, а не ответ сервера.
        HttpVerdict.Fine -> YoutubeException.Parse("status table", cause)
    }

/** Имя класса исключения: безопасная часть сбоя (у анонимного класса — его двоичное имя без пакета). */
private fun Throwable.kind(): String = javaClass.simpleName.ifEmpty { javaClass.name.substringAfterLast('.') }

private const val ITAG_OPUS = 251
private const val ITAG_AAC = 140
private const val OPUS_KBPS = 160
private const val AAC_KBPS = 128

/** Opus раньше AAC: так им и идти в вариантах. */
private val ITAGS =
    listOf(
        Itag(ITAG_OPUS, YoutubeFormat.OPUS, OPUS_KBPS),
        Itag(ITAG_AAC, YoutubeFormat.AAC, AAC_KBPS),
    )
private val ITAG_NUMBERS = ITAGS.map(Itag::number).toSet()

private const val VIDEO_ID_PATTERN = "[A-Za-z0-9_-]{11}"
private val VIDEO_ID = Regex(VIDEO_ID_PATTERN)
private val ALBUM_ID = Regex("OLAK5uy_[A-Za-z0-9_-]{8,64}")
private val VARIANT = Regex("($VIDEO_ID_PATTERN)/($ITAG_OPUS|$ITAG_AAC)")

private const val MIN_LIMIT = 1
private const val MAX_LIMIT = 100
private const val ALBUM_SHARE = 4
private const val TOPIC_SUFFIX = " - Topic"

// «Album – Название»: у плейлиста-альбома YouTube Music такое начало (– — длинное тире).
private const val ALBUM_PREFIX = "Album – "

private const val TIMED_OUT = "timed out"
private const val INTERRUPTED = "interrupted"
private const val TOO_MANY_REQUESTS = 429
private const val CAUSE_DEPTH = 8

private const val CAPACITY = 8
private val LIFETIME_NANOS = 2.minutes.inWholeNanoseconds

private const val RADIO_CAPACITY = 8
private val RADIO_LIFETIME_NANOS = 3.hours.inWholeNanoseconds
private const val RADIO_KEY_LENGTH = 12
private const val RADIO_KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
