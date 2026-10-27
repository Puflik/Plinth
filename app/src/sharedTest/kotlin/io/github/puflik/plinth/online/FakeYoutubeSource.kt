package io.github.puflik.plinth.online

import io.github.puflik.plinth.online.youtube.YoutubeException
import io.github.puflik.plinth.online.youtube.YoutubeFormat
import io.github.puflik.plinth.online.youtube.YoutubeKind
import io.github.puflik.plinth.online.youtube.YoutubeRadioPage
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
 * - [calls] — каждый вызов строкой («search:piano:20», «resolve:ID», «stream:ID/251», «radio:ID:КЛЮЧ»; у первой
 *   страницы радио ключ — «null»);
 * - [failure] — всякий вызов бросает это;
 * - [linkBase] и [expireAtSeconds] — начало ссылки потока и её срок (`expire=`, секунды Unix; `null` — без срока);
 *   каждая выданная ссылка несёт свой номер (`n=`): две ссылки одного варианта различимы;
 * - [radioPages] — Mix для `radio` (Ю4б): страницы по ключу, `null` — первая; по умолчанию три страницы и конец.
 *
 * Видео [SONG_NO_OPUS] и [QUIET_TRACK] — только AAC: их `stream` для 251 — `Unavailable("variant")`, хотя `resolve`
 * альбома называет оба формата, как настоящий источник.
 *
 * Радио, как у настоящего источника: затравка — любое видео каталога или радио (само или его вариант 251/140), иначе
 * `Unavailable("id")`; ключ, которого нет в [radioPages], — `Unavailable("page")`; затравка в страницу не идёт.
 */
class FakeYoutubeSource : YoutubeSource {
    val calls = CopyOnWriteArrayList<String>()

    @Volatile var failure: Throwable? = null

    @Volatile var linkBase = "https://rr1---sn-test.googlevideo.com"

    @Volatile var expireAtSeconds: Long? = System.currentTimeMillis() / MILLIS + SIX_HOURS_SECONDS

    @Volatile var radioPages: Map<String?, YoutubeRadioPage> = RADIO_PAGES

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

    override fun radio(
        seed: String,
        page: String?,
    ): YoutubeRadioPage {
        calls += "radio:$seed:$page"
        failure?.let { throw it }
        val video = SEED.matchEntire(seed)?.groupValues?.get(1)?.takeIf { it in SEED_VIDEOS }
        if (video == null) throw YoutubeException.Unavailable("id")
        val found = radioPages[page] ?: throw YoutubeException.Unavailable("page")
        return found.copy(tracks = found.tracks.filter { it.id != video })
    }

    companion object {
        const val SONG = "dQw4w9WgXcQ"
        const val SONG_NO_OPUS = "-a_B9xYz01q"
        const val ALBUM = "OLAK5uy_piano_Album-1"
        const val ALBUM_TRACK_2 = "trkCCCCCCC3"
        const val ALBUM_TRACK_3 = "trkDDDDDDD4"
        const val QUIET = "OLAK5uy_quiet_Album-2"
        const val QUIET_TRACK = "trkBBBBBBB2"

        /** Радио: семь треков (из них 1–3 на первой странице, 4–6 на второй, 7 на последней), 200, 210, … 260 секунд. */
        const val RADIO_1 = "radioAAAAA1"
        const val RADIO_2 = "radioAAAAA2"
        const val RADIO_3 = "radioAAAAA3"
        const val RADIO_4 = "radioAAAAA4"
        const val RADIO_5 = "radioAAAAA5"
        const val RADIO_6 = "radioAAAAA6"
        const val RADIO_7 = "radioAAAAA7"

        /** Ключи второй и третьей страниц радио; у последней страницы ключа нет. */
        const val RADIO_KEY_2 = "radio-2"
        const val RADIO_KEY_3 = "radio-3"

        /** `User-Agent` клиента, выдавшего ссылку, как отдаёт его экстрактор. */
        const val UA = "com.google.visionos.youtube/1.02"

        const val OPUS_KBPS = 160
        const val AAC_KBPS = 128
        private const val MILLIS = 1000L
        private const val SIX_HOURS_SECONDS = 21_540L
        private const val OPUS_ITAG = 251
        private val BOTH = listOf(OPUS_ITAG, 140)

        private val SEED = Regex("([A-Za-z0-9_-]{11})(?:/(?:251|140))?")

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

        private val RADIO_IDS = listOf(RADIO_1, RADIO_2, RADIO_3, RADIO_4, RADIO_5, RADIO_6, RADIO_7)

        /** Видео, от которых радио открывается: одиночные видео каталога и сами треки радио. */
        private val SEED_VIDEOS = setOf(SONG, SONG_NO_OPUS, ALBUM_TRACK_2, ALBUM_TRACK_3, QUIET_TRACK) + RADIO_IDS

        private fun radioTrack(number: Int) =
            track(RADIO_IDS[number - 1], "Radio $number", "Radio Artist", 190L + 10L * number)

        private val RADIO_PAGES: Map<String?, YoutubeRadioPage> =
            mapOf(
                null to YoutubeRadioPage((1..3).map(::radioTrack), RADIO_KEY_2),
                RADIO_KEY_2 to YoutubeRadioPage((4..6).map(::radioTrack), RADIO_KEY_3),
                RADIO_KEY_3 to YoutubeRadioPage(listOf(radioTrack(7)), null),
            )
    }
}
