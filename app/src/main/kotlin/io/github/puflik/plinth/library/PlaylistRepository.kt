package io.github.puflik.plinth.library

import io.github.puflik.plinth.ffi.Playlist
import io.github.puflik.plinth.ffi.PlaylistEntryId
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.model.PlaylistTrack
import kotlinx.coroutines.flow.Flow

/**
 * Свои плейлисты (D4b): список, треки плейлиста и правка.
 *
 * Пишется только в журнал ядра, мимо журнала — никогда
 * (docs/adr/0007-journal-as-source-of-truth.md). Реализация —
 * `CorePlaylistRepository`; требования — `PlaylistRepositoryContractTest`,
 * тот же, что проходит `FakePlaylistRepository`.
 *
 * Треки пропавших файлов скрыты, но их записи остаются на своих местах:
 * файл вернётся — трек встанет туда же. Индексы здесь — среди видимых
 * треков, как их показывает экран.
 *
 * Отказ ядра фасад сам отдаёт в `CoreErrors` и не бросает: правка, которая
 * не записалась, не должна ронять экран.
 */
interface PlaylistRepository {
    /** Плейлисты по имени, как списки экранов; поток меняется с каждой правкой. */
    fun playlists(): Flow<List<Playlist>>

    /** Видимые треки [playlist] в его порядке; плейлиста нет — пусто. */
    fun tracks(playlist: PlaylistId): Flow<List<PlaylistTrack>>

    /** Новый пустой плейлист; не создался — `null`. */
    suspend fun create(name: String): PlaylistId?

    suspend fun rename(
        playlist: PlaylistId,
        name: String,
    )

    /** Удаляет плейлист вместе с записями. */
    suspend fun delete(playlist: PlaylistId)

    /** Добавляет [track] в конец [playlist]; один трек может стоять дважды. */
    suspend fun add(
        playlist: PlaylistId,
        track: TrackId,
    )

    /**
     * Переставляет запись [entry] так, что среди видимых треков [playlist]
     * она встаёт на место [to] — как в [tracks]. Скрытые записи остаются
     * между прежними соседями.
     */
    suspend fun move(
        playlist: PlaylistId,
        entry: PlaylistEntryId,
        to: Int,
    )

    /** Убирает запись [entry]; тот же трек на других местах остаётся. */
    suspend fun remove(entry: PlaylistEntryId)
}
