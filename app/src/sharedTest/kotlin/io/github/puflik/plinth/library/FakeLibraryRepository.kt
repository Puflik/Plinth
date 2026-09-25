package io.github.puflik.plinth.library

import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.CodePointOrder
import io.github.puflik.plinth.library.sort.NaturalOrder
import io.github.puflik.plinth.library.sort.SortKeys
import io.github.puflik.plinth.library.sort.TrackSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Хранилище фонотеки в памяти для тестов (C3.3, D3b).
 *
 * Ведёт себя как ядро: сортирует и группирует в Kotlin то, что ядро делает
 * запросами; совпадение гарантирует общий контракт `LibraryRepositoryContractTest`.
 * Строки сравниваются по кодовым точкам ([CodePointOrder]), как в SQLite.
 * Равные по ключам треки и альбомы стоят в порядке добавления — в ядре так
 * растут их идентификаторы.
 *
 * Упрощения: исполнители делятся разделителями плана без списка исключений
 * (`AC/DC` здесь — два артиста), сборника без тега исполнителя альбома нет.
 *
 * Наполняется [upsert] и [add], пропавшие файлы — [markMissing] и [hide]:
 * методов записи у фасада нет, их здесь заменяет скан.
 */
class FakeLibraryRepository(
    private val keys: SortKeys = SortKeys(),
) : LibraryRepository {
    private data class Entry(
        val track: LibraryTrack,
        val missing: Boolean,
    )

    private val entries = MutableStateFlow<Map<TrackId, Entry>>(emptyMap())
    private var added = 0

    private val present: Flow<List<LibraryTrack>> =
        entries.map { all -> all.values.filterNot(Entry::missing).map(Entry::track) }

    /** Добавляет треки как есть или заменяет их по `id`; пропавший трек снова виден. */
    fun upsert(tracks: Collection<LibraryTrack>) {
        entries.update { all -> all + tracks.associate { it.id to Entry(it, missing = false) } }
    }

    /** Скрывает треки, файлов которых больше нет. */
    fun markMissing(ids: Collection<TrackId>) {
        entries.update { all -> all.mapValues { (id, entry) -> if (id in ids) entry.copy(missing = true) else entry } }
    }

    /**
     * Добавляет [files] новыми треками — как их записал бы скан ядра: без
     * названия — имя файла, без исполнителя альбома — основной артист трека.
     */
    fun add(files: Collection<TaggedFile>): List<LibraryTrack> {
        val tracks = files.map { file -> file.toTrack(TrackId("fake-%08d".format(++added))) }
        upsert(tracks)
        return tracks
    }

    /** Скрывает треки файлов [paths]. */
    fun hide(paths: Collection<String>) {
        markMissing(
            entries.value.values
                .filter { it.track.uri in paths }
                .map { it.track.id },
        )
    }

    override fun tracks(sort: TrackSort): Flow<List<LibraryTrack>> =
        present.map { tracks -> tracks.sortedWith(trackOrder(sort)) }

    override fun albums(sort: AlbumSort): Flow<List<Album>> = present.map { albumsOf(it, sort) }

    override fun artists(): Flow<List<Artist>> =
        present.map { tracks ->
            tracks
                .flatMap { track -> artistsOf(track.artist).map { name -> name to track } }
                .groupBy({ (name, _) -> normalize(name) })
                .map { (_, credits) ->
                    val tracksOfArtist = credits.map { it.second }
                    val albums = tracksOfArtist.mapNotNull(::albumIdentity).distinct()
                    Artist(credits.first().first, albums.size, tracksOfArtist.size)
                }.sortedWith(keyOrder(Artist::name))
        }

    override fun search(query: String): Flow<List<LibraryTrack>> {
        val words = normalize(query).split(' ').filter(String::isNotEmpty)
        if (words.isEmpty()) return flowOf(emptyList())
        return tracks(TrackSort.TITLE).map { tracks ->
            tracks.filter { track ->
                val text =
                    normalize(
                        listOfNotNull(track.title, track.artist, track.album, track.albumArtist).joinToString(" "),
                    )
                words.all(text::contains)
            }
        }
    }

    override fun albumTracks(album: Album): Flow<List<LibraryTrack>> {
        val wanted = normalize(album.title) to normalize(album.artist.orEmpty())
        return present.map { tracks -> tracks.filter { albumIdentity(it) == wanted }.sortedWith(inAlbumOrder()) }
    }

    override fun artistTracks(artist: String): Flow<List<LibraryTrack>> =
        present.map { tracks -> tracks.filter { it.isBy(artist) }.sortedWith(albumOrder().then(inAlbumOrder())) }

    override fun artistAlbums(artist: String): Flow<List<Album>> =
        present.map { tracks ->
            val his = tracks.filter { it.isBy(artist) }.mapNotNull(::albumIdentity).toSet()
            albumsOf(tracks, AlbumSort.TITLE).filter { (normalize(it.title) to normalize(it.artist.orEmpty())) in his }
        }

    override suspend fun sortKeys(names: List<String>): List<String> = names.map(keys::of)

    private fun albumsOf(
        tracks: List<LibraryTrack>,
        sort: AlbumSort,
    ): List<Album> =
        tracks
            .filter { it.album != null }
            .groupBy(::albumIdentity)
            .map { (_, tracksOfAlbum) ->
                val first = tracksOfAlbum.first()
                val cover = tracksOfAlbum.minWith(inAlbumOrder()).uri
                Album(checkNotNull(first.album), ownerOf(first), tracksOfAlbum.size, coverTrackUri = cover)
            }.sortedWith(
                when (sort) {
                    AlbumSort.TITLE -> keyOrder(Album::title).thenByKey(Album::artist)
                    AlbumSort.ARTIST -> keyOrder(Album::artist).thenByKey(Album::title)
                },
            )

    private fun trackOrder(sort: TrackSort): Comparator<LibraryTrack> =
        when (sort) {
            TrackSort.TITLE -> keyOrder(LibraryTrack::title).thenByKey(LibraryTrack::artist)
            TrackSort.ARTIST -> keyOrder(LibraryTrack::artist).then(albumOrder()).then(inAlbumOrder())
            TrackSort.ALBUM -> albumOrder().then(inAlbumOrder())
        }

    /** Альбомы по названию и владельцу; треки без альбома — в конце. */
    private fun albumOrder(): Comparator<LibraryTrack> =
        keyOrder(LibraryTrack::album).then(keyOrder { track: LibraryTrack -> track.album?.let { ownerOf(track) } })

    /** Порядок треков альбома: по диску и номеру, равные — по названию. */
    private fun inAlbumOrder(): Comparator<LibraryTrack> = discOrder().thenByKey(LibraryTrack::title)

    /** Диск без номера — первым, трек без номера — последним на своём диске. */
    private fun discOrder(): Comparator<LibraryTrack> =
        compareBy<LibraryTrack, Int?>(nullsFirst(naturalOrder())) { it.discNumber }
            .thenBy(nullsLast(naturalOrder())) { it.trackNumber }

    /** Порядок по ключу названия; без названия — в конце. */
    private fun <T> keyOrder(text: (T) -> String?): Comparator<T> =
        compareBy(nullsLast(CodePointOrder)) { item: T -> text(item)?.let(keys::of) }

    private fun <T> Comparator<T>.thenByKey(text: (T) -> String?): Comparator<T> = then(keyOrder(text))

    private companion object {
        /** Разделители исполнителей из плана (13.2): `;` `/` `feat.` `ft.` `&` `x`. */
        val SEPARATORS = Regex("""\s*[;/]\s*|\s+(?:feat\.|ft\.|featuring|&|x)\s+""")

        fun TaggedFile.toTrack(id: TrackId): LibraryTrack =
            LibraryTrack(
                id = id,
                uri = path,
                title = title?.takeIf(String::isNotBlank) ?: path.substringAfterLast('/').substringBeforeLast('.'),
                artist = artist,
                album = album,
                albumArtist = albumArtist ?: album?.let { artistsOf(artist).firstOrNull() },
                discNumber = discNumber,
                trackNumber = trackNumber,
                duration = duration,
                folder = folder,
            )

        fun LibraryTrack.isBy(artist: String): Boolean {
            val name = normalize(artist)
            return artistsOf(this.artist).any { normalize(it) == name }
        }

        fun artistsOf(credit: String?): List<String> =
            credit
                .orEmpty()
                .split(SEPARATORS)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinctBy(::normalize)

        /** Чей альбом: исполнитель альбома, без него — основной артист трека. */
        fun ownerOf(track: LibraryTrack): String? = track.albumArtist ?: artistsOf(track.artist).firstOrNull()

        /** Альбом трека — название и владелец, как их сравнивает ядро; `null` — не на альбоме. */
        fun albumIdentity(track: LibraryTrack): Pair<String, String>? =
            track.album?.let { normalize(it) to normalize(ownerOf(track).orEmpty()) }

        /** Как `text::normalize` ядра: без регистра, диакритики и знаков, слова — через пробел. */
        fun normalize(text: String): String =
            NaturalOrder
                .fold(text)
                .filter { it.isLetterOrDigit() || it.isWhitespace() }
                .split(' ')
                .filter(String::isNotEmpty)
                .joinToString(" ")
    }
}
