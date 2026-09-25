package io.github.puflik.plinth.ffi

/**
 * Чтение библиотеки из ядра (A3.1, D3) — `api/library_api.rs`. Видны только
 * треки, которые можно сыграть; названия — в естественном порядке и без
 * ведущего артикля.
 */
class CoreLibrary internal constructor(
    private val core: PlinthCore,
) {
    /**
     * Треки в порядке [sort]; [search] — каждое слово есть в названии,
     * исполнителе, альбоме или исполнителе альбома, без учёта регистра и
     * диакритики.
     */
    fun tracks(
        sort: CoreTrackSort = CoreTrackSort.TITLE,
        search: String? = null,
    ): List<CoreTrack> = core.call { rust -> rust.tracks(sort.toRust(), search).map { it.toApp() } }

    /** Треки альбома по дискам и номерам. */
    fun albumTracks(album: AlbumId): List<CoreTrack> =
        core.call { rust -> rust.albumTracks(album.value).map { it.toApp() } }

    /** Альбомы с видимыми треками. */
    fun albums(sort: CoreAlbumSort = CoreAlbumSort.TITLE): List<CoreAlbum> =
        core.call { rust -> rust.albums(sort.toRust()).map { it.toApp() } }

    /** Альбом по названию и исполнителю; регистр, диакритика и знаки не важны. */
    fun findAlbum(
        title: String,
        artist: String?,
    ): AlbumId? = core.call { it.findAlbum(title, artist)?.let(::AlbumId) }

    /** Исполнители видимых треков по имени. */
    fun artists(): List<CoreArtist> = core.call { rust -> rust.artists().map { it.toApp() } }

    /** Треки исполнителя [name]: альбомы по названию, внутри — диск и номер. */
    fun artistTracks(name: String): List<CoreTrack> = core.call { rust -> rust.artistTracks(name).map { it.toApp() } }

    /** Альбомы, где есть треки исполнителя [name]. */
    fun artistAlbums(name: String): List<CoreAlbum> = core.call { rust -> rust.artistAlbums(name).map { it.toApp() } }

    /** Лайк, оценка и счётчики трека; не слушали и не оценивали — пустые. */
    fun userData(track: TrackId): TrackUserData = core.call { it.userData(track.value).toApp() }

    /** Обложка файла [path]: встроенная, иначе `cover.jpg` рядом; `null` — нет. */
    fun artwork(path: String): CoreArtwork? = core.call { it.artwork(path)?.toApp() }

    /** Ключи сортировки названий — тот же порядок, что у списков ядра. */
    fun sortKeys(texts: List<String>): List<String> = core.call { it.sortKeys(texts) }
}
