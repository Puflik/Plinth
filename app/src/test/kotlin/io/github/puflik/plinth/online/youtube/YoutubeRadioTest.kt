package io.github.puflik.plinth.online.youtube

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.online.youtube.Recorded.KINO_ALBUM
import io.github.puflik.plinth.online.youtube.Recorded.KROVI
import io.github.puflik.plinth.online.youtube.Recorded.LUCKY
import io.github.puflik.plinth.online.youtube.Recorded.RAM_ALBUM
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `radio` на записанных ответах (Ю4б, `docs/work/yu4b.md`): Mix затравки страницами, каждая — настоящая страница YouTube
 * (`resources/youtube/mix/`, как они записаны — `README.md` там же). Что держится: затравка не возвращается ни на одной
 * странице; прямые эфиры, премьеры и безымянное не играются и не идут; повторы между страницами источник не трогает —
 * очередь знает, что в ней уже есть; ключ следующей страницы — короткая строка, а сама страница экстрактора лежит
 * в памяти источника (восемь ключей, три часа от рождения), и ключ можно использовать повторно; чужой, неизвестный и
 * просроченный ключи — `Unavailable("page")` без сети.
 */
class YoutubeRadioTest {
    private val replay = ReplayDownloader()
    private val table = FakeStatusTable()
    private val clock = AtomicLong(0L)
    private val source = NewPipeYoutubeSource(replay, table, nanoTime = clock::get)

    /** Версию клиента экстрактор помнит на всю JVM: сброс, чтобы `sw.js` был первым запросом в каждом тесте. */
    @Before
    fun resetClientVersion() {
        YoutubeParsingHelper.resetClientVersion()
    }

    @Test
    fun `the first page is the Mix of the seed without the seed, each track with both nominal variants`() {
        val page = source.radio(LUCKY, null)

        assertThat(page.ids()).containsExactlyElementsIn(LUCKY_PAGE_1).inOrder()
        assertThat(page.tracks.first())
            .isEqualTo(
                YoutubeTrack(
                    id = "cV9mMLDKIbI",
                    title = "I Need A Dollar",
                    artist = "Aloe Blacc",
                    album = null,
                    number = null,
                    duration = 244.seconds,
                    variants =
                        listOf(
                            YoutubeVariant("cV9mMLDKIbI/251", YoutubeFormat.OPUS, 160),
                            YoutubeVariant("cV9mMLDKIbI/140", YoutubeFormat.AAC, 128),
                        ),
                ),
            )
        assertThat(page.next).isNotNull()
        // Радио не извлекает ни одного видео: ни reel, ни player, ни next самого видео — только страница Mix.
        assertThat(replay.seen).containsExactly("sw", "mix.lucky.1").inOrder()
    }

    @Test
    fun `every track of a page can be put in the catalog and played`() {
        val tracks = source.radio(LUCKY, null).tracks + source.radio(KROVI, null).tracks

        assertThat(tracks).hasSize(24 + 24)
        for (track in tracks) {
            assertThat(track.id).matches("[A-Za-z0-9_-]{11}")
            assertThat(track.title).isNotEmpty()
            assertThat(track.duration).isNotNull()
            assertThat(track.duration!!).isGreaterThan(0.seconds)
            assertThat(track.album).isNull()
            assertThat(track.number).isNull()
            assertThat(track.variants.map(YoutubeVariant::id)).containsExactly("${track.id}/251", "${track.id}/140")
                .inOrder()
        }
    }

    @Test
    fun `the second and the third pages come by the key, bring the new tracks, and each has a key of its own`() {
        val first = source.radio(LUCKY, null)
        val second = source.radio(LUCKY, first.next)
        val third = source.radio(LUCKY, second.next)

        assertThat(second.ids()).containsExactlyElementsIn(LUCKY_PAGE_2).inOrder()
        assertThat(third.ids().first()).isEqualTo("FQlAEiCb8m0")
        assertThat(third.ids().last()).isEqualTo("Bavc0C_5354")
        assertThat(third.ids()).hasSize(24)
        // Подборка бесконечна: ключ следующей страницы есть всегда, и каждый — свой.
        assertThat(setOf(first.next, second.next, third.next)).hasSize(3)
        assertThat(third.next).isNotNull()
        // Продолжение — один запрос на страницу: `sw.js` уже получен, видео не извлекаются.
        assertThat(replay.seen).containsExactly("sw", "mix.lucky.1", "mix.lucky.2", "mix.lucky.3").inOrder()
    }

    @Test
    fun `the Topic suffix of the uploader is not part of the artist`() {
        val page = source.radio(LUCKY, null)
        val second = source.radio(LUCKY, page.next)

        val artists = second.tracks.associate { it.id to it.artist }

        assertThat(artists["T3CuH5A6DnE"]).isEqualTo("Junior Senior")
        assertThat(artists["ci2_vHkS5Uk"]).isEqualTo("10cc")
        assertThat(artists["f9oJAmllF2o"]).isEqualTo("A Taste Of Honey")
    }

    @Test
    fun `a Mix in Cyrillic keeps its titles and artists, and the seed is not on the first page`() {
        val page = source.radio(KROVI, null)

        assertThat(page.tracks).hasSize(24)
        assertThat(page.ids()).doesNotContain(KROVI)
        assertThat(page.tracks[0])
            .isEqualTo(
                YoutubeTrack(
                    id = "Z4jQ4hZfk00",
                    title = "Звезда по имени Солнце",
                    artist = "Группа КИНО",
                    album = null,
                    number = null,
                    duration = 226.seconds,
                    variants =
                        listOf(
                            YoutubeVariant("Z4jQ4hZfk00/251", YoutubeFormat.OPUS, 160),
                            YoutubeVariant("Z4jQ4hZfk00/140", YoutubeFormat.AAC, 128),
                        ),
                ),
            )
        assertThat(page.tracks[13].title).isEqualTo("КИНО | КУКУШКА")
        assertThat(page.tracks[13].artist).isEqualTo("mrPhakel")
    }

    @Test
    fun `the seed does not come back on a later page either`() {
        val first = source.radio(KROVI, null)

        val second = source.radio(KROVI, first.next)

        // На записанной второй странице YouTube снова предложил затравку: 24 элемента, из них один — она.
        assertThat(second.tracks).hasSize(23)
        assertThat(second.ids()).doesNotContain(KROVI)
        assertThat(second.ids().first()).isEqualTo("DiDUIfMk7go")
        assertThat(replay.seen).containsExactly("sw", "mix.krovi.1", "mix.krovi.2").inOrder()
    }

    /** Одну песню Mix называет не раз: дедуп по очереди — дело того, кто её знает (`RadioFeeder`, Ю4в), не источника. */
    @Test
    fun `repeats between pages come through as they are`() {
        val first = source.radio(LUCKY, null)
        val second = source.radio(LUCKY, first.next)

        val repeated = first.ids().intersect(second.ids().toSet())

        assertThat(repeated)
            .containsExactly("iU7oF4OXZSE", "qFLhGq0060w", "wU26xVT_vBU", "qod03PVTLqk", "hN5X4kGhAtU")
    }

    @Test
    fun `a video seed and its variants open the same Mix, and one key serves them all`() {
        val byVideo = source.radio(LUCKY, null)
        val byOpus = source.radio("$LUCKY/251", null)
        val byAac = source.radio("$LUCKY/140", null)

        assertThat(byOpus.tracks).isEqualTo(byVideo.tracks)
        assertThat(byAac.tracks).isEqualTo(byVideo.tracks)
        val second = source.radio("$LUCKY/140", byVideo.next)
        assertThat(second.ids()).containsExactlyElementsIn(LUCKY_PAGE_2).inOrder()
    }

    @Test
    fun `a key is short and plain, not the page of the extractor`() {
        val keys = (1..3).map { source.radio(LUCKY, null).next }

        for (key in keys) {
            assertThat(key).matches("[A-Za-z0-9]{8,32}")
        }
        assertThat(keys.toSet()).hasSize(3)
    }

    @Test
    fun `a key can be used again, so a retry after a failure gets the page`() {
        val flaky = ReplayDownloader().serve("mix.lucky.2", "errors/reel-html.json", "mix/lucky-2.json")
        val retrying = NewPipeYoutubeSource(flaky, table, nanoTime = clock::get)
        val key = retrying.radio(LUCKY, null).next

        val failure = assertThrows(YoutubeException.Parse::class.java) { retrying.radio(LUCKY, key) }
        val again = retrying.radio(LUCKY, key)
        val onceMore = retrying.radio(LUCKY, key)

        assertThat(failure.text).isEqualTo("ParsingException")
        assertThat(again.ids()).containsExactlyElementsIn(LUCKY_PAGE_2).inOrder()
        assertThat(onceMore.ids()).containsExactlyElementsIn(LUCKY_PAGE_2).inOrder()
        assertThat(flaky.count("mix.lucky.2")).isEqualTo(3)
    }

    @Test
    fun `a key belongs to the seed that got it, and a wrong try does not spend it`() {
        val key = source.radio(LUCKY, null).next

        val failure = assertThrows(YoutubeException.Unavailable::class.java) { source.radio(KROVI, key) }

        assertThat(failure.text).isEqualTo("page")
        assertThat(replay.seen).containsExactly("sw", "mix.lucky.1").inOrder()
        assertThat(source.radio(LUCKY, key).ids()).containsExactlyElementsIn(LUCKY_PAGE_2).inOrder()
    }

    @Test
    fun `a key that was never given is unavailable without asking the network`() {
        val unknown = listOf("", " ", "nope", "AAAAAAAAAAAA", "ключ", LUCKY, "https://www.youtube.com/watch?v=$LUCKY")

        for (key in unknown) {
            val failure = assertThrows(key, YoutubeException.Unavailable::class.java) { source.radio(LUCKY, key) }
            assertThat(failure.text).isEqualTo("page")
        }

        assertThat(replay.seen).isEmpty()
    }

    @Test
    fun `a key lives three hours from its birth, a use does not prolong it`() {
        val born = source.radio(LUCKY, null).next
        clock.set(2.hours.inWholeNanoseconds)
        val usedAtTwoHours = source.radio(LUCKY, born)
        clock.set(3.hours.inWholeNanoseconds - 1)
        val usedJustBefore = source.radio(LUCKY, born)

        clock.set(3.hours.inWholeNanoseconds)
        val expired = assertThrows(YoutubeException.Unavailable::class.java) { source.radio(LUCKY, born) }

        assertThat(expired.text).isEqualTo("page")
        // Ключи, выданные в два часа и без секунды в три, живы: у каждого свой срок.
        assertThat(source.radio(LUCKY, usedAtTwoHours.next).tracks).isNotEmpty()
        assertThat(source.radio(LUCKY, usedJustBefore.next).tracks).isNotEmpty()
    }

    @Test
    fun `eight keys are kept and the oldest goes first`() {
        val keys = (1..9).map { source.radio(LUCKY, null).next }

        val first = assertThrows(YoutubeException.Unavailable::class.java) { source.radio(LUCKY, keys.first()) }

        assertThat(keys.toSet()).hasSize(9)
        assertThat(first.text).isEqualTo("page")
        assertThat(source.radio(LUCKY, keys[1]).tracks).isNotEmpty()
        assertThat(source.radio(LUCKY, keys.last()).tracks).isNotEmpty()
    }

    @Test
    fun `foreign and broken seeds are unavailable without asking the network, with a key or without`() {
        val bad =
            listOf(
                "",
                " ",
                "abc",
                "${LUCKY}x",
                LUCKY.dropLast(1),
                "RD$LUCKY",
                "$LUCKY&list=RD$LUCKY",
                "$LUCKY/137",
                "$LUCKY/251/140",
                "$LUCKY/",
                "/251",
                RAM_ALBUM,
                KINO_ALBUM,
                "https://www.youtube.com/watch?v=$LUCKY",
                "../../etc/passwd",
            )
        val key = source.radio(LUCKY, null).next

        for (seed in bad) {
            assertThrows(seed, YoutubeException.Unavailable::class.java) { source.radio(seed, null) }
            val withKey = assertThrows(seed, YoutubeException.Unavailable::class.java) { source.radio(seed, key) }
            assertThat(withKey.text).isEqualTo("id")
        }

        assertThat(replay.seen).containsExactly("sw", "mix.lucky.1").inOrder()
    }

    @Test
    fun `a page also holds what cannot be played, and that is left out`() {
        val odd = ReplayDownloader().serve("mix.lucky.1", "mix/lucky-1-odd.json")
        val oddSource = NewPipeYoutubeSource(odd, table, nanoTime = clock::get)

        val page = oddSource.radio(LUCKY, null)

        // Закрытое (`[Deleted video]`) выбрасывает сам экстрактор; прямой эфир, видео без длительности и без названия, и
        // затравка — источник. Остальные двадцать идут в прежнем порядке.
        val dropped = setOf("qFLhGq0060w", "wU26xVT_vBU", "qod03PVTLqk", "EfqsJm6BKng")
        assertThat(page.ids()).containsExactlyElementsIn(LUCKY_PAGE_1.filterNot { it in dropped }).inOrder()
        assertThat(page.tracks).hasSize(20)
        assertThat(page.next).isNotNull()
    }

    @Test
    fun `a page of what cannot be played is empty but has its key, the next one comes`() {
        val skipped = ReplayDownloader().serve("mix.lucky.2", "mix/lucky-2-skipped.json")
        val skippedSource = NewPipeYoutubeSource(skipped, table, nanoTime = clock::get)
        val first = skippedSource.radio(LUCKY, null)

        val empty = skippedSource.radio(LUCKY, first.next)
        val next = skippedSource.radio(LUCKY, empty.next)

        assertThat(empty.tracks).isEmpty()
        assertThat(empty.next).isNotNull()
        assertThat(empty.next).isNotEqualTo(first.next)
        assertThat(next.tracks).isNotEmpty()
    }

    @Test
    fun `a continuation with nothing new is an empty page with a key`() {
        val quiet = ReplayDownloader().serve("mix.lucky.2", "mix/lucky-2-quiet.json")
        val quietSource = NewPipeYoutubeSource(quiet, table, nanoTime = clock::get)
        val first = quietSource.radio(LUCKY, null)

        val page = quietSource.radio(LUCKY, first.next)

        assertThat(page.tracks).isEmpty()
        assertThat(page.next).isNotNull()
    }

    @Test
    fun `an answer with no list is unavailable, the extractor reads it as a missing consent`() {
        val noList = ReplayDownloader().serve("mix.lucky.1", "mix/no-playlist.json")
        val noListSource = NewPipeYoutubeSource(noList, table, nanoTime = clock::get)

        val failure = assertThrows(YoutubeException.Unavailable::class.java) { noListSource.radio(LUCKY, null) }

        assertThat(failure.text).isEqualTo("ContentNotAvailableException")
    }

    @Test
    fun `html instead of json is a broken format, on the first page and on the next`() {
        val html = ReplayDownloader().serve("mix.lucky.1", "errors/reel-html.json")
        val htmlSource = NewPipeYoutubeSource(html, table, nanoTime = clock::get)
        val htmlLater = ReplayDownloader().serve("mix.lucky.2", "errors/reel-html.json")
        val laterSource = NewPipeYoutubeSource(htmlLater, table, nanoTime = clock::get)
        val laterKey = laterSource.radio(LUCKY, null).next

        val onFirst = assertThrows(YoutubeException.Parse::class.java) { htmlSource.radio(LUCKY, null) }
        val onNext = assertThrows(YoutubeException.Parse::class.java) { laterSource.radio(LUCKY, laterKey) }

        assertThat(onFirst.text).isEqualTo("ParsingException")
        assertThat(onNext.text).isEqualTo("ParsingException")
    }

    @Test
    fun `no connection is the networks trouble with the class name and nothing else, and gives no key`() {
        val down = ReplayDownloader().fail("mix.lucky.1") { UnknownHostException("secret.example $LUCKY") }
        val downSource = NewPipeYoutubeSource(down, table, nanoTime = clock::get)

        val failure = assertThrows(YoutubeException.Network::class.java) { downSource.radio(LUCKY, null) }

        assertThat(failure.text).isEqualTo("UnknownHostException")
        assertThat(failure.text).doesNotContain("secret")
        assertThat(failure.text).doesNotContain(LUCKY)
    }

    @Test
    fun `a continuation that times out is the networks trouble and the key stays`() {
        val slowNext = ReplayDownloader().fail("mix.lucky.2") { SocketTimeoutException("SECRET") }
        val slowSource = NewPipeYoutubeSource(slowNext, table, nanoTime = clock::get)
        val key = slowSource.radio(LUCKY, null).next

        val failure = assertThrows(YoutubeException.Network::class.java) { slowSource.radio(LUCKY, key) }
        val again = assertThrows(YoutubeException.Network::class.java) { slowSource.radio(LUCKY, key) }

        assertThat(failure.text).isEqualTo("timed out")
        assertThat(again.text).isEqualTo("timed out")
        assertThat(slowNext.count("mix.lucky.2")).isEqualTo(2)
    }

    @Test
    fun `a call that takes longer than its own budget ends as a timeout, and the next call has a budget of its own`() {
        val slow = ReplayDownloader().delayEachRequest(150)
        val tight = NewPipeYoutubeSource(slow, table, YoutubeBudgets(radio = 100.milliseconds), clock::get)
        val started = System.nanoTime()

        val failure = assertThrows(YoutubeException.Network::class.java) { tight.radio(LUCKY, null) }

        assertThat(failure.text).isEqualTo("timed out")
        // Первая страница — `sw.js` и страница Mix: на второй запрос срока уже нет.
        assertThat(slow.seen).containsExactly("sw")
        assertThat((System.nanoTime() - started) / NANOS_PER_MILLI).isLessThan(1_500L)
        assertThat(CallBudget.leftMillis()).isNull()
        val ready = NewPipeYoutubeSource(ReplayDownloader(), table, YoutubeBudgets(radio = 5_000.milliseconds))
        assertThat(ready.radio(LUCKY, null).tracks).isNotEmpty()
    }

    /** Поиск, альбом и поток живут своими сроками: срок радио их не трогает, и наоборот. */
    @Test
    fun `the budget of the radio is its own`() {
        assertThat(YoutubeBudgets().radio).isGreaterThan(1.seconds)
        assertThat(YoutubeBudgets().radio).isAtMost(YoutubeBudgets().extract)
        val tightRadio = YoutubeBudgets(radio = 1.milliseconds)
        assertThat(tightRadio.search).isEqualTo(YoutubeBudgets().search)
        assertThat(tightRadio.extract).isEqualTo(YoutubeBudgets().extract)
    }

    @Test
    fun `radio leaves the other calls as they were`() {
        val key = source.radio(LUCKY, null).next

        val track = source.resolve(LUCKY).single()
        val stream = source.stream("$LUCKY/251")
        val album = source.resolve(RAM_ALBUM)

        assertThat(track.id).isEqualTo(LUCKY)
        assertThat(stream.format).isEqualTo(YoutubeFormat.OPUS)
        assertThat(album).hasSize(13)
        assertThat(source.radio(LUCKY, key).tracks).isNotEmpty()
    }

    private fun YoutubeRadioPage.ids() = tracks.map(YoutubeTrack::id)

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** Первая страница Mix «Get Lucky» без затравки (`mix/lucky-1.json`): 24 трека по порядку. */
        val LUCKY_PAGE_1 =
            listOf(
                "cV9mMLDKIbI",
                "iU7oF4OXZSE",
                "qFLhGq0060w",
                "XXYlFuWEuKI",
                "wU26xVT_vBU",
                "0SVk3adkchY",
                "qod03PVTLqk",
                "h_7P0O-RSiE",
                "EfqsJm6BKng",
                "_GWKkqNoyEA",
                "TUVcZfQe-Kw",
                "XsMpXczOIPs",
                "vBtvhPM2GHc",
                "zuaBRqUBhyw",
                "ZbZSe6N_BXs",
                "or9maJRvSOw",
                "Kr4EQDVETuA",
                "ZbxEyWGvWO4",
                "Omnpu8mzX4c",
                "6IO74QV6wHY",
                "mRNcPbCJNJ8",
                "eRhg7qPLeN8",
                "hN5X4kGhAtU",
                "khnokW3Mw24",
            )

        /** Вторая страница (`mix/lucky-2.json`): первый элемент ответа — последний прошлой страницы, экстрактор его не отдаёт. */
        val LUCKY_PAGE_2 =
            listOf(
                "KJwQzgz6UBg",
                "QqBR2Rsd9UY",
                "PWAdn9zCil4",
                "PbpIyn70t8c",
                "T3CuH5A6DnE",
                "ci2_vHkS5Uk",
                "phNLASyPsUU",
                "f9oJAmllF2o",
                "iU7oF4OXZSE",
                "wU26xVT_vBU",
                "H64a2ggVIWc",
                "RmYCOm4ehKs",
                "NMRhx71bGo4",
                "hN5X4kGhAtU",
                "1G4isv_Fylg",
                "YykjpeuMNEk",
                "qFLhGq0060w",
                "qod03PVTLqk",
                "K0HSD_i2DvA",
                "KKsuL9QAeuo",
                "HyHNuVaZJ-k",
                "CfihYWRWRTQ",
                "K3JGxj2rvAs",
                "TWbsUhi_p_g",
            )
    }
}
