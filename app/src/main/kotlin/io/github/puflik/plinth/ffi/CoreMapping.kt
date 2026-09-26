package io.github.puflik.plinth.ffi

import kotlin.time.Instant
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration
import io.github.puflik.plinth.ffi.generated.AlbumRow as RustAlbumRow
import io.github.puflik.plinth.ffi.generated.AlbumSort as RustAlbumSort
import io.github.puflik.plinth.ffi.generated.ArtistRow as RustArtistRow
import io.github.puflik.plinth.ffi.generated.Artwork as RustArtwork
import io.github.puflik.plinth.ffi.generated.NewPlay as RustNewPlay
import io.github.puflik.plinth.ffi.generated.OutputDevice as RustOutputDevice
import io.github.puflik.plinth.ffi.generated.PlayEvent as RustPlayEvent
import io.github.puflik.plinth.ffi.generated.Playlist as RustPlaylist
import io.github.puflik.plinth.ffi.generated.PlaylistItem as RustPlaylistItem
import io.github.puflik.plinth.ffi.generated.PlaylistRow as RustPlaylistRow
import io.github.puflik.plinth.ffi.generated.StartupReport as RustStartupReport
import io.github.puflik.plinth.ffi.generated.TrackRow as RustTrackRow
import io.github.puflik.plinth.ffi.generated.TrackSort as RustTrackSort
import io.github.puflik.plinth.ffi.generated.TrackUserData as RustTrackUserData
import io.github.puflik.plinth.ffi.generated.VersionPreference as RustVersionPreference

// Перевод между сгенерированными типами ядра и типами приложения
// (`CoreModels.kt`). Идентификаторы в биндингах — строки, время —
// миллисекунды, длительности — java.time; здесь — обёртки и kotlin.time.

internal fun RustTrackRow.toApp() =
    CoreTrack(
        id = TrackId(id),
        title = title,
        artistCredit = artistCredit,
        album = album?.let(::AlbumId),
        albumTitle = albumTitle,
        albumArtist = albumArtist,
        disc = disc?.toInt(),
        number = number?.toInt(),
        duration = duration?.toKotlinDuration(),
        uri = uri,
        folder = folder,
        liked = liked,
        playCount = playCount.toInt(),
    )

internal fun RustAlbumRow.toApp() = CoreAlbum(AlbumId(id), title, artistCredit, trackCount.toInt(), coverUri)

internal fun RustArtistRow.toApp() = CoreArtist(ArtistId(id), name, albumCount.toInt(), trackCount.toInt())

internal fun RustArtwork.toApp() = CoreArtwork(mime, data)

internal fun RustTrackUserData.toApp() =
    TrackUserData(
        track = TrackId(track),
        liked = liked,
        rating = rating?.toInt(),
        playCount = playCount.toInt(),
        lastPlayedAt = lastPlayedAt?.let(Instant::fromEpochMilliseconds),
    )

internal fun RustPlaylist.toApp() = Playlist(PlaylistId(id), name, Instant.fromEpochMilliseconds(createdAt))

internal fun RustPlaylistItem.toApp() =
    PlaylistItem(PlaylistEntryId(id), TrackId(track), Instant.fromEpochMilliseconds(addedAt))

internal fun RustPlaylistRow.toApp() = CorePlaylistTrack(PlaylistEntryId(entry), track.toApp())

internal fun RustPlayEvent.toApp() =
    PlayEvent(
        id = PlayEventId(id),
        track = TrackId(track),
        startedAt = Instant.fromEpochMilliseconds(startedAt),
        utcOffsetMinutes = utcOffsetMinutes.toInt(),
        listened = listened.toKotlinDuration(),
        trackLength = trackLength?.toKotlinDuration(),
        skippedAt = skippedAt?.toKotlinDuration(),
        output = output.toApp(),
        previousTrack = previousTrack?.let(::TrackId),
        version = version?.let(::VersionId),
        source = source?.let(::SourceId),
    )

internal fun NewPlay.toRust() =
    RustNewPlay(
        track = track.value,
        version = version?.value,
        source = source?.value,
        startedAt = startedAt.toEpochMilliseconds(),
        utcOffsetMinutes = utcOffsetMinutes.toShort(),
        listened = listened.toJavaDuration(),
        trackLength = trackLength?.toJavaDuration(),
        skippedAt = skippedAt?.toJavaDuration(),
        output = output.toRust(),
        previousTrack = previousTrack?.value,
    )

internal fun RustStartupReport.toApp() = StartupReport(databaseRecovered, restoredFromJournal)

internal fun CoreTrackSort.toRust() =
    when (this) {
        CoreTrackSort.TITLE -> RustTrackSort.TITLE
        CoreTrackSort.ARTIST -> RustTrackSort.ARTIST
        CoreTrackSort.ALBUM -> RustTrackSort.ALBUM
        CoreTrackSort.RECENTLY_ADDED -> RustTrackSort.RECENTLY_ADDED
        CoreTrackSort.MOST_PLAYED -> RustTrackSort.MOST_PLAYED
    }

internal fun CoreAlbumSort.toRust() =
    when (this) {
        CoreAlbumSort.TITLE -> RustAlbumSort.TITLE
        CoreAlbumSort.ARTIST -> RustAlbumSort.ARTIST
    }

internal fun VersionPreference.toRust() =
    when (this) {
        VersionPreference.ORIGINAL -> RustVersionPreference.ORIGINAL
        VersionPreference.CLEAN -> RustVersionPreference.CLEAN
        VersionPreference.ANY -> RustVersionPreference.ANY
    }

internal fun RustVersionPreference.toApp() =
    when (this) {
        RustVersionPreference.ORIGINAL -> VersionPreference.ORIGINAL
        RustVersionPreference.CLEAN -> VersionPreference.CLEAN
        RustVersionPreference.ANY -> VersionPreference.ANY
    }

private fun OutputDevice.toRust() =
    when (this) {
        OutputDevice.SPEAKER -> RustOutputDevice.SPEAKER
        OutputDevice.HEADPHONES -> RustOutputDevice.HEADPHONES
        OutputDevice.BLUETOOTH -> RustOutputDevice.BLUETOOTH
        OutputDevice.CAR -> RustOutputDevice.CAR
        OutputDevice.CAST -> RustOutputDevice.CAST
        OutputDevice.UNKNOWN -> RustOutputDevice.UNKNOWN
    }

private fun RustOutputDevice.toApp() =
    when (this) {
        RustOutputDevice.SPEAKER -> OutputDevice.SPEAKER
        RustOutputDevice.HEADPHONES -> OutputDevice.HEADPHONES
        RustOutputDevice.BLUETOOTH -> OutputDevice.BLUETOOTH
        RustOutputDevice.CAR -> OutputDevice.CAR
        RustOutputDevice.CAST -> OutputDevice.CAST
        RustOutputDevice.UNKNOWN -> OutputDevice.UNKNOWN
    }
