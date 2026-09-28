package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.StreamLookup
import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.TrackId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.UUID

/**
 * Контракт `OnlineRepository` (E3b) — онлайн-источники для экранов и плеера.
 * `FakeOnlineRepository` проходит его на JVM, ядро на Rust
 * (`CoreOnlineRepository`) — на эмуляторе, за сетью, которая отвечает как
 * Internet Archive: экраны поиска и альбома тестируются на фейке.
 *
 * В архиве один концерт [CONCERT] — «Test Concert» группы «Plinth Band»,
 * 1999: «Opening» и «Closing», у каждого FLAC и MP3.
 *
 * Имена тестов — через подчёркивание: класс собирается и в APK для эмулятора.
 */
abstract class OnlineRepositoryContractTest {
    /** Архив за сетью, которую тест выключает. */
    interface World {
        val online: OnlineRepository

        /** Есть ли сеть; нет — ни один запрос не получает ответа. */
        var networkUp: Boolean
    }

    protected abstract fun world(): World

    protected open fun close(world: World) = Unit

    private val worlds = mutableListOf<World>()

    @After
    fun closeWorlds() = worlds.forEach(::close)

    @Test
    fun search_gives_a_section_of_the_archive_with_the_concert() =
        contract { online ->
            val sections = online.search("concert")

            val section = sections.single()
            assertThat(section.provider).isEqualTo(ARCHIVE)
            assertThat(section.problem).isNull()
            val found = section.results.single()
            assertThat(found.external).isEqualTo(CONCERT)
            assertThat(found.kind).isEqualTo(OnlineKind.ALBUM)
            assertThat(listOf(found.title, found.artist)).containsExactly("Test Concert", "Plinth Band").inOrder()
            assertThat(found.year).isEqualTo(TestConcert.YEAR)
        }

    @Test
    fun a_blank_query_asks_nobody() =
        contract { online ->
            assertThat(online.search("  ")).isEmpty()
        }

    @Test
    fun without_network_the_section_says_so_and_is_not_an_error() =
        contract(networkUp = false) { online ->
            val section = online.search("concert").single()

            assertThat(section.problem).isEqualTo(OnlineProblem.NO_NETWORK)
            assertThat(section.results).isEmpty()
        }

    @Test
    fun the_concert_opens_with_its_tracks_in_order_and_their_variants() =
        contract { online ->
            val tracks = (online.album(ARCHIVE, CONCERT) as OnlineAlbum.Tracks).tracks

            assertThat(tracks.map { it.title }).containsExactly("Opening", "Closing").inOrder()
            assertThat(tracks.map { it.artist }).containsExactly("Plinth Band", "Plinth Band")
            assertThat(tracks.map { it.number }).containsExactly(1, 2).inOrder()
            tracks.forEach { track ->
                assertThat(track.variants.map { it.format }).containsExactly(AudioFormat.FLAC, AudioFormat.MP3)
            }
        }

    @Test
    fun without_network_the_concert_does_not_open() =
        contract(networkUp = false) { online ->
            assertThat(online.album(ARCHIVE, CONCERT)).isEqualTo(OnlineAlbum.Failed(OnlineProblem.NO_NETWORK))
        }

    @Test
    fun tracks_go_to_the_catalog_once_and_are_known_after_that() =
        contract { online ->
            val tracks = (online.album(ARCHIVE, CONCERT) as OnlineAlbum.Tracks).tracks
            assertThat(online.known(ARCHIVE, tracks)).containsExactly(null, null)

            val first = checkNotNull(online.add(ARCHIVE, tracks.take(1)))
            val all = checkNotNull(online.add(ARCHIVE, tracks))

            assertThat(all).hasSize(2)
            assertThat(all[0]).isEqualTo(first.single())
            assertThat(all[1]).isNotEqualTo(all[0])
            assertThat(online.known(ARCHIVE, tracks)).containsExactlyElementsIn(all).inOrder()
        }

    @Test
    fun a_track_streams_flac_on_wifi_and_mp3_on_cellular() =
        contract { online ->
            val tracks = (online.album(ARCHIVE, CONCERT) as OnlineAlbum.Tracks).tracks
            val id = checkNotNull(online.add(ARCHIVE, tracks)).first()

            val wifi = online.stream(id, metered = false) as StreamLookup.Found
            val cellular = online.stream(id, metered = true) as StreamLookup.Found

            assertThat(wifi.url).startsWith("https://")
            assertThat(wifi.url).endsWith(".flac")
            assertThat(cellular.url).endsWith(".mp3")
        }

    /** Android 8.0 не декодирует FLAC (F): по Wi-Fi тогда MP3, а не «формат не поддерживается». */
    @Test
    fun a_format_the_device_cannot_decode_is_taken_only_without_another() =
        contract { online ->
            val tracks = (online.album(ARCHIVE, CONCERT) as OnlineAlbum.Tracks).tracks
            val id = checkNotNull(online.add(ARCHIVE, tracks)).first()

            val noFlac = online.stream(id, metered = false, undecodable = setOf(AudioFormat.FLAC)) as StreamLookup.Found
            val nothing = online.stream(id, metered = false, undecodable = AudioFormat.entries.toSet())

            assertThat(noFlac.url).endsWith(".mp3")
            assertThat(nothing).isInstanceOf(StreamLookup.Found::class.java)
        }

    @Test
    fun a_track_without_variants_has_nothing_to_stream() =
        contract { online ->
            assertThat(online.stream(TrackId(UUID.randomUUID().toString()), metered = false))
                .isEqualTo(StreamLookup.Unavailable)
        }

    @Test
    fun switched_off_there_is_no_search_no_album_and_no_stream() =
        contract { online ->
            val tracks = (online.album(ARCHIVE, CONCERT) as OnlineAlbum.Tracks).tracks
            val ids = checkNotNull(online.add(ARCHIVE, tracks))

            online.setEnabled(false)

            assertThat(online.search("concert")).isEmpty()
            assertThat(online.album(ARCHIVE, CONCERT)).isInstanceOf(OnlineAlbum.Failed::class.java)
            assertThat(online.stream(ids[0], metered = false)).isEqualTo(StreamLookup.Unavailable)
            assertThat(online.known(ARCHIVE, tracks)).isEqualTo(ids)
        }

    @Test
    fun switched_on_again_it_works_again() =
        contract { online ->
            online.setEnabled(false)
            online.setEnabled(true)

            assertThat(online.search("concert").single().results).hasSize(1)
        }

    /** Архив с включёнными источниками; [networkUp] — есть ли сеть. */
    private fun contract(
        networkUp: Boolean = true,
        block: suspend (OnlineRepository) -> Unit,
    ) = runBlocking {
        val world = world().also(worlds::add)
        world.networkUp = networkUp
        world.online.setEnabled(true)
        block(world.online)
    }

    companion object {
        const val ARCHIVE = "archive.org"
        const val CONCERT = "plinth-test-concert"
    }
}
