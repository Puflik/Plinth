package io.github.puflik.plinth.ffi

import kotlin.time.Duration
import kotlin.time.Instant

// Типы API ядра для остального приложения (A3). Сгенерированные UniFFI
// типы за пределы пакета `ffi` не выходят (ADR 0010, `FfiBoundaryTest`):
// фасад переводит их сюда в `CoreMapping.kt`. Идентификаторы ядра — UUIDv7
// строкой в 36 знаков; обёртки не дают перепутать трек с плейлистом.

@JvmInline
value class TrackId(
    val value: String,
)

@JvmInline
value class AlbumId(
    val value: String,
)

@JvmInline
value class VersionId(
    val value: String,
)

@JvmInline
value class SourceId(
    val value: String,
)

@JvmInline
value class PlaylistId(
    val value: String,
)

@JvmInline
value class PlaylistEntryId(
    val value: String,
)

@JvmInline
value class PlayEventId(
    val value: String,
)

/**
 * Порядок списка треков ядра. Сортировки экранов v0.1 (`library.sort`)
 * переедут в ядро в D3 — тогда два порядка сольются в один.
 */
enum class CoreTrackSort {
    TITLE,
    ARTIST,
    RECENTLY_ADDED,
    MOST_PLAYED,
}

/**
 * Строка списка треков из ядра: песня, её основная версия и пользовательское.
 * `Core` в названии — до D3, где она заменит `LibraryTrack` из Room.
 */
data class CoreTrack(
    val id: TrackId,
    val title: String,
    val artistCredit: String,
    val album: AlbumId?,
    val albumTitle: String?,
    val duration: Duration?,
    val liked: Boolean,
    val playCount: Int,
)

/** Лайк, оценка (1–5) и счётчики трека; не слушали и не оценивали — пустые. */
data class TrackUserData(
    val track: TrackId,
    val liked: Boolean,
    val rating: Int?,
    val playCount: Int,
    val lastPlayedAt: Instant?,
)

data class Playlist(
    val id: PlaylistId,
    val name: String,
    val createdAt: Instant,
)

/** Запись плейлиста в его порядке; позицию ведёт ядро, снаружи — индексы. */
data class PlaylistItem(
    val id: PlaylistEntryId,
    val track: TrackId,
    val addedAt: Instant,
)

enum class OutputDevice {
    SPEAKER,
    HEADPHONES,
    BLUETOOTH,
    CAR,
    CAST,
    UNKNOWN,
}

/**
 * Прослушивание — факты без выводов: засчитать ли его в счётчик, решает ядро
 * (правило Last.fm: половина трека или 4 минуты).
 *
 * @property utcOffsetMinutes пояс устройства в момент прослушивания.
 * @property listened сколько звучало на самом деле, без перемотки вперёд.
 * @property skippedAt где пропустили; `null` — дослушали или остановили не пропуском.
 */
data class NewPlay(
    val track: TrackId,
    val startedAt: Instant,
    val utcOffsetMinutes: Int,
    val listened: Duration,
    val trackLength: Duration? = null,
    val skippedAt: Duration? = null,
    val output: OutputDevice = OutputDevice.UNKNOWN,
    val previousTrack: TrackId? = null,
    val version: VersionId? = null,
    val source: SourceId? = null,
)

/** Прослушивание из истории — [NewPlay] с идентификатором от ядра. */
data class PlayEvent(
    val id: PlayEventId,
    val track: TrackId,
    val startedAt: Instant,
    val utcOffsetMinutes: Int,
    val listened: Duration,
    val trackLength: Duration?,
    val skippedAt: Duration?,
    val output: OutputDevice,
    val previousTrack: TrackId?,
    val version: VersionId?,
    val source: SourceId?,
)

/** Какую версию играть, если их несколько — настройка едет за человеком между устройствами. */
enum class VersionPreference {
    ORIGINAL,
    CLEAN,
    ANY,
}

/**
 * Что ядро сделало при открытии: база была испорчена и отложена в сторону;
 * лайки, плейлисты и история собраны заново из журнала. Каталог после этого
 * вернёт скан.
 */
data class StartupReport(
    val databaseRecovered: Boolean,
    val restoredFromJournal: Boolean,
)
