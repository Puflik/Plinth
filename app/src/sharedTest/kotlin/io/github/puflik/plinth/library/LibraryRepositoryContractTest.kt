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
    fun search_finds_every_word_in_title_artist_or_album() =
        contract { repository ->
            val yesterday = track(id = 1, title = "Yesterday", artist = "The Beatles", album = "Help!")
            val bohemian = track(id = 2, title = "Bohemian Rhapsody", artist = "Queen", album = "A Night at the Opera")
            val various = track(id = 3, title = "Intro", artist = "DJ", album = "Mix", albumArtist = "Various Queens")
            repository.upsert(listOf(yesterday, bohemian, various))

            assertThat(repository.search("beat").first()).containsExactly(yesterday)
            assertThat(repository.search("  QUEEN   opera ").first()).containsExactly(bohemian)
            assertThat(repository.search("queen").first()).containsExactly(bohemian, various).inOrder()
            assertThat(repository.search("queen jazz").first()).isEmpty()
        }

    @Test
    fun search_ignores_case_and_accents() =
        contract { repository ->
            val halo = track(id = 1, title = "Halo", artist = "Beyoncé")
            val tree = track(id = 2, title = "Ёлочка", artist = "Хор")
            repository.upsert(listOf(halo, tree))

            assertThat(repository.search("BEYONCE").first()).containsExactly(halo)
            assertThat(repository.search("елочка").first()).containsExactly(tree)
            assertThat(repository.search("ЁЛОЧ").first()).containsExactly(tree)
        }

    @Test
    fun search_goes_by_title_and_skips_missing_tracks() =
        contract { repository ->
            val zebra = track(id = 1, title = "Zebra", artist = "Queen")
            val anthem = track(id = 2, title = "Anthem", artist = "Queen")
            val gone = track(id = 3, title = "Bicycle", artist = "Queen")
            repository.upsert(listOf(zebra, anthem, gone))
            repository.markMissing(listOf(gone.id))

            assertThat(repository.search("queen").first()).containsExactly(anthem, zebra).inOrder()
        }

    @Test
    fun blank_search_finds_nothing() =
        contract { repository ->
            repository.upsert(listOf(track(id = 1, title = "Anything")))

            assertThat(repository.search("").first()).isEmpty()
            assertThat(repository.search("   ").first()).isEmpty()
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

    /** С артиклем `The Wall` стоял бы раньше `Time`: `th` < `ti`. */
    @Test
    fun tracks_by_title_ignore_leading_article() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, title = "Time"),
                    track(id = 2, title = "The Wall"),
                    track(id = 3, title = "Animals"),
                ),
            )

            assertThat(repository.titles(TrackSort.TITLE))
                .containsExactly("Animals", "Time", "The Wall")
                .inOrder()
        }

    @Test
    fun tracks_with_equal_titles_follow_artist() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, title = "Intro"),
                    track(id = 2, title = "Intro", artist = "Coldplay"),
                    track(id = 3, title = "Intro", artist = "The Beatles"),
                ),
            )

            assertThat(repository.tracks(TrackSort.TITLE).first().map(LibraryTrack::artist))
                .containsExactly("The Beatles", "Coldplay", null)
                .inOrder()
        }

    @Test
    fun tracks_with_equal_titles_keep_order_of_ids() =
        contract { repository ->
            repository.upsert(listOf(track(id = 2, title = "Intro"), track(id = 1, title = "intro")))

            assertThat(repository.tracks().first().map(LibraryTrack::id)).containsExactly(1L, 2L).inOrder()
        }

    /**
     * Ключи сравниваются по кодовым точкам, как в SQLite и Rust, а не по UTF-16,
     * как `String.compareTo`: эмодзи (вне BMP) — после полуширинной катаканы U+FF76.
     */
    @Test
    fun tracks_by_title_compare_code_points() =
        contract { repository ->
            val kana = "ｶ Kana"
            val smile = "😀 Smile"
            repository.upsert(listOf(track(id = 1, title = smile), track(id = 2, title = kana)))

            assertThat(repository.titles(TrackSort.TITLE)).containsExactly(kana, smile).inOrder()
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

    /** Одноимённые альбомы не перемешиваются: первый диск ABBA целиком раньше первого диска Queen. */
    @Test
    fun tracks_by_album_keep_discs_and_same_named_albums_apart() =
        contract { repository ->
            fun hit(
                id: Long,
                title: String,
                artist: String,
                disc: Int,
                number: Int,
            ) = track(id, title, artist, "Greatest Hits", discNumber = disc, trackNumber = number)
            repository.upsert(
                listOf(
                    hit(id = 1, title = "q-d2-t1", artist = "Queen", disc = 2, number = 1),
                    hit(id = 2, title = "a-t1", artist = "ABBA", disc = 1, number = 1),
                    hit(id = 3, title = "q-d1-t2", artist = "Queen", disc = 1, number = 2),
                    hit(id = 4, title = "a-t2", artist = "ABBA", disc = 1, number = 2),
                    track(id = 5, title = "no-album", artist = "ABBA"),
                    hit(id = 6, title = "q-d1-t1", artist = "Queen", disc = 1, number = 1),
                ),
            )

            assertThat(repository.titles(TrackSort.ALBUM))
                .containsExactly("a-t1", "a-t2", "q-d1-t1", "q-d1-t2", "q-d2-t1", "no-album")
                .inOrder()
        }

    /** Одноимённые альбомы — по владельцу, и тоже без артикля: The Kinks под «K». */
    @Test
    fun albums_group_tracks_by_title_and_owner() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, artist = "Queen", album = "Greatest Hits"),
                    track(id = 2, artist = "The Kinks", album = "Greatest Hits"),
                    track(id = 3, artist = "Queen", album = "Greatest Hits"),
                ),
            )

            assertThat(repository.albums().first())
                .containsExactly(
                    Album("Greatest Hits", "The Kinks", 1, coverTrackUri = uriOf(2)),
                    Album("Greatest Hits", "Queen", 2, coverTrackUri = uriOf(1)),
                ).inOrder()
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

            assertThat(repository.albums().first())
                .containsExactly(Album("Now 1", "Various Artists", trackCount = 3, coverTrackUri = uriOf(1)))
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

    /** Внутри владельца — по названию без артикля: The Album раньше Arrival. */
    @Test
    fun albums_by_artist_put_unknown_owner_last() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, album = "Bootleg"),
                    track(id = 2, artist = "The Beatles", album = "Abbey Road"),
                    track(id = 3, artist = "ABBA", album = "Waterloo"),
                    track(id = 4, artist = "ABBA", album = "Arrival"),
                    track(id = 5, artist = "ABBA", album = "The Album"),
                ),
            )

            assertThat(repository.albums(AlbumSort.ARTIST).first().map(Album::title))
                .containsExactly("The Album", "Arrival", "Waterloo", "Abbey Road", "Bootleg")
                .inOrder()
        }

    /** У альбома нет `id`: равные по ключам идут по точному названию, затем по владельцу. */
    @Test
    fun albums_with_equal_keys_follow_exact_title_then_owner() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, artist = "The Beatles", album = "Abbey Road"),
                    track(id = 2, artist = "Beatles", album = "abbey road"),
                    track(id = 3, artist = "Beatles", album = "Abbey Road"),
                ),
            )
            val exactOrder =
                listOf(
                    Album("Abbey Road", "Beatles", 1, coverTrackUri = uriOf(3)),
                    Album("Abbey Road", "The Beatles", 1, coverTrackUri = uriOf(1)),
                    Album("abbey road", "Beatles", 1, coverTrackUri = uriOf(2)),
                )

            assertThat(repository.albums(AlbumSort.TITLE).first()).containsExactlyElementsIn(exactOrder).inOrder()
            assertThat(repository.albums(AlbumSort.ARTIST).first()).containsExactlyElementsIn(exactOrder).inOrder()
        }

    /**
     * Диск без номера — первым: у однодисковых альбомов тега диска обычно нет.
     * Трек без номера — последним на диске, равные номера — по названию.
     */
    @Test
    fun album_tracks_follow_disc_then_track_number() =
        contract { repository ->
            val album = "Mellon Collie"
            val artist = "Smashing Pumpkins"

            fun side(
                id: Long,
                title: String,
                disc: Int?,
                number: Int?,
            ) = track(id, title, artist, album, discNumber = disc, trackNumber = number)
            repository.upsert(
                listOf(
                    side(id = 1, title = "d2-t1", disc = 2, number = 1),
                    side(id = 2, title = "d1-none", disc = 1, number = null),
                    side(id = 3, title = "d1-t10", disc = 1, number = 10),
                    side(id = 4, title = "d1-t2", disc = 1, number = 2),
                    track(id = 5, title = "other", artist = "Other", album = album, trackNumber = 1),
                    side(id = 6, title = "none-t5", disc = null, number = 5),
                    side(id = 7, title = "d1-bonus", disc = 1, number = null),
                ),
            )

            val tracks = repository.albumTracks(Album(album, artist, trackCount = 6)).first()

            assertThat(tracks.map(LibraryTrack::title))
                .containsExactly("none-t5", "d1-t2", "d1-t10", "d1-bonus", "d1-none", "d2-t1")
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

    /**
     * Обложка альбома — встроенная картинка его первого трека в порядке
     * [LibraryRepository.albumTracks]; пропавший трек её не даёт.
     */
    @Test
    fun album_cover_comes_from_its_first_track() =
        contract { repository ->
            repository.upsert(
                listOf(
                    track(id = 1, artist = "Queen", album = "Jazz", discNumber = 2, trackNumber = 1),
                    track(id = 2, artist = "Queen", album = "Jazz", discNumber = 1, trackNumber = 1),
                    track(id = 3, artist = "Queen", album = "Jazz", discNumber = 1, trackNumber = 2),
                ),
            )

            assertThat(repository.onlyAlbumCover()).isEqualTo(uriOf(2))

            repository.markMissing(listOf(2L))

            assertThat(repository.onlyAlbumCover()).isEqualTo(uriOf(id = 3))
        }

    /**
     * Треки исполнителя (E5) — его треки в порядке вкладки «по исполнителю»:
     * альбомы по названию без артикля, внутри — по диску и номеру, без
     * альбома — в конце. Сборники тоже: исполнитель — тег трека.
     */
    @Test
    fun artist_tracks_follow_artist_order() =
        contract { repository ->
            repository.upsert(artistLibrary())

            val tracks = repository.artistTracks("Queen").first()

            assertThat(tracks.map(LibraryTrack::title))
                .containsExactly("Track 3", "Track 1", "Track 2", "Track 6", "Track 4")
                .inOrder()
            assertThat(tracks)
                .containsExactlyElementsIn(repository.tracks(TrackSort.ARTIST).first().filter { it.artist == "Queen" })
                .inOrder()
        }

    /** Альбомы исполнителя — где есть его треки; число треков и обложка — всего альбома. */
    @Test
    fun artist_albums_are_albums_with_his_tracks() =
        contract { repository ->
            repository.upsert(artistLibrary())

            val albums = repository.artistAlbums("Queen").first()

            assertThat(albums.map { it.title to it.artist })
                .containsExactly("Jazz" to "Queen", "A Night at the Opera" to "Queen", "Now 1" to "Various Artists")
                .inOrder()
            assertThat(albums.last()).isEqualTo(Album("Now 1", "Various Artists", trackCount = 2, uriOf(id = 6)))
        }

    @Test
    fun missing_tracks_leave_the_artist() =
        contract { repository ->
            repository.upsert(artistLibrary())

            val gone = artistLibrary().filter { it.album == "A Night at the Opera" || it.album == "Now 1" }
            repository.markMissing(gone.map(LibraryTrack::id))

            assertThat(repository.artistTracks("Queen").first().map(LibraryTrack::title))
                .containsExactly("Track 3", "Track 1", "Track 4")
            assertThat(repository.artistAlbums("Queen").first().map(Album::title)).containsExactly("Jazz")
            assertThat(repository.artistTracks("Nobody").first()).isEmpty()
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
            assertThat(repository.albums().first()).containsExactly(Album("Jazz", "Queen", 1, coverTrackUri = uriOf(2)))
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
        uri = uriOf(id),
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

    /** Queen на трёх альбомах (один — сборник) и без альбома; ABBA рядом. */
    private fun artistLibrary() =
        listOf(
            track(id = 1, artist = "Queen", album = "Jazz", trackNumber = 2),
            track(id = 2, artist = "Queen", album = "A Night at the Opera", trackNumber = 1),
            track(id = 3, artist = "Queen", album = "Jazz", trackNumber = 1),
            track(id = 4, artist = "Queen"),
            track(id = 5, artist = "ABBA", album = "Arrival"),
            track(id = 6, artist = "Queen", album = "Now 1", albumArtist = "Various Artists"),
            track(id = 7, artist = "ABBA", album = "Now 1", albumArtist = "Various Artists"),
        )

    /** `content://` трека [id] — такой же, как у [track]. */
    protected fun uriOf(id: Long): String = "content://media/external/audio/media/$id"

    private suspend fun LibraryRepository.onlyAlbumCover(): String? = albums().first().single().coverTrackUri

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
