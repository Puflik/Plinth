package io.github.puflik.plinth.library

import io.github.puflik.plinth.library.db.dao.TrackDao
import io.github.puflik.plinth.library.db.entity.TrackEntity
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.SearchQuery
import io.github.puflik.plinth.library.sort.SortKeys
import io.github.puflik.plinth.library.sort.TrackSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Фонотека на Room (C3.3) — реализация фасада для v0.1.
 *
 * Сортирует и группирует SQLite; здесь только выбор запроса и перевод строк
 * в модель. Ключи сортировки считаются при записи — поэтому [keys] нужны
 * только [upsert]. Требования — `LibraryRepositoryContractTest`, тот же, что
 * проходит `FakeLibraryRepository`.
 */
class RoomLibraryRepository(
    private val dao: TrackDao,
    private val keys: SortKeys,
) : LibraryRepository {
    override fun tracks(sort: TrackSort): Flow<List<LibraryTrack>> =
        when (sort) {
            TrackSort.TITLE -> dao.tracksByTitle()
            TrackSort.ARTIST -> dao.tracksByArtist()
            TrackSort.ALBUM -> dao.tracksByAlbum()
        }.toLibraryTracks()

    override fun albums(sort: AlbumSort): Flow<List<Album>> =
        when (sort) {
            AlbumSort.TITLE -> dao.albumsByTitle()
            AlbumSort.ARTIST -> dao.albumsByArtist()
        }

    override fun artists(): Flow<List<Artist>> = dao.artists()

    // Поиск по нескольким тысячам треков в Kotlin занимает миллисекунды, а
    // свёртку регистра и надстрочных знаков SQLite без своих функций не умеет.
    override fun search(query: String): Flow<List<LibraryTrack>> {
        val search = SearchQuery(query)
        if (search.isBlank) return flowOf(emptyList())
        return tracks(TrackSort.TITLE).map { tracks ->
            tracks.filter { search.matches(it.title, it.artist, it.album, it.albumArtist) }
        }
    }

    override fun albumTracks(album: Album): Flow<List<LibraryTrack>> =
        dao.albumTracks(album.title, album.artist).toLibraryTracks()

    override fun artistTracks(artist: String): Flow<List<LibraryTrack>> = dao.artistTracks(artist).toLibraryTracks()

    override fun artistAlbums(artist: String): Flow<List<Album>> = dao.artistAlbums(artist)

    override suspend fun knownVersions(): Map<Long, Long> =
        dao.knownVersions().associate { it.mediaStoreId to it.modifiedAt }

    override suspend fun upsert(tracks: Collection<LibraryTrack>) {
        dao.upsert(tracks.map { TrackEntity.of(it, keys) })
    }

    override suspend fun markMissing(ids: Collection<Long>) {
        dao.markMissing(ids)
    }

    private fun Flow<List<TrackEntity>>.toLibraryTracks(): Flow<List<LibraryTrack>> =
        map { rows -> rows.map(TrackEntity::toLibraryTrack) }
}
