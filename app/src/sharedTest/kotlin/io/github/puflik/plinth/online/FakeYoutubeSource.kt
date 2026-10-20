package io.github.puflik.plinth.online

import io.github.puflik.plinth.online.youtube.YoutubeException
import io.github.puflik.plinth.online.youtube.YoutubeFormat
import io.github.puflik.plinth.online.youtube.YoutubeKind
import io.github.puflik.plinth.online.youtube.YoutubeResult
import io.github.puflik.plinth.online.youtube.YoutubeSource
import io.github.puflik.plinth.online.youtube.YoutubeStream
import io.github.puflik.plinth.online.youtube.YoutubeTrack
import io.github.puflik.plinth.online.youtube.YoutubeVariant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * [YoutubeSource] в памяти (Ю3в): каталог, похожий на выдачу YouTube Music, и ручки, которыми тест ломает источник.
 * Не знает ни экстрактора, ни сети — для тестов провайдера и его пути через ядро и плеер.
 *
 * - [calls] — каждый вызов строкой («search:piano:20», «resolve:ID», «stream:ID/251»);
 * - [failure] — всякий вызов бросает это;
 * - [linkBase] и [expireAtSeconds] — начало ссылки потока и её срок (`expire=`, секунды Unix; `null` — без срока);
 *   каждая выданная ссылка несёт свой номер (`n=`): две ссылки одного варианта различимы.
 *
 * Видео [SONG_NO_OPUS] и [QUIET_TRACK] — только AAC: их `stream` для 251 — `Unavailable("variant")`, хотя `resolve`
 * альбома называет оба формата, как настоящий источник.
 */
class FakeYoutubeSource : YoutubeSource {
    val calls = CopyOnWriteArrayList<String>()

    @Volatile var failure: Throwable? = null

    @Volatile var linkBase = "https://rr1---sn-test.googlevideo.com"

    @Volatile var expireAtSeconds: Long? = System.currentTimeMillis() / MILLIS + SIX_HOURS_SECONDS

    private val links = AtomicInteger()

    override fun search(
        query: String,
        limit: Int,
    ): List<YoutubeResult> {
        calls += "search:$query:$limit"
        failure?.let { throw it }
        val needle = query.trim().lowercase()
        return RESULTS.filter { needle.isNotEmpty() && it.mentions(needle) }.take(limit)
    }

    override fun resolve(id: String): List<YoutubeTrack> {
        calls += "resolve:$id"
        failure?.let { throw it }
        return TRACKS[id] ?: throw YoutubeException.Unavailable("id")
    }

    override fun stream(variant: String): YoutubeStream {
        calls += "stream:$variant"
        failure?.let { throw it }
        val video = variant.substringBefore('/')
        val itag = variant.substringAfter('/', "")
        val playable = (itag == "251" && video != SONG_NO_OPUS && video != QUIET_TRACK) || itag == "140"
        if (!playable) throw YoutubeException.Unavailable("variant")
        val expire = expireAtSeconds?.let { "expire=$it&" }.orEmpty()
        val link = "$linkBase/videoplayback?${expire}n=${links.incrementAndGet()}&source=$variant"
        val (format, kbps) = if (itag == "251") YoutubeFormat.OPUS to OPUS_KBPS else YoutubeFormat.AAC to AAC_KBPS
        return YoutubeStream(link, listOf("User-Agent" to UA, "Range" to "bytes=0-"), format, kbps)
    }

    companion object {
        const val SONG = "dQw4w9WgXcQ"
        const val SONG_NO_OPUS = "-a_B9xYz01q"
        const val ALBUM = "OLAK5uy_piano_Album-1"
        const val ALBUM_TRACK_2 = "trkCCCCCCC3"
        const val ALBUM_TRACK_3 = "trkDDDDDDD4"
        const val QUIET = "OLAK5uy_quiet_Album-2"
        const val QUIET_TRACK = "trkBBBBBBB2"

        /** `User-Agent` клиента, выдавшего ссылку, как отдаёт его экстрактор. */
        const val UA = "com.google.visionos.youtube/1.02"

        const val OPUS_KBPS = 160
        const val AAC_KBPS = 128
        private const val MILLIS = 1000L
        private const val SIX_HOURS_SECONDS = 21_540L
        private const val OPUS_ITAG = 251
        private val BOTH = listOf(OPUS_ITAG, 140)

        private val RESULTS =
            listOf(
                YoutubeResult(SONG, YoutubeKind.TRACK, "Piano Man", "Billy Joel", 338.seconds),
                YoutubeResult(SONG_NO_OPUS, YoutubeKind.TRACK, "Фортепианный концерт", "Группа КИНО", 240.seconds),
                YoutubeResult(ALBUM, YoutubeKind.ALBUM, "Piano Album", "Various"),
                YoutubeResult(QUIET, YoutubeKind.ALBUM, "Quiet Album", "Various"),
            )

        private fun YoutubeResult.mentions(needle: String) =
            needle in title.lowercase() || artist.orEmpty().lowercase().contains(needle)

        private fun track(
            video: String,
            title: String,
            artist: String,
            seconds: Long,
            album: String? = null,
            number: Int? = null,
            itags: List<Int> = BOTH,
        ) = YoutubeTrack(
            id = video,
            title = title,
            artist = artist,
            album = album,
            number = number,
            duration = seconds.seconds,
            variants =
                itags.map { itag ->
                    if (itag == OPUS_ITAG) {
                        YoutubeVariant("$video/$itag", YoutubeFormat.OPUS, OPUS_KBPS)
                    } else {
                        YoutubeVariant("$video/$itag", YoutubeFormat.AAC, AAC_KBPS)
                    }
                },
        )

        private val TRACKS: Map<String, List<YoutubeTrack>> =
            mapOf(
                SONG to listOf(track(SONG, "Piano Man", "Billy Joel", 338)),
                SONG_NO_OPUS to
                    listOf(track(SONG_NO_OPUS, "Фортепианный концерт", "Группа КИНО", 240, itags = listOf(140))),
                ALBUM to
                    listOf(
                        track(SONG, "Piano Man", "Billy Joel", 338, "Piano Album", 1),
                        track(ALBUM_TRACK_2, "Second Movement", "Various", 200, "Piano Album", 2),
                        track(ALBUM_TRACK_3, "Third Movement", "Various", 210, "Piano Album", 3),
                    ),
                QUIET to
                    listOf(
                        track(QUIET_TRACK, "Hush", "Various", 180, "Quiet Album", 1),
                        track(SONG_NO_OPUS, "Фортепианный концерт", "Группа КИНО", 240, "Quiet Album", 2),
                    ),
                QUIET_TRACK to listOf(track(QUIET_TRACK, "Hush", "Various", 180)),
                ALBUM_TRACK_2 to listOf(track(ALBUM_TRACK_2, "Second Movement", "Various", 200)),
                ALBUM_TRACK_3 to listOf(track(ALBUM_TRACK_3, "Third Movement", "Various", 210)),
            )
    }
}
