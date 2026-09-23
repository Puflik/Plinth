package io.github.puflik.plinth.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.TrackSort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Контракт `LibraryRepository` (C3.3) — требования к любому хранилищу
 * фонотеки.
 *
 * Устроен как контракт движка: `FakeLibraryRepository` проходит его на JVM,
 * Room-реализация — на эмуляторе, ядро на Rust в v0.2 — тем же способом.
 * Экраны тестируются на фейке, поэтому всё, что им важно в порядке и
 * группировке списков, должно быть записано здесь.
 *
 * Имена тестов — через подчёркивание: класс собирается и в APK для эмулятора.
 */
abstract class LibraryRepositoryContractTest {
    /** Пустое хранилище; закрывает его [closeRepository]. */
    protected abstract fun createRepository(): LibraryRepository

    protected open fun closeRepository(repository: LibraryRepository) = Unit

    /** Предел ожидания списка. */
    protected open val timeout: Duration = 5.seconds

    @Test
    fun new_repository_is_empty() =
        contract { repository ->
            assertThat(repository.tracks().first()).isEmpty()
            assertThat(repository.albums().first()).isEmpty()
            assertThat(repository.artists().first()).isEmpty()
            assertThat(repository.knownVersions()).isEmpty()
        }

    @Test
    fun upserted_tracks_are_listed() =
        contract { repository ->
            val track = track(id = 1, artist = "Queen", album = "Jazz", discNumber = 1, trackNumber = 3)

            repository.upsert(listOf(track))

            assertThat(repository.tracks().first()).containsExactly(track)
        }

    @Test
    fun upsert_replaces_track_with_same_id() =
        contract { repository ->
            repository.upsert(listOf(track(id = 1, title = "Old")))

            repository.upsert(listOf(track(id = 1, title = "New")))

            assertThat(repository.titles(TrackSort.TITLE)).containsExactly("New")
        }

    @Test
    fun tracks_by_title_follow_natural_order() =
        contract { repository ->
            repository.upsert(listOf(track(id = 1, title = "Track 10"), track(id = 2, title = "track 2")))

            assertThat(repository.titles(TrackSort.TITLE)).containsExactly("track 2", "Track 10").inOrder()
        }

    @Test
    fun tracks_by_title_ignore_leading_article() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, title = "Wish You Were Here"),
                    track(id = 2, title = "The Wall"),
                    track(id = 3, title = "Animals"),
                ),
            )

            assertThat(repository.titles(TrackSort.TITLE))
                .containsExactly("Animals", "The Wall", "Wish You Were Here")
                .inOrder()
        }

    @Test
    fun tracks_with_equal_titles_keep_order_of_ids() =
        contract { repository ->
            repository.upsert(listOf(track(id = 2, title = "Intro"), track(id = 1, title = "intro")))

            assertThat(repository.tracks().first().map(LibraryTrack::id)).containsExactly(1L, 2L).inOrder()
        }

    @Test
    fun tracks_by_artist_keep_albums_in_track_order() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, title = "b2", artist = "The Beatles", album = "Help!", trackNumber = 2),
                    track(id = 2, title = "c1", artist = "Coldplay", album = "Parachutes", trackNumber = 1),
                    track(id = 3, title = "b1", artist = "The Beatles", album = "Help!", trackNumber = 1),
                    track(id = 4, title = "a1", artist = "ABBA", album = "Arrival", trackNumber = 1),
                    track(id = 5, title = "b3", artist = "The Beatles", album = "Abbey Road", trackNumber = 1),
                ),
            )

            assertThat(repository.titles(TrackSort.ARTIST)).containsExactly("a1", "b3", "b1", "b2", "c1").inOrder()
        }

    @Test
    fun tracks_without_artist_go_last_by_artist() =
        contract { repository ->
            repository.upsert(listOf(track(id = 1, title = "unknown"), track(id = 2, title = "known", artist = "Zz")))

            assertThat(repository.titles(TrackSort.ARTIST)).containsExactly("known", "unknown").inOrder()
        }

    @Test
    fun tracks_by_album_keep_discs_and_same_named_albums_apart() =
        contract { repository ->
            fun hit(
                id: Long,
                title: String,
                artist: String,
                disc: Int?,
                number: Int,
            ) = track(id, title, artist, "Greatest Hits", discNumber = disc, trackNumber = number)
            repository.upsert(
                listOf(
                    hit(id = 1, title = "q-d2-t1", artist = "Queen", disc = 2, number = 1),
                    hit(id = 2, title = "a-t1", artist = "ABBA", disc = null, number = 1),
                    hit(id = 3, title = "q-d1-t2", artist = "Queen", disc = 1, number = 2),
                    hit(id = 4, title = "a-t2", artist = "ABBA", disc = null, number = 2),
                    track(id = 5, title = "no-album", artist = "ABBA"),
                    hit(id = 6, title = "q-d1-t1", artist = "Queen", disc = 1, number = 1),
                ),
            )

            assertThat(repository.titles(TrackSort.ALBUM))
                .containsExactly("a-t1", "a-t2", "q-d1-t1", "q-d1-t2", "q-d2-t1", "no-album")
                .inOrder()
        }

    @Test
    fun albums_group_tracks_by_title_and_owner() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, artist = "Queen", album = "Greatest Hits"),
                    track(id = 2, artist = "ABBA", album = "Greatest Hits"),
                    track(id = 3, artist = "Queen", album = "Greatest Hits"),
                ),
            )

            assertThat(repository.albums().first())
                .containsExactly(Album("Greatest Hits", "ABBA", 1), Album("Greatest Hits", "Queen", 2))
                .inOrder()
        }

    @Test
    fun compilation_is_one_album_of_its_album_artist() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, artist = "Queen", album = "Now 1", albumArtist = "Various Artists"),
                    track(id = 2, artist = "ABBA", album = "Now 1", albumArtist = "Various Artists"),
                    track(id = 3, artist = "Coldplay", album = "Now 1", albumArtist = "Various Artists"),
                ),
            )

            assertThat(repository.albums().first()).containsExactly(Album("Now 1", "Various Artists", trackCount = 3))
        }

    @Test
    fun tracks_without_album_form_no_album() =
        contract { repository ->
            repository.upsert(listOf(track(id = 1, artist = "Queen")))

            assertThat(repository.albums().first()).isEmpty()
        }

    @Test
    fun albums_by_title_ignore_article_and_follow_natural_order() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, artist = "X", album = "Vol. 10"),
                    track(id = 2, artist = "X", album = "The Wall"),
                    track(id = 3, artist = "X", album = "Vol. 2"),
                ),
            )

            assertThat(repository.albums(AlbumSort.TITLE).first().map(Album::title))
                .containsExactly("Vol. 2", "Vol. 10", "The Wall")
                .inOrder()
        }

    @Test
    fun albums_by_artist_put_unknown_owner_last() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, album = "Bootleg"),
                    track(id = 2, artist = "The Beatles", album = "Abbey Road"),
                    track(id = 3, artist = "ABBA", album = "Waterloo"),
                    track(id = 4, artist = "ABBA", album = "Arrival"),
                ),
            )

            assertThat(repository.albums(AlbumSort.ARTIST).first().map(Album::title))
                .containsExactly("Arrival", "Waterloo", "Abbey Road", "Bootleg")
                .inOrder()
        }

    @Test
    fun album_tracks_follow_disc_then_track_number() =
        contract { repository ->
            val album = "Mellon Collie"
            val artist = "Smashing Pumpkins"

            fun side(
                id: Long,
                title: String,
                disc: Int,
                number: Int?,
            ) = track(id, title, artist, album, discNumber = disc, trackNumber = number)
            repository.upsert(
                listOf(
                    side(id = 1, title = "d2-t1", disc = 2, number = 1),
                    side(id = 2, title = "d1-none", disc = 1, number = null),
                    side(id = 3, title = "d1-t10", disc = 1, number = 10),
                    side(id = 4, title = "d1-t2", disc = 1, number = 2),
                    track(id = 5, title = "other", artist = "Other", album = album, trackNumber = 1),
                ),
            )

            val tracks = repository.albumTracks(Album(album, artist, trackCount = 4)).first()

            assertThat(tracks.map(LibraryTrack::title))
                .containsExactly("d1-t2", "d1-t10", "d1-none", "d2-t1")
                .inOrder()
        }

    @Test
    fun album_without_any_artist_has_its_tracks() =
        contract { repository ->
            val anonymous = track(id = 1, album = "Bootleg")
            repository.upsert(listOf(anonymous, track(id = 2, artist = "X", album = "Bootleg")))

            val tracks = repository.albumTracks(Album("Bootleg", null, 1)).first()

            assertThat(tracks).containsExactly(anonymous)
        }

    @Test
    fun artists_list_each_track_artist_once() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, artist = "Queen", album = "Jazz"),
                    track(id = 2, artist = "Queen", album = "Jazz"),
                    track(id = 3, artist = "Queen", album = "Now 1", albumArtist = "Various Artists"),
                    track(id = 4, artist = "Queen"),
                    track(id = 5, artist = "ABBA", album = "Now 1", albumArtist = "Various Artists"),
                    track(id = 6, album = "Bootleg"),
                ),
            )

            assertThat(repository.artists().first())
                .containsExactly(
                    Artist(name = "ABBA", albumCount = 1, trackCount = 1),
                    Artist(name = "Queen", albumCount = 2, trackCount = 4),
                ).inOrder()
        }

    @Test
    fun artists_ignore_leading_article() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, artist = "Coldplay"),
                    track(id = 2, artist = "The Beatles"),
                    track(id = 3, artist = "ABBA"),
                ),
            )

            assertThat(repository.artists().first().map(Artist::name))
                .containsExactly("ABBA", "The Beatles", "Coldplay")
                .inOrder()
        }

    @Test
    fun missing_tracks_leave_every_list() =
        contract { repository ->
            val kept = track(id = 2, artist = "Queen", album = "Jazz")
            val gone =
                listOf(
                    track(id = 1, artist = "Queen", album = "Jazz"),
                    track(id = 3, artist = "ABBA", album = "Arrival"),
                )
            repository.upsert(gone + kept)

            repository.markMissing(gone.map(LibraryTrack::id))

            assertThat(repository.tracks().first()).containsExactly(kept)
            assertThat(repository.albums().first()).containsExactly(Album("Jazz", "Queen", 1))
            assertThat(repository.artists().first()).containsExactly(Artist("Queen", albumCount = 1, trackCount = 1))
            assertThat(repository.albumTracks(Album("Arrival", "ABBA", 1)).first()).isEmpty()
        }

    @Test
    fun upsert_brings_missing_track_back() =
        contract { repository ->
            repository.upsert(listOf(track(id = 1, title = "Old")))
            repository.markMissing(listOf(1L))

            repository.upsert(listOf(track(id = 1, title = "Back")))

            assertThat(repository.titles(TrackSort.TITLE)).containsExactly("Back")
        }

    @Test
    fun known_versions_cover_present_tracks_only() =
        contract { repository ->
            repository.upsert(
                listOf(track(id = 1, modifiedAt = FIRST_EDIT), track(id = 2, modifiedAt = SECOND_EDIT)),
            )

            repository.markMissing(listOf(2L))

            assertThat(repository.knownVersions()).containsExactly(1L, FIRST_EDIT)
        }

    @Test
    fun lists_follow_later_writes() =
        contract { repository ->
            val sawEmpty = CompletableDeferred<Unit>()
            val update =
                async(start = CoroutineStart.UNDISPATCHED) {
                    repository
                        .tracks()
                        .onEach { if (it.isEmpty()) sawEmpty.complete(Unit) }
                        .first { it.isNotEmpty() }
                }
            sawEmpty.await()

            repository.upsert(listOf(track(id = 1)))

            assertThat(update.await().map(LibraryTrack::id)).containsExactly(1L)
        }

    /** Трек с заполненными служебными полями: тестам важны только теги. */
    protected fun track(
        id: Long,
        title: String = "Track $id",
        artist: String? = null,
        album: String? = null,
        albumArtist: String? = null,
        discNumber: Int? = null,
        trackNumber: Int? = null,
        modifiedAt: Long = id,
    ) = LibraryTrack(
        id = id,
        uri = "content://media/external/audio/media/$id",
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        discNumber = discNumber,
        trackNumber = trackNumber,
        duration = TRACK_LENGTH,
        folder = "Music/",
        modifiedAt = modifiedAt,
    )

    private suspend fun LibraryRepository.titles(sort: TrackSort): List<String> =
        tracks(sort).first().map(LibraryTrack::title)

    /** Своё хранилище на каждый тест, общий предел ожидания, гарантированное закрытие. */
    private fun contract(body: suspend CoroutineScope.(LibraryRepository) -> Unit) =
        runBlocking {
            val repository = createRepository()
            try {
                withTimeout(timeout) { this.body(repository) }
            } finally {
                closeRepository(repository)
            }
        }

    private companion object {
        val TRACK_LENGTH = 3.minutes

        /** Время изменения файла, секунды эпохи — как `DATE_MODIFIED` в `MediaStore`. */
        const val FIRST_EDIT = 1_700_000_000L
        const val SECOND_EDIT = 1_700_000_100L
    }
}
