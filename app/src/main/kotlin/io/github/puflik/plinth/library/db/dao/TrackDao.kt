package io.github.puflik.plinth.library.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.puflik.plinth.library.db.entity.TrackEntity
import io.github.puflik.plinth.library.model.Album
import io.github.puflik.plinth.library.model.Artist
import kotlinx.coroutines.flow.Flow

/**
 * Треки внутри альбома: по диску, затем по номеру (C3.2).
 *
 * `NULL` в SQLite меньше любого значения, поэтому диск без номера идёт
 * первым сам. Трек без номера — последним на диске: `track_number IS NULL`
 * отправляет его в конец. Дальше — ключ названия, равные — по `id`.
 */
private const val IN_ALBUM_ORDER = "disc_number, track_number IS NULL, track_number, title_key, media_store_id"

/**
 * Обложка альбома — у его первого трека (E2): подзапрос по тем же названию и
 * владельцу, что у группы, в порядке [IN_ALBUM_ORDER]. Колонки порядка без
 * таблицы относятся к ближайшей — к `cover`; индекс `(album, album_owner)`
 * сводит подзапрос к нескольким строкам.
 */
private const val COVER_TRACK_URI =
    "(SELECT cover.uri FROM tracks AS cover " +
        "WHERE cover.missing = 0 AND cover.album = tracks.album AND cover.album_owner IS tracks.album_owner " +
        "ORDER BY " + IN_ALBUM_ORDER + " LIMIT 1) AS coverTrackUri"

/**
 * Запросы к трекам (C3.1, C3.2). Альбомы и исполнители — тоже здесь:
 * своих таблиц у них нет, их собирает `GROUP BY` по трекам.
 *
 * Порядок и группировка повторяют `LibraryRepositoryContractTest` один в
 * один. Названия сравниваются ключами `*_key`; пустой ключ — в конце
 * (`x IS NULL, x`: `NULLS LAST` в SQLite только с 3.30, а на Android 8 — 3.18).
 * Скрытые треки (`missing = 1`) не видны ни одному запросу.
 */
@Dao
abstract class TrackDao {
    @Query(
        "SELECT * FROM tracks WHERE missing = 0 " +
            "ORDER BY title_key, artist_key IS NULL, artist_key, media_store_id",
    )
    abstract fun tracksByTitle(): Flow<List<TrackEntity>>

    @Query(
        "SELECT * FROM tracks WHERE missing = 0 " +
            "ORDER BY artist_key IS NULL, artist_key, album_key IS NULL, album_key, " + IN_ALBUM_ORDER,
    )
    abstract fun tracksByArtist(): Flow<List<TrackEntity>>

    @Query(
        "SELECT * FROM tracks WHERE missing = 0 " +
            "ORDER BY album_key IS NULL, album_key, album_owner_key IS NULL, album_owner_key, " + IN_ALBUM_ORDER,
    )
    abstract fun tracksByAlbum(): Flow<List<TrackEntity>>

    /**
     * Альбом — точное название и владелец. Ключи в группировке не меняют
     * групп — они функция названия, — но избавляют сортировку от голых колонок.
     */
    @Query(
        "SELECT album AS title, album_owner AS artist, COUNT(*) AS trackCount, " + COVER_TRACK_URI + " FROM tracks " +
            "WHERE missing = 0 AND album IS NOT NULL " +
            "GROUP BY album_key, album_owner_key, album, album_owner " +
            "ORDER BY album_key, album_owner_key IS NULL, album_owner_key, album, album_owner",
    )
    abstract fun albumsByTitle(): Flow<List<Album>>

    @Query(
        "SELECT album AS title, album_owner AS artist, COUNT(*) AS trackCount, " + COVER_TRACK_URI + " FROM tracks " +
            "WHERE missing = 0 AND album IS NOT NULL " +
            "GROUP BY album_key, album_owner_key, album, album_owner " +
            "ORDER BY album_owner_key IS NULL, album_owner_key, album_key, album, album_owner",
    )
    abstract fun albumsByArtist(): Flow<List<Album>>

    /** Треки исполнителя — как [tracksByArtist], только его: одно имя, порядок тот же. */
    @Query(
        "SELECT * FROM tracks WHERE missing = 0 AND artist = :artist " +
            "ORDER BY album_key IS NULL, album_key, " + IN_ALBUM_ORDER,
    )
    abstract fun artistTracks(artist: String): Flow<List<TrackEntity>>

    /**
     * Альбомы, где есть треки исполнителя, — как [albumsByTitle], но группы без
     * его треков отсекает `HAVING`: сравнение даёт 1, 0 или `NULL`, а `SUM`
     * пропускает `NULL`. Число треков и обложка — всей группы.
     */
    @Query(
        "SELECT album AS title, album_owner AS artist, COUNT(*) AS trackCount, " + COVER_TRACK_URI + " FROM tracks " +
            "WHERE missing = 0 AND album IS NOT NULL " +
            "GROUP BY album_key, album_owner_key, album, album_owner " +
            "HAVING SUM(artist = :artist) > 0 " +
            "ORDER BY album_key, album_owner_key IS NULL, album_owner_key, album, album_owner",
    )
    abstract fun artistAlbums(artist: String): Flow<List<Album>>

    /** Исполнитель — тег `artist` трека; альбомы считаются по названию, сборники тоже. */
    @Query(
        "SELECT artist AS name, COUNT(DISTINCT album) AS albumCount, COUNT(*) AS trackCount FROM tracks " +
            "WHERE missing = 0 AND artist IS NOT NULL " +
            "GROUP BY artist_key, artist " +
            "ORDER BY artist_key, artist",
    )
    abstract fun artists(): Flow<List<Artist>>

    /** Владелец сравнивается через `IS`: у альбома без исполнителей он `NULL`, а `NULL = NULL` ложно. */
    @Query(
        "SELECT * FROM tracks WHERE missing = 0 AND album = :title AND album_owner IS :owner " +
            "ORDER BY " + IN_ALBUM_ORDER,
    )
    abstract fun albumTracks(
        title: String,
        owner: String?,
    ): Flow<List<TrackEntity>>

    @Query("SELECT media_store_id, modified_at FROM tracks WHERE missing = 0")
    abstract suspend fun knownVersions(): List<TrackVersion>

    /** Заменяет строку с тем же `media_store_id` целиком — и снимает с неё `missing`. */
    @Upsert
    abstract suspend fun upsert(tracks: List<TrackEntity>)

    /**
     * Скрывает треки. Список режется на порции: больше [MAX_BOUND_IDS]
     * параметров одним запросом SQLite до 3.32 (Android 8–11) не примет, а
     * сканер помечает пропавшей целую удалённую папку разом.
     */
    @Transaction
    open suspend fun markMissing(ids: Collection<Long>) {
        ids.chunked(MAX_BOUND_IDS).forEach { markMissingAtOnce(it) }
    }

    @Query("UPDATE tracks SET missing = 1 WHERE media_store_id IN (:ids)")
    protected abstract suspend fun markMissingAtOnce(ids: List<Long>)

    private companion object {
        /** `SQLITE_MAX_VARIABLE_NUMBER` до SQLite 3.32. */
        const val MAX_BOUND_IDS = 999
    }
}

/** Версия файла трека: по ней сканер решает, что перечитать. */
data class TrackVersion(
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long,
)
