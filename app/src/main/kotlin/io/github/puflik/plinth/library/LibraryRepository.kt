package io.github.puflik.plinth.library

import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.TrackSort
import kotlinx.coroutines.flow.Flow

/**
 * Фасад фонотеки (C3.3, D3b) — единственный путь экранов к библиотеке.
 *
 * Реализации — `CoreLibraryRepository` поверх ядра на Rust и, до D3c,
 * `RoomLibraryRepository` v0.1. Здесь нет ни ядра, ни Room, ни Android.
 * Требования к реализациям записаны в `LibraryRepositoryContractTest`.
 *
 * Чтение — потоками: список обновляется сам, когда скан что-то записал.
 * Треки пропавших файлов не видны ни в одном списке. Пишет в фонотеку скан,
 * а не экраны, — поэтому методов записи здесь нет.
 */
interface LibraryRepository {
    fun tracks(sort: TrackSort = TrackSort.TITLE): Flow<List<LibraryTrack>>

    fun albums(sort: AlbumSort = AlbumSort.TITLE): Flow<List<Album>>

    /**
     * Исполнители по имени, без артикля и в естественном порядке. Строка
     * исполнителя делится на артистов: у «Queen & David Bowie» их двое.
     */
    fun artists(): Flow<List<Artist>>

    /**
     * Треки, у которых каждое слово [query] есть в названии, исполнителе,
     * альбоме или исполнителе альбома — без учёта регистра, диакритики и
     * знаков; по названию, как [tracks]. Запрос без слов ничего не находит.
     */
    fun search(query: String): Flow<List<LibraryTrack>>

    /** Треки альбома по диску, затем по номеру; треки без номера — в конце диска. */
    fun albumTracks(album: Album): Flow<List<LibraryTrack>>

    /**
     * Треки исполнителя [artist] — одного из [artists] — в порядке [tracks]
     * по исполнителю: альбомы по названию, внутри — по диску и номеру, треки
     * без альбома — в конце.
     */
    fun artistTracks(artist: String): Flow<List<LibraryTrack>>

    /**
     * Альбомы, где есть треки [artist], в порядке [albums] по названию. Число
     * треков и обложка — всего альбома: сборник с одной его песней — целый сборник.
     */
    fun artistAlbums(artist: String): Flow<List<Album>>

    /**
     * Ключи сортировки [names] по порядку — для того, что экран собирает сам
     * (папки): в порядке ключей, сравнённых по кодовым точкам, названия стоят
     * так же, как в списках хранилища.
     */
    suspend fun sortKeys(names: List<String>): List<String>
}
