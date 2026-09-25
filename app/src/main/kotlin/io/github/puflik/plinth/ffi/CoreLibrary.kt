package io.github.puflik.plinth.ffi

/**
 * Чтение библиотеки из ядра (A3.1) — `api/library_api.rs`. Каталог в базе
 * ядра появится со сканером (D1); запросы экранов под замену Room — D3.
 */
class CoreLibrary internal constructor(
    private val core: PlinthCore,
) {
    /** Треки в порядке [sort]; [search] — по названию и исполнителю без учёта регистра и диакритики. */
    fun tracks(
        sort: CoreTrackSort = CoreTrackSort.TITLE,
        search: String? = null,
    ): List<CoreTrack> = core.call { rust -> rust.tracks(sort.toRust(), search).map { it.toApp() } }

    /** Треки альбома по дискам и номерам. */
    fun albumTracks(album: AlbumId): List<CoreTrack> =
        core.call { rust -> rust.albumTracks(album.value).map { it.toApp() } }

    /** Лайк, оценка и счётчики трека; не слушали и не оценивали — пустые. */
    fun userData(track: TrackId): TrackUserData = core.call { it.userData(track.value).toApp() }
}
