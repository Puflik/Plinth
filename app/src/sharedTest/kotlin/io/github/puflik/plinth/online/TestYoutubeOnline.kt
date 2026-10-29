package io.github.puflik.plinth.online

import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.OnlineVariant
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Песня и альбом YouTube, как их отдаёт поиск (Ю5а): песня — видео (`TRACK`, длительность есть), альбом — плейлист
 * `OLAK5uy_…` (`ALBUM`, длительности нет). Идентификаторы — как у провайдера: трек `<видео>`, вариант
 * `<видео>/<itag>` (251 — Opus, 140 — AAC).
 */
object TestSong {
    const val PROVIDER = "youtube"
    const val VIDEO = "luckyvideo1"
    const val TITLE = "Lucky"
    const val ARTIST = "Daft Punk"
    val DURATION = 3.minutes + 29.seconds

    const val ALBUM_ID = "OLAK5uy_lucky"
    const val ALBUM_TITLE = "Random Access Memories"

    val result = OnlineResult(VIDEO, OnlineKind.TRACK, TITLE, ARTIST, duration = DURATION)
    val albumResult = OnlineResult(ALBUM_ID, OnlineKind.ALBUM, ALBUM_TITLE, ARTIST)

    /** Что даёт `resolve` видео: один трек с обоими номинальными вариантами. */
    val track =
        OnlineTrack(
            external = VIDEO,
            title = TITLE,
            artist = ARTIST,
            duration = DURATION,
            variants =
                listOf(
                    OnlineVariant("$VIDEO/251", AudioFormat.OPUS, 160),
                    OnlineVariant("$VIDEO/140", AudioFormat.AAC, 128),
                ),
        )
}

/**
 * [OnlineRepository] с одним провайдером `youtube` (Ю5а): поиск отдаёт [TestSong.result] и [TestSong.albumResult],
 * `album` на видео песни — [TestSong.track]. Каталог, радио и потоки — у [delegate]. Сеть можно выключить
 * ([networkUp]), ответ `album` — задержать ([gate]) или опустошить ([emptyAnswer]); вызовы `album` пишутся в
 * [albumCalls].
 */
class TestYoutubeOnline(
    private val delegate: FakeOnlineRepository = FakeOnlineRepository(),
) : OnlineRepository by delegate {
    /** Есть ли сеть; нет — поиск и `album` говорят «нет сети». */
    var networkUp = true

    /** Ответ `album` на видео песни — без треков. */
    var emptyAnswer = false

    /** Есть — `album` ждёт его, как ответа сети. */
    var gate: CompletableDeferred<Unit>? = null

    /** `item` каждого вызова `album` по порядку, в том числе неудачного. */
    val albumCalls = mutableListOf<String>()

    override suspend fun search(query: String): List<OnlineSection> =
        when {
            query.isBlank() -> emptyList()
            networkUp -> listOf(OnlineSection(TestSong.PROVIDER, listOf(TestSong.result, TestSong.albumResult)))
            else -> listOf(OnlineSection(TestSong.PROVIDER, emptyList(), OnlineProblem.NO_NETWORK))
        }

    override suspend fun album(
        provider: String,
        item: String,
    ): OnlineAlbum {
        albumCalls += item
        gate?.await()
        return when {
            !networkUp -> OnlineAlbum.Failed(OnlineProblem.NO_NETWORK)
            provider == TestSong.PROVIDER && item == TestSong.VIDEO ->
                OnlineAlbum.Tracks(if (emptyAnswer) emptyList() else listOf(TestSong.track))
            else -> delegate.album(provider, item)
        }
    }
}
