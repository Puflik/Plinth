package io.github.puflik.plinth.library

import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.CodePointOrder
import io.github.puflik.plinth.library.sort.SortKeys
import io.github.puflik.plinth.library.sort.TrackSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Хранилище фонотеки в памяти для тестов (C3.3).
 *
 * Сортирует и группирует в Kotlin то, что Room делает запросами; совпадение
 * гарантирует общий контракт `LibraryRepositoryContractTest`. Строки
 * сравниваются по кодовым точкам ([CodePointOrder]), как в SQLite.
 */
class FakeLibraryRepository(
    private val keys: SortKeys = SortKeys(),
) : LibraryRepository {
    private data class Entry(
        val track: LibraryTrack,
        val missing: Boolean,
    )

    private val entries = MutableStateFlow<Map<Long, Entry>>(emptyMap())

    private val present: Flow<List<LibraryTrack>> =
        entries.map { all -> all.values.filterNot(Entry::missing).map(Entry::track) }

    override fun tracks(sort: TrackSort): Flow<List<LibraryTrack>> =
        present.map { tracks -> tracks.sortedWith(trackOrder(sort)) }

    override fun albums(sort: AlbumSort): Flow<List<Album>> =
        present.map { tracks ->
            tracks
                .filter { it.album != null }
                .groupBy { checkNotNull(it.album) to it.albumOwner }
                .map { (album, tracksOfAlbum) -> Album(album.first, album.second, tracksOfAlbum.size) }
                .sortedWith(albumOrder(sort))
        }

    override fun artists(): Flow<List<Artist>> =
        present.map { tracks ->
            tracks
                .filter { it.artist != null }
                .groupBy { checkNotNull(it.artist) }
                .map { (name, tracksOfArtist) ->
                    Artist(name, tracksOfArtist.mapNotNull(LibraryTrack::album).distinct().size, tracksOfArtist.size)
                }.sortedWith(keyOrder(Artist::name).thenBy(CodePointOrder, Artist::name))
        }

    override fun albumTracks(album: Album): Flow<List<LibraryTrack>> =
        present.map { tracks ->
            tracks
                .filter { it.album == album.title && it.albumOwner == album.artist }
                .sortedWith(discOrder().thenByKey(LibraryTrack::title).thenBy(LibraryTrack::id))
        }

    override suspend fun knownVersions(): Map<Long, Long> =
        entries.value.values
            .filterNot(Entry::missing)
            .associate { it.track.id to it.track.modifiedAt }

    override suspend fun upsert(tracks: Collection<LibraryTrack>) {
        entries.update { all -> all + tracks.associate { it.id to Entry(it, missing = false) } }
    }

    override suspend fun markMissing(ids: Collection<Long>) {
        entries.update { all -> all.mapValues { (id, entry) -> if (id in ids) entry.copy(missing = true) else entry } }
    }

    private fun trackOrder(sort: TrackSort): Comparator<LibraryTrack> =
        when (sort) {
            TrackSort.TITLE ->
                keyOrder(LibraryTrack::title).thenByKey(LibraryTrack::artist)
            TrackSort.ARTIST ->
                keyOrder(LibraryTrack::artist).thenByKey(LibraryTrack::album).then(discOrder())
            TrackSort.ALBUM ->
                keyOrder(LibraryTrack::album).thenByKey(LibraryTrack::albumOwner).then(discOrder())
        }.thenByKey(LibraryTrack::title).thenBy(LibraryTrack::id)

    /** Равные по ключам — по точному названию, затем по владельцу: у альбома нет `id`. */
    private fun albumOrder(sort: AlbumSort): Comparator<Album> =
        when (sort) {
            AlbumSort.TITLE -> keyOrder(Album::title).thenByKey(Album::artist)
            AlbumSort.ARTIST -> keyOrder(Album::artist).thenByKey(Album::title)
        }.thenBy(CodePointOrder, Album::title).thenBy(nullsLast(CodePointOrder), Album::artist)

    /** Диск без номера — первым, трек без номера — последним на своём диске. */
    private fun discOrder(): Comparator<LibraryTrack> =
        compareBy<LibraryTrack, Int?>(nullsFirst(naturalOrder())) { it.discNumber }
            .thenBy(nullsLast(naturalOrder())) { it.trackNumber }

    /** Порядок по ключу названия; без названия — в конце. */
    private fun <T> keyOrder(text: (T) -> String?): Comparator<T> =
        compareBy(nullsLast(CodePointOrder)) { item: T -> text(item)?.let(keys::of) }

    private fun <T> Comparator<T>.thenByKey(text: (T) -> String?): Comparator<T> = then(keyOrder(text))
}
