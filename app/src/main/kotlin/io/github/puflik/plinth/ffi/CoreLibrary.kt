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
     * исполнителе, альбоме или исполнителе альбома, без учёта регистра,
     * диакритики и знаков. Запрос без слов ничего не находит.
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

    /** «Любимое»: видимые треки с лайком, по названию. */
    fun likedTracks(): List<CoreTrack> = core.call { rust -> rust.likedTracks().map { it.toApp() } }

    /** «Недавнее»: по последнему засчитанному прослушиванию, новые первыми, не больше [limit]. */
    fun recentTracks(limit: Int): List<CoreTrack> {
        require(limit >= 0) { "limit: $limit" }
        return core.call { rust -> rust.recentTracks(limit.toUInt()).map { it.toApp() } }
    }

    /**
     * Видимые треки плейлиста в его порядке, каждый — со своей записью.
     * Пропавший файл скрыт, а запись его — нет: индекс строки здесь не индекс
     * для [CoreJournal.moveInPlaylist], тот считает среди всех записей.
     */
    fun playlistTracks(playlist: PlaylistId): List<CorePlaylistTrack> =
        core.call { rust -> rust.playlistTracks(playlist.value).map { it.toApp() } }

    /** Трек библиотеки, который играет из файла [path]; файла в библиотеке нет — `null`. */
    fun trackAt(path: String): TrackId? = core.call { it.trackAt(path)?.let(::TrackId) }

    /** Лайк, оценка и счётчики трека; не слушали и не оценивали — пустые. */
    fun userData(track: TrackId): TrackUserData = core.call { it.userData(track.value).toApp() }

    /**
     * Обложка файла [path]: встроенная, иначе `cover.jpg` рядом; `null` — нет.
     * Файл не прочитался — [CoreFailure], но не в `CoreErrors`: файл мог
     * пропасть после скана, и это не сбой ядра.
     */
    fun artwork(path: String): CoreArtwork? = core.quietCall { it.artwork(path)?.toApp() }

    /** Ключи сортировки названий — тот же порядок, что у списков ядра. */
    fun sortKeys(texts: List<String>): List<String> = core.call { it.sortKeys(texts) }
}
