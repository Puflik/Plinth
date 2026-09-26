package io.github.puflik.plinth.library

import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.sort.AlbumSort
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
 * Строки сравниваются по кодовым точкам, как в SQLite (`FakeLibraryOrder`).
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
    /** [plays] — засчитанные прослушивания, [lastPlayed] — номер последнего по порядку всех. */
    private data class Entry(
        val track: LibraryTrack,
        val missing: Boolean,
        val plays: Int = 0,
        val lastPlayed: Int? = null,
    )

    private val entries = MutableStateFlow<Map<TrackId, Entry>>(emptyMap())
    private val order = FakeLibraryOrder(keys) { ownerOf(it) }
    private var added = 0
    private var played = 0

    private val presentEntries: Flow<List<Entry>> = entries.map { all -> all.values.filterNot(Entry::missing) }

    private val present: Flow<List<LibraryTrack>> = presentEntries.map { all -> all.map(Entry::track) }

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

    /** Ставит лайк трекам файлов [paths]. */
    fun like(paths: Collection<String>) = updateAt(paths) { it.copy(track = it.track.copy(liked = true)) }

    /** Файл [path] дослушали [times] раз — позже всех прежних прослушиваний. */
    fun play(
        path: String,
        times: Int,
    ) = updateAt(listOf(path)) { it.copy(plays = it.plays + times, lastPlayed = ++played) }

    private fun updateAt(
        paths: Collection<String>,
        change: (Entry) -> Entry,
    ) = entries.update { all -> all.mapValues { (_, entry) -> if (entry.track.uri in paths) change(entry) else entry } }

    override fun tracks(sort: TrackSort): Flow<List<LibraryTrack>> =
        if (sort == TrackSort.MOST_PLAYED) {
            presentEntries.map { all ->
                all
                    .sortedWith(compareByDescending(Entry::plays).then(compareBy(order.tracks(sort), Entry::track)))
                    .map(Entry::track)
            }
        } else {
            present.map { tracks -> tracks.sortedWith(order.tracks(sort)) }
        }

    override fun likedTracks(): Flow<List<LibraryTrack>> =
        present.map { tracks -> tracks.filter(LibraryTrack::liked).sortedWith(order.tracks(TrackSort.TITLE)) }

    override fun recentTracks(): Flow<List<LibraryTrack>> =
        presentEntries.map { all ->
            all
                .filter { it.lastPlayed != null }
                .sortedByDescending(Entry::lastPlayed)
                .take(LibraryRepository.RECENT_LIMIT)
                .map(Entry::track)
        }

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
                }.sortedWith(order.key(Artist::name))
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
        return present.map { tracks -> tracks.filter { albumIdentity(it) == wanted }.sortedWith(order.inAlbum()) }
    }

    override fun artistTracks(artist: String): Flow<List<LibraryTrack>> =
        present.map { tracks -> tracks.filter { it.isBy(artist) }.sortedWith(order.byAlbum().then(order.inAlbum())) }

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
                val cover = tracksOfAlbum.minWith(order.inAlbum()).uri
                Album(checkNotNull(first.album), ownerOf(first), tracksOfAlbum.size, coverTrackUri = cover)
            }.sortedWith(order.albums(sort))

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
