package io.github.puflik.plinth.library

import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.CodePointOrder
import io.github.puflik.plinth.library.sort.SortKeys
import io.github.puflik.plinth.library.sort.TrackSort

/**
 * Порядки `FakeLibraryRepository` (C3.3, D4b) — те же, что у запросов ядра:
 * названия по ключам [keys], сравнённым по кодовым точкам ([CodePointOrder]),
 * как в SQLite; пустое — в конце. Вынесены из фейка, чтобы он оставался
 * обозримым.
 *
 * @property ownerOf чей альбом у трека — по нему альбомы различаются.
 */
internal class FakeLibraryOrder(
    private val keys: SortKeys,
    private val ownerOf: (LibraryTrack) -> String?,
) {
    /** Порядок [sort]; у «Часто слушаю» — только равные по числу прослушиваний, его сравнивает фейк. */
    fun tracks(sort: TrackSort): Comparator<LibraryTrack> =
        when (sort) {
            TrackSort.TITLE -> key(LibraryTrack::title).thenByKey(LibraryTrack::artist)
            TrackSort.ARTIST -> key(LibraryTrack::artist).then(byAlbum()).then(inAlbum())
            TrackSort.ALBUM -> byAlbum().then(inAlbum())
            TrackSort.MOST_PLAYED -> key(LibraryTrack::title)
        }

    fun albums(sort: AlbumSort): Comparator<Album> =
        when (sort) {
            AlbumSort.TITLE -> key(Album::title).thenByKey(Album::artist)
            AlbumSort.ARTIST -> key(Album::artist).thenByKey(Album::title)
        }

    /** Альбомы по названию и владельцу; треки без альбома — в конце. */
    fun byAlbum(): Comparator<LibraryTrack> =
        key(LibraryTrack::album).then(key { track: LibraryTrack -> track.album?.let { ownerOf(track) } })

    /** Порядок треков альбома: по диску и номеру, равные — по названию. */
    fun inAlbum(): Comparator<LibraryTrack> = discOrder().thenByKey(LibraryTrack::title)

    /** Порядок по ключу названия; без названия — в конце. */
    fun <T> key(text: (T) -> String?): Comparator<T> =
        compareBy(nullsLast(CodePointOrder)) { item: T -> text(item)?.let(keys::of) }

    /** Диск без номера — первым, трек без номера — последним на своём диске. */
    private fun discOrder(): Comparator<LibraryTrack> =
        compareBy<LibraryTrack, Int?>(nullsFirst(naturalOrder())) { it.discNumber }
            .thenBy(nullsLast(naturalOrder())) { it.trackNumber }

    private fun <T> Comparator<T>.thenByKey(text: (T) -> String?): Comparator<T> = then(key(text))
}
