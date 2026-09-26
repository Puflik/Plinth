package io.github.puflik.plinth.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.CodePointOrder
import io.github.puflik.plinth.library.sort.TrackSort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Контракт `LibraryRepository` (C3.3, D3b) — требования к любому хранилищу
 * фонотеки.
 *
 * Устроен как контракт движка: `FakeLibraryRepository` проходит его на JVM,
 * ядро на Rust (`CoreLibraryRepository`) — на эмуляторе. Экраны тестируются
 * на фейке, поэтому всё, что им важно в порядке и группировке списков,
 * должно быть записано здесь.
 *
 * Хранилище наполняется как сканом — файлами с тегами ([seed]), пропавшие
 * файлы скрываются ([hide]). Идентификаторы треков выдаёт хранилище,
 * поэтому треки сравниваются по названию.
 *
 * Имена тестов — через подчёркивание: класс собирается и в APK для эмулятора.
 */
abstract class LibraryRepositoryContractTest {
    /** Пустое хранилище; закрывает его [closeRepository]. */
    protected abstract fun createRepository(): LibraryRepository

    protected open fun closeRepository(repository: LibraryRepository) = Unit

    /** Кладёт [files] в [repository] — как их записал бы скан. */
    protected abstract suspend fun seed(
        repository: LibraryRepository,
        files: List<TaggedFile>,
    )

    /** Файлы [paths] пропали — как их отметил бы скан. */
    protected abstract suspend fun hide(
        repository: LibraryRepository,
        paths: List<String>,
    )

    /** Ставит лайк трекам файлов [paths] — как его поставил бы человек (D4). */
    protected abstract suspend fun like(
        repository: LibraryRepository,
        paths: List<String>,
    )

    /** Файл [path] дослушали до конца [times] раз — позже всех прежних прослушиваний (D4). */
    protected abstract suspend fun play(
        repository: LibraryRepository,
        path: String,
        times: Int,
    )

    /** Предел ожидания списка. */
    protected open val timeout: Duration = 5.seconds

    @Test
    fun new_repository_is_empty() =
        contract { repository ->
            assertThat(repository.tracks().first()).isEmpty()
            assertThat(repository.albums().first()).isEmpty()
            assertThat(repository.artists().first()).isEmpty()
        }

    @Test
    fun seeded_tracks_are_listed_with_their_tags() =
        contract { repository ->
            repository.seed(
                file(n = 1, "Mustapha", "Queen", "Jazz", albumArtist = "Queen", discNumber = 1, trackNumber = 3),
            )

            val listed = repository.tracks().first().single()

            assertThat(listed)
                .isEqualTo(
                    LibraryTrack(
                        id = listed.id,
                        uri = pathOf(n = 1),
                        title = "Mustapha",
                        artist = "Queen",
                        album = "Jazz",
                        albumArtist = "Queen",
                        discNumber = 1,
                        trackNumber = 3,
                        duration = TRACK_LENGTH,
                        folder = FOLDER,
                    ),
                )
        }

    @Test
    fun untitled_file_is_named_after_the_file() =
        contract { repository ->
            repository.seed(file(n = 1, title = null))

            assertThat(repository.titles(TrackSort.TITLE)).containsExactly("track-1")
        }

    @Test
    fun search_finds_every_word_in_title_artist_or_album() =
        contract { repository ->
            repository.seed(
                file(n = 1, "Yesterday", "The Beatles", "Help!"),
                file(n = 2, "Bohemian Rhapsody", "Queen", "A Night at the Opera"),
                file(n = 3, "Intro", "DJ", "Mix", albumArtist = "Various Queens"),
            )

            assertThat(repository.search("beat").titles()).containsExactly("Yesterday")
            assertThat(repository.search("  QUEEN   opera ").titles()).containsExactly("Bohemian Rhapsody")
            assertThat(repository.search("queen").titles()).containsExactly("Bohemian Rhapsody", "Intro").inOrder()
            assertThat(repository.search("queen jazz").titles()).isEmpty()
        }

    @Test
    fun search_ignores_case_and_accents() =
        contract { repository ->
            repository.seed(file(n = 1, "Halo", "Beyoncé"), file(n = 2, "Ёлочка", "Хор"))

            assertThat(repository.search("BEYONCE").titles()).containsExactly("Halo")
            assertThat(repository.search("елочка").titles()).containsExactly("Ёлочка")
            assertThat(repository.search("ЁЛОЧ").titles()).containsExactly("Ёлочка")
        }

    /** Ядро сравнивает без знаков препинания: `acdc` находит `AC/DC`, `dont` — `Don't`. */
    @Test
    fun search_ignores_punctuation() =
        contract { repository ->
            repository.seed(file(n = 1, "Thunderstruck", "AC/DC"), file(n = 2, "Don't Stop Me Now", "Queen"))

            assertThat(repository.search("acdc").titles()).containsExactly("Thunderstruck")
            assertThat(repository.search("ac/dc").titles()).containsExactly("Thunderstruck")
            assertThat(repository.search("dont stop").titles()).containsExactly("Don't Stop Me Now")
        }

    @Test
    fun search_goes_by_title_and_skips_missing_tracks() =
        contract { repository ->
            repository.seed(
                file(n = 1, "Zebra", "Queen"),
                file(n = 2, "Anthem", "Queen"),
                file(n = 3, "Bicycle", "Queen"),
            )
            repository.hide(pathOf(n = 3))

            assertThat(repository.search("queen").titles()).containsExactly("Anthem", "Zebra").inOrder()
        }

    @Test
    fun search_without_words_finds_nothing() =
        contract { repository ->
            repository.seed(file(n = 1, "Anything"))

            assertThat(repository.search("").first()).isEmpty()
            assertThat(repository.search("   ").first()).isEmpty()
            assertThat(repository.search(" % ").first()).isEmpty()
        }

    @Test
    fun tracks_by_title_follow_natural_order() =
        contract { repository ->
            repository.seed(file(n = 1, "Track 10"), file(n = 2, "track 2"))

            assertThat(repository.titles(TrackSort.TITLE)).containsExactly("track 2", "Track 10").inOrder()
        }

    /** С артиклем `The Wall` стоял бы раньше `Time`: `th` < `ti`. */
    @Test
    fun tracks_by_title_ignore_leading_article() =
        contract { repository ->
            repository.seed(file(n = 1, "Time"), file(n = 2, "The Wall"), file(n = 3, "Animals"))

            assertThat(repository.titles(TrackSort.TITLE))
                .containsExactly("Animals", "Time", "The Wall")
                .inOrder()
        }

    @Test
    fun tracks_with_equal_titles_follow_artist() =
        contract { repository ->
            repository.seed(file(n = 1, "Intro"), file(n = 2, "Intro", "Coldplay"), file(n = 3, "Intro", "The Beatles"))

            assertThat(repository.tracks(TrackSort.TITLE).first().map(LibraryTrack::artist))
                .containsExactly("The Beatles", "Coldplay", null)
                .inOrder()
        }

    @Test
    fun tracks_with_equal_keys_keep_the_order_they_came_in() =
        contract { repository ->
            repository.seed(file(n = 1, "intro"), file(n = 2, "Intro"))

            assertThat(repository.titles(TrackSort.TITLE)).containsExactly("intro", "Intro").inOrder()
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
            repository.seed(file(n = 1, smile), file(n = 2, kana))

            assertThat(repository.titles(TrackSort.TITLE)).containsExactly(kana, smile).inOrder()
        }

    @Test
    fun tracks_by_artist_keep_albums_in_track_order() =
        contract { repository ->
            repository.seed(
                file(n = 1, "b2", "The Beatles", "Help!", trackNumber = 2),
                file(n = 2, "c1", "Coldplay", "Parachutes", trackNumber = 1),
                file(n = 3, "b1", "The Beatles", "Help!", trackNumber = 1),
                file(n = 4, "a1", "ABBA", "Arrival", trackNumber = 1),
                file(n = 5, "b3", "The Beatles", "Abbey Road", trackNumber = 1),
            )

            assertThat(repository.titles(TrackSort.ARTIST)).containsExactly("a1", "b3", "b1", "b2", "c1").inOrder()
        }

    @Test
    fun tracks_without_artist_go_last_by_artist() =
        contract { repository ->
            repository.seed(file(n = 1, "unknown"), file(n = 2, "known", "Zz"))

            assertThat(repository.titles(TrackSort.ARTIST)).containsExactly("known", "unknown").inOrder()
        }

    /** Одноимённые альбомы не перемешиваются: первый диск ABBA целиком раньше первого диска Queen. */
    @Test
    fun tracks_by_album_keep_discs_and_same_named_albums_apart() =
        contract { repository ->
            fun hit(
                n: Int,
                title: String,
                artist: String,
                disc: Int,
                number: Int,
            ) = file(n, title, artist, "Greatest Hits", discNumber = disc, trackNumber = number)
            repository.seed(
                hit(n = 1, "q-d2-t1", "Queen", disc = 2, number = 1),
                hit(n = 2, "a-t1", "ABBA", disc = 1, number = 1),
                hit(n = 3, "q-d1-t2", "Queen", disc = 1, number = 2),
                hit(n = 4, "a-t2", "ABBA", disc = 1, number = 2),
                file(n = 5, "no-album", "ABBA"),
                hit(n = 6, "q-d1-t1", "Queen", disc = 1, number = 1),
            )

            assertThat(repository.titles(TrackSort.ALBUM))
                .containsExactly("a-t1", "a-t2", "q-d1-t1", "q-d1-t2", "q-d2-t1", "no-album")
                .inOrder()
        }

    /** Одноимённые альбомы — по исполнителю, и тоже без артикля: The Kinks под «K». */
    @Test
    fun albums_group_tracks_by_title_and_owner() =
        contract { repository ->
            repository.seed(
                file(n = 1, artist = "Queen", album = "Greatest Hits"),
                file(n = 2, artist = "The Kinks", album = "Greatest Hits"),
                file(n = 3, artist = "Queen", album = "Greatest Hits"),
            )

            assertThat(repository.albums().first())
                .containsExactly(
                    Album("Greatest Hits", "The Kinks", 1, coverTrackUri = pathOf(n = 2)),
                    Album("Greatest Hits", "Queen", 2, coverTrackUri = pathOf(n = 1)),
                ).inOrder()
        }

    @Test
    fun compilation_is_one_album_of_its_album_artist() =
        contract { repository ->
            repository.seed(
                file(n = 1, artist = "Queen", album = "Now 1", albumArtist = "Various Artists"),
                file(n = 2, artist = "ABBA", album = "Now 1", albumArtist = "Various Artists"),
                file(n = 3, artist = "Coldplay", album = "Now 1", albumArtist = "Various Artists"),
            )

            assertThat(repository.albums().first())
                .containsExactly(Album("Now 1", "Various Artists", trackCount = 3, coverTrackUri = pathOf(n = 1)))
        }

    /**
     * Без тега исполнителя альбома альбом принадлежит основному артисту трека:
     * гость из «feat.» не уводит трек в отдельный альбом. Трек знает этого
     * владельца — по нему экран открывает альбом.
     */
    @Test
    fun album_without_album_artist_belongs_to_the_main_artist() =
        contract { repository ->
            repository.seed(
                file(n = 1, "Get Lucky", "Daft Punk feat. Pharrell Williams", "Random Access Memories"),
                file(n = 2, "Contact", "Daft Punk", "Random Access Memories"),
            )

            val album = repository.albums().first().single()
            val lucky = repository.search("lucky").first().single()

            assertThat(album.artist).isEqualTo("Daft Punk")
            assertThat(album.trackCount).isEqualTo(2)
            assertThat(lucky.albumOwner).isEqualTo("Daft Punk")
            assertThat(repository.albumTracks(album).titles()).containsExactly("Contact", "Get Lucky")
        }

    @Test
    fun tracks_without_album_form_no_album() =
        contract { repository ->
            repository.seed(file(n = 1, artist = "Queen"))

            assertThat(repository.albums().first()).isEmpty()
        }

    @Test
    fun albums_by_title_ignore_article_and_follow_natural_order() =
        contract { repository ->
            repository.seed(
                file(n = 1, artist = "X", album = "Vol. 10"),
                file(n = 2, artist = "X", album = "The Wall"),
                file(n = 3, artist = "X", album = "Vol. 2"),
            )

            assertThat(repository.albums(AlbumSort.TITLE).first().map(Album::title))
                .containsExactly("Vol. 2", "Vol. 10", "The Wall")
                .inOrder()
        }

    @Test
    fun albums_by_title_put_unknown_owner_last() =
        contract { repository ->
            repository.seed(file(n = 1, album = "Bootleg"), file(n = 2, artist = "Zz", album = "Bootleg"))

            assertThat(repository.albums(AlbumSort.TITLE).first().map(Album::artist))
                .containsExactly("Zz", null)
                .inOrder()
        }

    /** Внутри владельца — по названию без артикля: The Album раньше Arrival. */
    @Test
    fun albums_by_artist_put_unknown_owner_last() =
        contract { repository ->
            repository.seed(
                file(n = 1, album = "Bootleg"),
                file(n = 2, artist = "The Beatles", album = "Abbey Road"),
                file(n = 3, artist = "ABBA", album = "Waterloo"),
                file(n = 4, artist = "ABBA", album = "Arrival"),
                file(n = 5, artist = "ABBA", album = "The Album"),
            )

            assertThat(repository.albums(AlbumSort.ARTIST).first().map(Album::title))
                .containsExactly("The Album", "Arrival", "Waterloo", "Abbey Road", "Bootleg")
                .inOrder()
        }

    /**
     * Альбом узнаётся по названию и владельцу без учёта регистра, диакритики и
     * знаков, как в ядре; показывается так, как пришёл первым. Равные по
     * ключам альбомы стоят в порядке появления.
     */
    @Test
    fun albums_are_known_by_title_and_owner_whatever_the_case() =
        contract { repository ->
            repository.seed(
                file(n = 1, artist = "The Beatles", album = "Abbey Road"),
                file(n = 2, artist = "Beatles", album = "abbey road"),
                file(n = 3, artist = "Beatles", album = "Abbey Road"),
            )
            val inOrderOfComing =
                listOf(
                    Album("Abbey Road", "The Beatles", 1, coverTrackUri = pathOf(n = 1)),
                    Album("abbey road", "Beatles", 2, coverTrackUri = pathOf(n = 2)),
                )

            assertThat(repository.albums(AlbumSort.TITLE).first()).containsExactlyElementsIn(inOrderOfComing).inOrder()
            assertThat(repository.albums(AlbumSort.ARTIST).first()).containsExactlyElementsIn(inOrderOfComing).inOrder()
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
                n: Int,
                title: String,
                disc: Int?,
                number: Int?,
            ) = file(n, title, artist, album, discNumber = disc, trackNumber = number)
            repository.seed(
                side(n = 1, "d2-t1", disc = 2, number = 1),
                side(n = 2, "d1-none", disc = 1, number = null),
                side(n = 3, "d1-t10", disc = 1, number = 10),
                side(n = 4, "d1-t2", disc = 1, number = 2),
                file(n = 5, "other", "Other", album, trackNumber = 1),
                side(n = 6, "none-t5", disc = null, number = 5),
                side(n = 7, "d1-bonus", disc = 1, number = null),
            )

            val tracks = repository.albumTracks(Album(album, artist, trackCount = 6)).titles()

            assertThat(tracks).containsExactly("none-t5", "d1-t2", "d1-t10", "d1-bonus", "d1-none", "d2-t1").inOrder()
        }

    @Test
    fun album_without_any_artist_has_its_tracks() =
        contract { repository ->
            repository.seed(file(n = 1, "anonymous", album = "Bootleg"), file(n = 2, artist = "X", album = "Bootleg"))

            val tracks = repository.albumTracks(Album("Bootleg", null, 1)).titles()

            assertThat(tracks).containsExactly("anonymous")
        }

    @Test
    fun unknown_album_has_no_tracks() =
        contract { repository ->
            repository.seed(file(n = 1, artist = "Queen", album = "Jazz"))

            assertThat(repository.albumTracks(Album("Jazz", "ABBA", 1)).first()).isEmpty()
            assertThat(repository.albumTracks(Album("Arrival", "Queen", 1)).first()).isEmpty()
        }

    /**
     * Обложка альбома — картинка его первого трека в порядке
     * [LibraryRepository.albumTracks]; пропавший трек её не даёт.
     */
    @Test
    fun album_cover_comes_from_its_first_track() =
        contract { repository ->
            repository.seed(
                file(n = 1, artist = "Queen", album = "Jazz", discNumber = 2, trackNumber = 1),
                file(n = 2, artist = "Queen", album = "Jazz", discNumber = 1, trackNumber = 1),
                file(n = 3, artist = "Queen", album = "Jazz", discNumber = 1, trackNumber = 2),
            )

            assertThat(repository.onlyAlbumCover()).isEqualTo(pathOf(n = 2))

            repository.hide(pathOf(n = 2))

            assertThat(repository.onlyAlbumCover()).isEqualTo(pathOf(n = 3))
        }

    /**
     * Треки исполнителя (E5) — его треки в порядке вкладки «по исполнителю»:
     * альбомы по названию без артикля, внутри — по диску и номеру, без
     * альбома — в конце. Сборники тоже: исполнитель — тег трека.
     */
    @Test
    fun artist_tracks_follow_artist_order() =
        contract { repository ->
            repository.seed(*artistLibrary())

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
            repository.seed(*artistLibrary())

            val albums = repository.artistAlbums("Queen").first()

            assertThat(albums.map { it.title to it.artist })
                .containsExactly("Jazz" to "Queen", "A Night at the Opera" to "Queen", "Now 1" to "Various Artists")
                .inOrder()
            assertThat(albums.last()).isEqualTo(Album("Now 1", "Various Artists", trackCount = 2, pathOf(n = 6)))
        }

    @Test
    fun missing_tracks_leave_the_artist() =
        contract { repository ->
            repository.seed(*artistLibrary())

            repository.hide(pathOf(n = 2), pathOf(n = 6), pathOf(n = 7))

            assertThat(repository.artistTracks("Queen").titles()).containsExactly("Track 3", "Track 1", "Track 4")
            assertThat(repository.artistAlbums("Queen").first().map(Album::title)).containsExactly("Jazz")
            assertThat(repository.artistTracks("Nobody").first()).isEmpty()
        }

    @Test
    fun artists_list_each_track_artist_once() =
        contract { repository ->
            repository.seed(
                file(n = 1, artist = "Queen", album = "Jazz"),
                file(n = 2, artist = "Queen", album = "Jazz"),
                file(n = 3, artist = "Queen", album = "Now 1", albumArtist = "Various Artists"),
                file(n = 4, artist = "Queen"),
                file(n = 5, artist = "ABBA", album = "Now 1", albumArtist = "Various Artists"),
                file(n = 6, album = "Bootleg"),
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
            repository.seed(
                file(n = 1, artist = "Coldplay"),
                file(n = 2, artist = "The Beatles"),
                file(n = 3, artist = "ABBA"),
            )

            assertThat(repository.artists().first().map(Artist::name))
                .containsExactly("ABBA", "The Beatles", "Coldplay")
                .inOrder()
        }

    /**
     * Строка исполнителя делится на артистов разделителями (D2.2): у дуэта
     * два исполнителя, и трек есть у каждого. Сама строка остаётся как в теге.
     */
    @Test
    fun artists_are_split_by_separators() =
        contract { repository ->
            repository.seed(
                file(n = 1, "Under Pressure", "Queen & David Bowie"),
                file(n = 2, "Get Lucky", "Daft Punk feat. Pharrell Williams"),
                file(n = 3, "Mustapha", "Queen"),
            )

            assertThat(repository.artists().first())
                .containsExactly(
                    Artist("Daft Punk", albumCount = 0, trackCount = 1),
                    Artist("David Bowie", albumCount = 0, trackCount = 1),
                    Artist("Pharrell Williams", albumCount = 0, trackCount = 1),
                    Artist("Queen", albumCount = 0, trackCount = 2),
                ).inOrder()
            assertThat(repository.artistTracks("David Bowie").titles()).containsExactly("Under Pressure")
            assertThat(repository.artistTracks("Queen").titles()).containsExactly("Mustapha", "Under Pressure")
            assertThat(
                repository
                    .search("pressure")
                    .first()
                    .single()
                    .artist,
            ).isEqualTo("Queen & David Bowie")
        }

    /** Исполнитель узнаётся по имени без учёта регистра и знаков; имя — как пришло первым. */
    @Test
    fun artist_is_known_by_name_whatever_the_case() =
        contract { repository ->
            repository.seed(file(n = 1, "One", "Queen"), file(n = 2, "Two", "QUEEN"))

            assertThat(repository.artists().first()).containsExactly(Artist("Queen", albumCount = 0, trackCount = 2))
            assertThat(repository.artistTracks("queen").titles()).containsExactly("One", "Two")
        }

    @Test
    fun missing_tracks_leave_every_list() =
        contract { repository ->
            repository.seed(
                file(n = 1, artist = "Queen", album = "Jazz"),
                file(n = 2, artist = "Queen", album = "Jazz"),
                file(n = 3, artist = "ABBA", album = "Arrival"),
            )

            repository.hide(pathOf(n = 1), pathOf(n = 3))

            assertThat(repository.titles(TrackSort.TITLE)).containsExactly("Track 2")
            assertThat(
                repository.albums().first(),
            ).containsExactly(Album("Jazz", "Queen", 1, coverTrackUri = pathOf(n = 2)))
            assertThat(repository.artists().first()).containsExactly(Artist("Queen", albumCount = 1, trackCount = 1))
            assertThat(repository.albumTracks(Album("Arrival", "ABBA", 1)).first()).isEmpty()
        }

    /**
     * Папки экран собирает сам и сортирует ключами хранилища: по ним имена
     * стоят так же, как названия в списках, — естественно и без артикля.
     */
    @Test
    fun sort_keys_order_names_like_the_lists() =
        contract { repository ->
            val names = listOf("The Wall", "track 2", "Time", "Track 10", "Élan", "ｶ Kana", "😀 Smile")

            val keys = repository.sortKeys(names)

            assertThat(keys).hasSize(names.size)
            assertThat(names.zip(keys).sortedWith(compareBy(CodePointOrder) { it.second }).map { it.first })
                .containsExactly("Élan", "Time", "track 2", "Track 10", "The Wall", "ｶ Kana", "😀 Smile")
                .inOrder()
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

            repository.seed(file(n = 1))

            assertThat(update.await().map(LibraryTrack::title)).containsExactly("Track 1")
        }

    @Test
    fun lists_follow_later_hiding() =
        contract { repository ->
            repository.seed(file(n = 1), file(n = 2))
            val update =
                async(start = CoroutineStart.UNDISPATCHED) { repository.tracks().first { it.size == 1 } }

            repository.hide(pathOf(n = 1))

            assertThat(update.await().map(LibraryTrack::title)).containsExactly("Track 2")
        }

    /** «Любимое» (D4b): треки с лайком, по названию, как [tracks]. */
    @Test
    fun liked_tracks_are_listed_by_title() =
        contract { repository ->
            repository.seed(file(n = 1, "Zebra"), file(n = 2, "Apple"), file(n = 3, "Mango"))

            like(repository, listOf(pathOf(1), pathOf(2)))

            val liked = repository.likedTracks().first { it.size == 2 }
            assertThat(liked.map(LibraryTrack::title)).containsExactly("Apple", "Zebra").inOrder()
            assertThat(liked.all(LibraryTrack::liked)).isTrue()
        }

    /** «Недавнее» (D4b): дослушанные треки, последний — первым; не слушанных нет. */
    @Test
    fun recent_tracks_go_newest_first() =
        contract { repository ->
            repository.seed(file(n = 1, "One"), file(n = 2, "Two"), file(n = 3, "Three"))

            play(repository, pathOf(2), times = 1)
            play(repository, pathOf(1), times = 1)

            assertThat(repository.recentTracks().first { it.size == 2 }.map(LibraryTrack::title))
                .containsExactly("One", "Two")
                .inOrder()
        }

    /** «Часто слушаю» (D4b): по числу прослушиваний, равные — по названию. */
    @Test
    fun most_played_go_first_then_by_title() =
        contract { repository ->
            repository.seed(file(n = 1, "Beta"), file(n = 2, "Alpha"), file(n = 3, "Gamma"))

            play(repository, pathOf(n = 3), times = 1)
            play(repository, pathOf(1), times = MOST)

            val order = repository.tracks(TrackSort.MOST_PLAYED).first { it.firstOrNull()?.title == "Beta" }
            assertThat(order.map(LibraryTrack::title)).containsExactly("Beta", "Gamma", "Alpha").inOrder()
        }

    /** Файл [n] с тегами, как его нашёл бы скан: путь и папка — от номера. */
    protected fun file(
        n: Int,
        title: String? = "Track $n",
        artist: String? = null,
        album: String? = null,
        albumArtist: String? = null,
        discNumber: Int? = null,
        trackNumber: Int? = null,
    ) = TaggedFile(
        path = pathOf(n),
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        discNumber = discNumber,
        trackNumber = trackNumber,
        duration = TRACK_LENGTH,
        folder = FOLDER,
    )

    /** Путь файла [n] — такой же, как у [file]. */
    protected fun pathOf(n: Int): String = "/storage/emulated/0/${FOLDER}track-$n.mp3"

    /** Queen на трёх альбомах (один — сборник) и без альбома; ABBA рядом. */
    private fun artistLibrary() =
        arrayOf(
            file(n = 1, artist = "Queen", album = "Jazz", trackNumber = 2),
            file(n = 2, artist = "Queen", album = "A Night at the Opera", trackNumber = 1),
            file(n = 3, artist = "Queen", album = "Jazz", trackNumber = 1),
            file(n = 4, artist = "Queen"),
            file(n = 5, artist = "ABBA", album = "Arrival"),
            file(n = 6, artist = "Queen", album = "Now 1", albumArtist = "Various Artists"),
            file(n = 7, artist = "ABBA", album = "Now 1", albumArtist = "Various Artists"),
        )

    private suspend fun LibraryRepository.seed(vararg files: TaggedFile) = seed(this, files.toList())

    private suspend fun LibraryRepository.hide(vararg paths: String) = hide(this, paths.toList())

    private suspend fun LibraryRepository.onlyAlbumCover(): String? = albums().first().single().coverTrackUri

    private suspend fun LibraryRepository.titles(sort: TrackSort): List<String> = tracks(sort).titles()

    private suspend fun Flow<List<LibraryTrack>>.titles(): List<String> = first().map(LibraryTrack::title)

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
        const val MOST = 3
        const val FOLDER = "Music/"
    }
}
