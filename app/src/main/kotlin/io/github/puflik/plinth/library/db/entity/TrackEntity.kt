package io.github.puflik.plinth.library.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.sort.SortKeys
import kotlin.time.Duration

/**
 * Строка трека в базе v0.1 (C3.1) — [LibraryTrack] и то, что нужно SQL.
 *
 * Упрощённая модель: не `Track/Version/Source` — та приходит в v0.2 вместе
 * с ядром на Rust. Таблиц альбомов и исполнителей нет: их собирают запросы
 * `GROUP BY` по трекам (`TrackDao`).
 *
 * Сверх тегов в строке лежит:
 * - [albumOwner] — чей альбом ([LibraryTrack.albumOwner]); по нему альбомы
 *   группируются, а экран альбома выбирает треки;
 * - ключи сортировки (`*_key`, [SortKeys]) — считаются при записи, SQL
 *   сортирует по ним обычным сравнением строк. Нет значения — нет и ключа;
 * - [missing] — файла больше нет: трек скрыт из всех списков, но строка
 *   остаётся и оживает, если файл вернётся.
 *
 * @property mediaStoreId `_ID` из `MediaStore` — [LibraryTrack.id], он же
 *   первичный ключ: по нему сканер заменяет строку при следующем обходе.
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index("title_key"),
        Index("artist_key", "artist"),
        Index("album_key", "album_owner_key"),
        Index("album", "album_owner"),
    ],
)
data class TrackEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    @ColumnInfo(name = "album_artist")
    val albumArtist: String?,
    @ColumnInfo(name = "album_owner")
    val albumOwner: String?,
    @ColumnInfo(name = "disc_number")
    val discNumber: Int?,
    @ColumnInfo(name = "track_number")
    val trackNumber: Int?,
    val duration: Duration,
    val folder: String,
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long,
    val missing: Boolean,
    @ColumnInfo(name = "title_key")
    val titleKey: String,
    @ColumnInfo(name = "artist_key")
    val artistKey: String?,
    @ColumnInfo(name = "album_key")
    val albumKey: String?,
    @ColumnInfo(name = "album_owner_key")
    val albumOwnerKey: String?,
) {
    fun toLibraryTrack(): LibraryTrack =
        LibraryTrack(
            id = mediaStoreId,
            uri = uri,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            discNumber = discNumber,
            trackNumber = trackNumber,
            duration = duration,
            folder = folder,
            modifiedAt = modifiedAt,
        )

    companion object {
        /** Строка для записи: трек виден, ключи посчитаны заново. */
        fun of(
            track: LibraryTrack,
            keys: SortKeys,
        ): TrackEntity =
            TrackEntity(
                mediaStoreId = track.id,
                uri = track.uri,
                title = track.title,
                artist = track.artist,
                album = track.album,
                albumArtist = track.albumArtist,
                albumOwner = track.albumOwner,
                discNumber = track.discNumber,
                trackNumber = track.trackNumber,
                duration = track.duration,
                folder = track.folder,
                modifiedAt = track.modifiedAt,
                missing = false,
                titleKey = keys.of(track.title),
                artistKey = track.artist?.let(keys::of),
                albumKey = track.album?.let(keys::of),
                albumOwnerKey = track.albumOwner?.let(keys::of),
            )
    }
}
