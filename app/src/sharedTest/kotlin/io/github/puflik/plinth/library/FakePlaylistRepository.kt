package io.github.puflik.plinth.library

import io.github.puflik.plinth.ffi.Playlist
import io.github.puflik.plinth.ffi.PlaylistEntryId
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.model.PlaylistTrack
import io.github.puflik.plinth.library.sort.CodePointOrder
import io.github.puflik.plinth.library.sort.SortKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/**
 * Плейлисты в памяти (D4b) — для тестов экранов. Проходит
 * `PlaylistRepositoryContractTest`, как ядро на эмуляторе.
 *
 * Треки берёт из [library]: видимые — те, что она показывает; скрытые
 * ([FakeLibraryRepository.hide]) остаются записями на своих местах, как в ядре.
 * Равные по имени плейлисты стоят в порядке создания — в ядре так растут их
 * идентификаторы.
 */
class FakePlaylistRepository(
    private val library: FakeLibraryRepository = FakeLibraryRepository(),
    private val keys: SortKeys = SortKeys(),
) : PlaylistRepository {
    private data class Entry(
        val id: PlaylistEntryId,
        val track: TrackId,
    )

    private data class Stored(
        val playlist: Playlist,
        val entries: List<Entry> = emptyList(),
    )

    private val stored = MutableStateFlow<Map<PlaylistId, Stored>>(emptyMap())
    private var created = 0
    private var added = 0

    override fun playlists(): Flow<List<Playlist>> =
        stored.map { all ->
            all.values.map(Stored::playlist).sortedWith(compareBy(CodePointOrder) { keys.of(it.name) })
        }

    override fun tracks(playlist: PlaylistId): Flow<List<PlaylistTrack>> =
        combine(stored, library.tracks()) { all, present ->
            val shown = present.associateBy(LibraryTrack::id)
            all[playlist]?.entries.orEmpty().mapNotNull { entry ->
                shown[entry.track]?.let { PlaylistTrack(entry.id, it) }
            }
        }.distinctUntilChanged()

    override suspend fun create(name: String): PlaylistId {
        val id = PlaylistId("playlist-%04d".format(++created))
        stored.update { it + (id to Stored(Playlist(id, name, Instant.fromEpochMilliseconds(created.toLong())))) }
        return id
    }

    override suspend fun rename(
        playlist: PlaylistId,
        name: String,
    ) = edit(playlist) { it.copy(playlist = it.playlist.copy(name = name)) }

    override suspend fun delete(playlist: PlaylistId) = stored.update { it - playlist }

    override suspend fun add(
        playlist: PlaylistId,
        track: TrackId,
    ) {
        val entry = Entry(PlaylistEntryId("entry-%04d".format(++added)), track)
        edit(playlist) { it.copy(entries = it.entries + entry) }
    }

    // Как ядро: место считается среди всех записей, перевод — тот же PlaylistOrder.
    override suspend fun move(
        playlist: PlaylistId,
        entry: PlaylistEntryId,
        to: Int,
    ) {
        val visible = tracks(playlist).first().map(PlaylistTrack::entry)
        val all = stored.value[playlist]?.entries.orEmpty()
        val index = PlaylistOrder.indexAmongAll(visible, all.map(Entry::id), entry, to) ?: return
        val moved = all.first { it.id == entry }
        edit(playlist) { it.copy(entries = (it.entries - moved).toMutableList().apply { add(index, moved) }) }
    }

    override suspend fun remove(entry: PlaylistEntryId) =
        stored.update { all ->
            all.mapValues { (_, playlist) -> playlist.copy(entries = playlist.entries.filterNot { it.id == entry }) }
        }

    /** Меняет [playlist], если он есть; удалённый правка не воскрешает. */
    private fun edit(
        playlist: PlaylistId,
        change: (Stored) -> Stored,
    ) = stored.update { all -> all[playlist]?.let { all + (playlist to change(it)) } ?: all }
}
