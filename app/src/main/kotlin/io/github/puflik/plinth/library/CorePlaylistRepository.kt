package io.github.puflik.plinth.library

import io.github.puflik.plinth.ffi.Playlist
import io.github.puflik.plinth.ffi.PlaylistEntryId
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.ffi.PlaylistItem
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.model.PlaylistTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull

/**
 * Плейлисты поверх журнала ядра (D4b). Требования —
 * `PlaylistRepositoryContractTest`, тот же, что проходит `FakePlaylistRepository`.
 *
 * Списки перечитываются по сигналу [PlinthCore.userDataChanges] — его двигает
 * каждая запись в журнал, — треки плейлиста ещё и по
 * [PlinthCore.catalogChanges]: скан скрывает и возвращает файлы. Вызовы ядра
 * блокирующие и идут в [io]. Отказ ядра уже ушёл в `CoreErrors`; здесь он
 * только пишется в лог.
 */
class CorePlaylistRepository(
    private val core: PlinthCore,
    private val io: CoroutineDispatcher,
) : PlaylistRepository {
    override fun playlists(): Flow<List<Playlist>> = read(core.userDataChanges) { core.journal.playlists() }

    override fun tracks(playlist: PlaylistId): Flow<List<PlaylistTrack>> =
        read(combine(core.catalogChanges, core.userDataChanges, ::Pair)) { visible(playlist) }

    override suspend fun create(name: String): PlaylistId? =
        attempt(io, TAG, "playlist creation") { core.journal.createPlaylist(name).id }

    override suspend fun rename(
        playlist: PlaylistId,
        name: String,
    ) {
        attempt(io, TAG, "playlist rename") { core.journal.renamePlaylist(playlist, name) }
    }

    override suspend fun delete(playlist: PlaylistId) {
        attempt(io, TAG, "playlist deletion") { core.journal.deletePlaylist(playlist) }
    }

    override suspend fun add(
        playlist: PlaylistId,
        track: TrackId,
    ) {
        attempt(io, TAG, "playlist addition") { core.journal.addToPlaylist(playlist, track) }
    }

    // Ядро считает место среди всех записей, экран — среди видимых: PlaylistOrder переводит.
    override suspend fun move(
        playlist: PlaylistId,
        entry: PlaylistEntryId,
        to: Int,
    ) {
        attempt(io, TAG, "playlist move") {
            val visible = visible(playlist).map(PlaylistTrack::entry)
            val all = core.journal.playlistItems(playlist).map(PlaylistItem::id)
            PlaylistOrder
                .indexAmongAll(
                    visible,
                    all,
                    entry,
                    to,
                )?.let { core.journal.moveInPlaylist(playlist, entry, it) }
        }
    }

    override suspend fun remove(entry: PlaylistEntryId) {
        attempt(io, TAG, "playlist removal") { core.journal.removeFromPlaylist(entry) }
    }

    /** Видимые треки — те же, что покажет экран: строки без файла отсеиваются и здесь. */
    private fun visible(playlist: PlaylistId): List<PlaylistTrack> =
        core.library.playlistTracks(playlist).mapNotNull { row ->
            row.track.toLibraryTrack()?.let { PlaylistTrack(row.entry, it) }
        }

    private fun <T : Any> read(
        signal: Flow<Any>,
        query: () -> T,
    ): Flow<T> =
        signal
            .mapNotNull { quietly(TAG, "playlist read", query) }
            .distinctUntilChanged()
            .flowOn(io)

    private companion object {
        const val TAG = "Playlists"
    }
}
