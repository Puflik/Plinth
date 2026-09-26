package io.github.puflik.plinth.library

import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.ffi.CoreAlbum
import io.github.puflik.plinth.ffi.CoreAlbumSort
import io.github.puflik.plinth.ffi.CoreFailure
import io.github.puflik.plinth.ffi.CoreLibrary
import io.github.puflik.plinth.ffi.CoreTrack
import io.github.puflik.plinth.ffi.CoreTrackSort
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.TrackSort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext

/**
 * Фонотека поверх ядра на Rust (D3b). Порядок, группировку и поиск считает
 * ядро (`api/library_api.rs`); здесь — выбор вызова и перевод строк в модель.
 * Требования — `LibraryRepositoryContractTest`, тот же, что проходит
 * `FakeLibraryRepository`.
 *
 * Потоки перечитывают ядро по сигналу [PlinthCore.catalogChanges] — после
 * каждой пачки скана и в его конце — и по [PlinthCore.userDataChanges]: лайк
 * меняет строку трека. Вызовы ядра блокирующие и идут в [io].
 * Отказ ядра фасад сам отдаёт в `CoreErrors`; список при этом остаётся
 * прежним, а следующее изменение каталога прочитает его снова.
 */
class CoreLibraryRepository(
    private val core: PlinthCore,
    private val io: CoroutineDispatcher,
) : LibraryRepository {
    override fun tracks(sort: TrackSort): Flow<List<LibraryTrack>> = read { tracks(sort.toCore()).toLibraryTracks() }

    override fun albums(sort: AlbumSort): Flow<List<Album>> = read { albums(sort.toCore()).map(CoreAlbum::toAlbum) }

    override fun artists(): Flow<List<Artist>> =
        read { artists().map { Artist(it.name, albumCount = it.albumCount, trackCount = it.trackCount) } }

    // Запрос без слов ядро и само не ищет; пустую строку не стоит и отправлять.
    override fun search(query: String): Flow<List<LibraryTrack>> =
        if (query.isBlank()) {
            flowOf(emptyList())
        } else {
            read { tracks(CoreTrackSort.TITLE, search = query).toLibraryTracks() }
        }

    // Экран альбома открывается по названию и исполнителю — ID у Album нет.
    override fun albumTracks(album: Album): Flow<List<LibraryTrack>> =
        read { findAlbum(album.title, album.artist)?.let { albumTracks(it).toLibraryTracks() }.orEmpty() }

    override fun artistTracks(artist: String): Flow<List<LibraryTrack>> =
        read { artistTracks(artist).toLibraryTracks() }

    override fun artistAlbums(artist: String): Flow<List<Album>> = read { artistAlbums(artist).map(CoreAlbum::toAlbum) }

    /** Ядро не ответило — имена сами себе ключи: папки встанут по кодовым точкам, но встанут. */
    override suspend fun sortKeys(names: List<String>): List<String> =
        withContext(io) {
            try {
                core.library.sortKeys(names)
            } catch (failure: CoreFailure) {
                AppLog.w(TAG, "sort keys are unavailable", failure)
                names
            }
        }

    private fun <T : Any> read(query: CoreLibrary.() -> T): Flow<T> =
        combine(core.catalogChanges, core.userDataChanges) { catalog, user -> catalog to user }
            .mapNotNull {
                try {
                    core.library.query()
                } catch (failure: CoreFailure) {
                    AppLog.w(TAG, "library is unreadable", failure)
                    null
                }
            }.distinctUntilChanged()
            .flowOn(io)

    private companion object {
        const val TAG = "Library"
    }
}

private fun TrackSort.toCore() =
    when (this) {
        TrackSort.TITLE -> CoreTrackSort.TITLE
        TrackSort.ARTIST -> CoreTrackSort.ARTIST
        TrackSort.ALBUM -> CoreTrackSort.ALBUM
    }

private fun AlbumSort.toCore() =
    when (this) {
        AlbumSort.TITLE -> CoreAlbumSort.TITLE
        AlbumSort.ARTIST -> CoreAlbumSort.ARTIST
    }

private fun CoreAlbum.toAlbum() = Album(title, artistCredit, trackCount, coverTrackUri = coverUri)

/** Играть можно только файл: строки без пути (сетевые источники — эпик E) не показываются. */
private fun List<CoreTrack>.toLibraryTracks(): List<LibraryTrack> =
    mapNotNull { track ->
        val path = track.uri?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        LibraryTrack(
            id = track.id,
            uri = path,
            title = track.title.ifBlank { path.substringAfterLast('/') },
            artist = track.artistCredit.ifEmpty { null },
            album = track.albumTitle,
            albumArtist = track.albumArtist,
            discNumber = track.disc?.takeIf { it > 0 },
            trackNumber = track.number?.takeIf { it > 0 },
            duration = track.duration,
            folder = track.folder.orEmpty(),
            liked = track.liked,
        )
    }
