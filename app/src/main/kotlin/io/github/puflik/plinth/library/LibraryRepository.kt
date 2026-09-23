package io.github.puflik.plinth.library

import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.TrackSort
import kotlinx.coroutines.flow.Flow

/**
 * Фасад фонотеки (C3.3) — единственный путь экранов к библиотеке.
 *
 * Всё остальное в эпике C временное: в v0.2 Room и сканер заменит ядро на
 * Rust, а этот интерфейс останется. Поэтому здесь нет ни Room, ни Android.
 * Требования к реализациям записаны в `LibraryRepositoryContractTest`.
 *
 * Чтение — потоками: список обновляется сам, когда сканер что-то записал.
 * Треки, [помеченные пропавшими][markMissing], не видны ни в одном списке.
 */
interface LibraryRepository {
    fun tracks(sort: TrackSort = TrackSort.TITLE): Flow<List<LibraryTrack>>

    fun albums(sort: AlbumSort = AlbumSort.TITLE): Flow<List<Album>>

    /** Исполнители по имени, без артикля и в естественном порядке. */
    fun artists(): Flow<List<Artist>>

    /** Треки альбома по диску, затем по номеру; треки без номера — в конце диска. */
    fun albumTracks(album: Album): Flow<List<LibraryTrack>>

    /** `id` → `modifiedAt` всех видимых треков: по ним сканер решает, что перечитать. */
    suspend fun knownVersions(): Map<Long, Long>

    /** Добавляет треки или заменяет их по `id`; пропавший трек снова становится видимым. */
    suspend fun upsert(tracks: Collection<LibraryTrack>)

    /** Скрывает треки, файлов которых больше нет. */
    suspend fun markMissing(ids: Collection<Long>)
}
