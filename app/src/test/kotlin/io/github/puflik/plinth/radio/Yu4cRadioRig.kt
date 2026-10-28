package io.github.puflik.plinth.radio

import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.core.RadioProblem
import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.OnlineVariant
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.online.FakeOnlineRepository
import io.github.puflik.plinth.online.FakeOnlineSettings
import io.github.puflik.plinth.online.OnlineProviderIds
import io.github.puflik.plinth.online.OnlineRadio
import io.github.puflik.plinth.online.OnlineRepository
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Стенд радио Ю4в: движок, контроллер, сеть в памяти ([repository] за двойником [online]), переключатели, вести и оба
 * рабочих — [feeder] и [starter]. Часы — виртуальное время теста от нуля эпохи, как в `QueueKeeperTest`.
 *
 * Сеть в памяти отвечает синхронно: пока не поставлен [RadioOnline.radioGate], запрос радио кончается сразу.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RadioRig(
    scope: TestScope,
) {
    val engine = FakeAudioEngine()
    val controller = PlaybackController(engine, scope.backgroundScope)
    val repository = FakeOnlineRepository()
    val online = RadioOnline(repository)
    val settings = FakeOnlineSettings()
    val notices = RadioNotices()

    /** Вести о радио, что дошли до человека, по порядку. */
    val told = mutableListOf<RadioProblem>()

    private val clock =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(scope.testScheduler.currentTime)
        }

    val feeder = RadioFeeder(controller, online, notices, scope.backgroundScope, clock)
    val starter = RadioStarter(controller, online, settings, feeder, notices, scope.backgroundScope)

    init {
        scope.backgroundScope.launch { notices.errors.collect { told += (it as AppError.Radio).problem } }
        feeder.start()
    }

    /** Источники включены, как после первого применения переключателя. */
    suspend fun enable() = repository.setEnabled(true, emptySet())

    /** Трек YouTube, каким его отдаёт страница Mix: два номинальных варианта, альбома и номера нет. */
    fun track(
        video: String,
        title: String = "Song $video",
        artist: String? = "Band",
    ) = OnlineTrack(
        external = video,
        title = title,
        artist = artist,
        album = null,
        number = null,
        year = null,
        duration = 3.minutes,
        mbid = null,
        variants = listOf(OnlineVariant("$video/251", AudioFormat.OPUS), OnlineVariant("$video/140", AudioFormat.AAC)),
    )

    /** [count] разных треков `v<номер>`, названия — «Song v<номер>». */
    fun tracks(
        count: Int,
        prefix: String = "v",
    ) = (0 until count).map { track("$prefix$it") }

    /** Заводит [tracks] в каталог под провайдером YouTube — ID по порядку. */
    suspend fun catalog(tracks: List<OnlineTrack>): List<TrackId> = repository.add(OnlineProviderIds.YOUTUBE, tracks)

    /** Элемент очереди для трека каталога: как у `LibraryTrack.toQueueItem()` сетевого трека без альбома. */
    fun item(
        id: TrackId,
        track: OnlineTrack,
    ) = QueueItem(
        source = AudioSource.Online(id.value),
        title = track.title,
        artist = track.artist,
        album = track.album,
        duration = track.duration,
        albumOwner = track.artist,
    )

    /** Радио на треке [start] из [tracks] (они заводятся в каталог) — играет. Возвращает ID по порядку. */
    suspend fun playRadio(
        tracks: List<OnlineTrack>,
        start: Int = 0,
        context: QueueContext.Radio = RADIO,
    ): List<TrackId> {
        val ids = catalog(tracks)
        controller.play(context, tracks.zip(ids) { track, id -> item(id, track) }, start)
        return ids
    }

    /** Ответ радио «страница» из [tracks] с ключом следующей [next]. */
    fun page(
        tracks: List<OnlineTrack>,
        next: String? = null,
    ) = OnlineRadio.Page(tracks, next)

    /** Сценарий: на `radio("youtube", seed, page)` отвечать [answer]. */
    fun script(
        seed: String,
        page: String?,
        answer: OnlineRadio,
    ) = repository.scriptRadio(OnlineProviderIds.YOUTUBE, seed, page, answer)

    /** Секция поиска YouTube из треков с этими видео. */
    fun youtubeSection(vararg videos: String) =
        OnlineSection(
            provider = OnlineProviderIds.YOUTUBE,
            results = videos.map { OnlineResult(it, OnlineKind.TRACK, "Title of $it", "Artist of $it") },
        )

    /** Вызовы радио, дошедшие до сети в памяти: `(затравка, ключ страницы)`. */
    val calls: List<Pair<String, String?>> get() = repository.radioCalls.map { it.seed to it.page }

    companion object {
        /** Контекст радио в тестах. */
        val RADIO = QueueContext.Radio(OnlineProviderIds.YOUTUBE, "seedvid", "Seed Song")
    }
}

/**
 * Сеть радио для тестов: поверх [FakeOnlineRepository] — поиск по сценарию [sections] (с записью запросов) и
 * ворота [radioGate], которые держат вызов радио, как медленная сеть. [radioStarted] — вызовы, что начались (в том
 * числе ждущие у ворот).
 *
 * Поиск затравки (Ю4г) идёт к одному провайдеру: [searchIn] отдаёт его секцию из [sections] и записывает провайдера в
 * [searchedIn]; поиск по всем ([search]) записывается в [wide] — запуску радио он не нужен. Запрос попадает в [queries]
 * в обоих случаях.
 */
internal class RadioOnline(
    private val inner: FakeOnlineRepository,
) : OnlineRepository by inner {
    val queries = mutableListOf<String>()
    val searchedIn = mutableListOf<String>()
    val wide = mutableListOf<String>()
    var sections: List<OnlineSection> = emptyList()
    var radioGate: CompletableDeferred<Unit>? = null
    val radioStarted = mutableListOf<Pair<String, String?>>()

    override suspend fun search(query: String): List<OnlineSection> {
        queries += query
        wide += query
        return sections
    }

    override suspend fun searchIn(
        provider: String,
        query: String,
    ): OnlineSection? {
        queries += query
        searchedIn += provider
        return sections.firstOrNull { it.provider == provider }
    }

    override suspend fun radio(
        provider: String,
        seed: String,
        page: String?,
    ): OnlineRadio {
        radioStarted += seed to page
        radioGate?.await()
        return inner.radio(provider, seed, page)
    }
}

/** Запуск радио, что только записывает затравки. */
internal class RecordingRadio : RadioLauncher {
    val seeds = mutableListOf<RadioSeed>()

    override fun start(seed: RadioSeed) {
        seeds += seed
    }
}
