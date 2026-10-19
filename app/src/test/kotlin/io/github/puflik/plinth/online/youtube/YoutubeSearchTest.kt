package io.github.puflik.plinth.online.youtube

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Поиск `YoutubeSource` на записанных ответах YouTube Music: песни и альбомы, латиница и кириллица, четверть мест
 * альбомам, предел, пустой запрос, пустой ответ. Экстрактор настоящий (`v0.26.5`), сеть — [ReplayDownloader].
 */
class YoutubeSearchTest {
    private val replay = ReplayDownloader()
    private val source = NewPipeYoutubeSource(replay, FakeStatusTable())

    @Test
    fun `songs come first, then albums, with what the search knows about them`() {
        val found = source.search(RAM, 20)

        assertThat(found.map(YoutubeResult::kind))
            .containsExactlyElementsIn(List(8) { YoutubeKind.TRACK } + List(6) { YoutubeKind.ALBUM })
            .inOrder()
        assertThat(found.first())
            .isEqualTo(YoutubeResult("ZFZM6jDTWd4", YoutubeKind.TRACK, "Giorgio by Moroder", "Daft Punk", 545.seconds))
        assertThat(found[2].title).isEqualTo("Lose Yourself to Dance (feat. Pharrell Williams)")
        assertThat(found[2].duration).isEqualTo(354.seconds)
        // У альбома в поиске нет ни длительности, ни числа треков.
        assertThat(found[8])
            .isEqualTo(
                YoutubeResult(Recorded.RAM_ALBUM, YoutubeKind.ALBUM, "Random Access Memories", "Daft Punk", null),
            )
        assertThat(found[9].id).isEqualTo("OLAK5uy_mz6eafmqdRHSaR4IwG0ll6J6rgv0_ZpGw")
    }

    @Test
    fun `a Cyrillic query finds Cyrillic songs and albums`() {
        val found = source.search("Кино Группа крови", 20)

        assertThat(found).hasSize(6 + 4)
        assertThat(found.first())
            .isEqualTo(YoutubeResult("xtxjm7ciwmc", YoutubeKind.TRACK, "Группа крови", "Кино", 285.seconds))
        assertThat(found[6])
            .isEqualTo(
                YoutubeResult(
                    "OLAK5uy_nRndsUhiMCq2wRW3JCnDgDwD33CUalJZU",
                    YoutubeKind.ALBUM,
                    "Группа крови",
                    "Кино",
                    null,
                ),
            )
        assertThat(found.map(YoutubeResult::title)).contains("Звезда по имени Солнце")
    }

    @Test
    fun `albums get a quarter of the places while there are enough of them`() {
        fun kinds(limit: Int) = source.search(RAM, limit).map(YoutubeResult::kind)

        assertThat(
            kinds(8),
        ).containsExactlyElementsIn(List(6) { YoutubeKind.TRACK } + List(2) { YoutubeKind.ALBUM }).inOrder()
        assertThat(
            kinds(4),
        ).containsExactlyElementsIn(List(3) { YoutubeKind.TRACK } + List(1) { YoutubeKind.ALBUM }).inOrder()
        assertThat(kinds(3)).containsExactly(YoutubeKind.TRACK, YoutubeKind.TRACK, YoutubeKind.TRACK).inOrder()
        assertThat(kinds(1)).containsExactly(YoutubeKind.TRACK)
    }

    @Test
    fun `places that songs leave empty go to albums, and the other way round`() {
        val noSongs = ReplayDownloader().serve("search.songs.ram", "search/empty.json")
        val noAlbums = ReplayDownloader().serve("search.albums.ram", "search/empty.json")

        val albumsOnly = NewPipeYoutubeSource(noSongs, FakeStatusTable()).search(RAM, 5)
        val songsOnly = NewPipeYoutubeSource(noAlbums, FakeStatusTable()).search(RAM, 20)

        assertThat(albumsOnly.map(YoutubeResult::kind)).containsExactlyElementsIn(List(5) { YoutubeKind.ALBUM })
        assertThat(songsOnly.map(YoutubeResult::kind)).containsExactlyElementsIn(List(8) { YoutubeKind.TRACK })
    }

    @Test
    fun `the limit is one to a hundred`() {
        assertThat(source.search(RAM, 0)).hasSize(1)
        assertThat(source.search(RAM, -5)).hasSize(1)
        assertThat(source.search(RAM, 1_000)).hasSize(14)
    }

    @Test
    fun `a blank query asks nobody`() {
        for (blank in listOf("", " ", " \t\n ")) {
            assertThat(source.search(blank, 20)).isEmpty()
        }

        assertThat(replay.seen).isEmpty()
    }

    @Test
    fun `a source built the production way asks nobody for a blank query`() {
        val production = NewPipeYoutubeSource(FakeStatusTable())

        assertThat(production.search("  ", 20)).isEmpty()
    }

    @Test
    fun `nothing found is an empty list, and the query goes trimmed in two searches`() {
        val found = source.search("  zzz nothing here \n", 20)

        assertThat(found).isEmpty()
        assertThat(replay.count("search.songs.empty")).isEqualTo(1)
        assertThat(replay.count("search.albums.empty")).isEqualTo(1)
    }

    @Test
    fun `one failing search fails the whole search, nothing partial is returned`() {
        replay.fail("search.albums.ram") { IOException("connection reset") }

        val failure = assertThrows(YoutubeException.Network::class.java) { source.search(RAM, 20) }

        assertThat(failure.text).isEqualTo("IOException")
    }

    @Test
    fun `search results are repeatable`() {
        assertThat(source.search(RAM, 20)).isEqualTo(source.search(RAM, 20))
    }

    private companion object {
        const val RAM = "Daft Punk Random Access Memories"
    }
}
