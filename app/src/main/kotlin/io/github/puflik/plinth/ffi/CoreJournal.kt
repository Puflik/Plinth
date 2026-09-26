package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.ffi.generated.Core

/**
 * Действия пользователя (A3.1) — `api/journal_api.rs`. Каждое ложится в
 * журнал ядра и оттуда — в базу (docs/adr/0007-journal-as-source-of-truth.md):
 * мимо журнала пользовательское не пишется.
 */
class CoreJournal internal constructor(
    private val core: PlinthCore,
) {
    fun like(track: TrackId) = write { it.like(track.value) }

    fun unlike(track: TrackId) = write { it.unlike(track.value) }

    /** Оценка звёздами от 1 до 5; `null` — снять. */
    fun rate(
        track: TrackId,
        stars: Int?,
    ) {
        require(stars == null || stars in STARS) { "rating is 1..5 stars: $stars" }
        write { it.rate(track.value, stars?.toUByte()) }
    }

    fun createPlaylist(name: String): Playlist = write { it.createPlaylist(name).toApp() }

    fun renamePlaylist(
        playlist: PlaylistId,
        name: String,
    ) = write { it.renamePlaylist(playlist.value, name) }

    /** Удаляет плейлист вместе с записями. */
    fun deletePlaylist(playlist: PlaylistId) = write { it.deletePlaylist(playlist.value) }

    /** Плейлисты по имени. */
    fun playlists(): List<Playlist> = core.call { rust -> rust.playlists().map { it.toApp() } }

    /** Записи плейлиста в его порядке. */
    fun playlistItems(playlist: PlaylistId): List<PlaylistItem> =
        core.call { rust -> rust.playlistItems(playlist.value).map { it.toApp() } }

    /** Добавляет [track] на место [index]; `null` или за концом — в конец. Трек может стоять дважды. */
    fun addToPlaylist(
        playlist: PlaylistId,
        track: TrackId,
        index: Int? = null,
    ): PlaylistEntryId {
        require(index == null || index >= 0) { "index: $index" }
        return write { PlaylistEntryId(it.addToPlaylist(playlist.value, track.value, index?.toUInt())) }
    }

    /** Переставляет запись [entry] так, что она оказывается на месте [index]. */
    fun moveInPlaylist(
        playlist: PlaylistId,
        entry: PlaylistEntryId,
        index: Int,
    ) {
        require(index >= 0) { "index: $index" }
        write { it.moveInPlaylist(playlist.value, entry.value, index.toUInt()) }
    }

    fun removeFromPlaylist(entry: PlaylistEntryId) = write { it.removeFromPlaylist(entry.value) }

    /** Записывает прослушивание; засчитать ли его в счётчик, решает ядро. */
    fun recordPlay(play: NewPlay): PlayEventId = write { PlayEventId(it.recordPlay(play.toRust())) }

    /** Последние [limit] прослушиваний всей библиотеки, новые первыми. */
    fun recentPlays(limit: Int): List<PlayEvent> {
        require(limit >= 0) { "limit: $limit" }
        return core.call { rust -> rust.recentPlays(limit.toUInt()).map { it.toApp() } }
    }

    fun versionPreference(): VersionPreference = core.call { it.syncedSettings().versionPreference.toApp() }

    fun setVersionPreference(preference: VersionPreference) = write { it.setVersionPreference(preference.toRust()) }

    /** Запись в журнал: после неё списки с пользовательским перечитают ядро. */
    private inline fun <T> write(crossinline block: (Core) -> T): T =
        core.call { block(it) }.also { core.userDataChanged() }

    private companion object {
        val STARS = 1..5
    }
}
