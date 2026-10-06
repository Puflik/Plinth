package io.github.puflik.plinth.ffi

import kotlin.time.Duration
import kotlin.time.Instant
import io.github.puflik.plinth.ffi.generated.TestFile as RustTestFile

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
value class ArtistId(
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
 * Порядок списка треков ядра. Названия — в естественном порядке и без
 * ведущего артикля; пустое (нет исполнителя, нет альбома) — в конце.
 */
enum class CoreTrackSort {
    /** По названию, затем по исполнителю. */
    TITLE,

    /** По исполнителю; внутри — альбомы, диск и номер. */
    ARTIST,

    /** По альбому и его исполнителю; внутри — диск и номер. */
    ALBUM,
    RECENTLY_ADDED,
    MOST_PLAYED,
}

/** Порядок списка альбомов ядра; альбомы без исполнителя — в конце. */
enum class CoreAlbumSort {
    TITLE,
    ARTIST,
}

/**
 * Строка списка треков из ядра: песня, её основная версия, файл и
 * пользовательское. Видны только треки, которые можно сыграть.
 *
 * @property albumArtist исполнитель альбома: album artist, «Various Artists» у сборника.
 * @property uri путь к файлу, из которого трек играет.
 * @property folder папка файла от корня тома: `Music/Queen/`.
 */
data class CoreTrack(
    val id: TrackId,
    val title: String,
    val artistCredit: String,
    val album: AlbumId?,
    val albumTitle: String?,
    val albumArtist: String? = null,
    val disc: Int? = null,
    val number: Int? = null,
    val duration: Duration?,
    val uri: String? = null,
    val folder: String? = null,
    val liked: Boolean,
    val playCount: Int,
)

/**
 * Альбом из ядра.
 *
 * @property coverUri файл первого трека альбома — его картинка служит обложкой.
 */
data class CoreAlbum(
    val id: AlbumId,
    val title: String,
    val artistCredit: String?,
    val trackCount: Int,
    val coverUri: String?,
)

/** Исполнитель из ядра; альбомы и треки — среди видимых. */
data class CoreArtist(
    val id: ArtistId,
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
)

/** Обложка файла: байты картинки как есть и её тип, если известен. */
class CoreArtwork(
    val mime: String?,
    val data: ByteArray,
)

/**
 * Файл с тегами для `PlinthCore.seedForTest` — как его прочёл бы скан.
 *
 * @property uri путь к файлу: по нему трек играет и по нему скрывается.
 * @property title `null` или пусто — название из имени файла.
 * @property artist строка исполнителя; на артистов её делит ядро.
 */
data class CoreTestFile(
    val uri: String,
    val folder: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val disc: Int? = null,
    val number: Int? = null,
    val duration: Duration? = null,
) {
    internal fun toRust() =
        RustTestFile(
            uri = uri,
            folder = folder,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            disc = disc?.toUInt(),
            number = number?.toUInt(),
            durationMs = duration?.inWholeMilliseconds?.toULong(),
        )
}

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

/**
 * Итог импорта плейлиста из файла (D4c): сколько строк нашлось в фонотеке
 * и сколько нет. Не нашлось ни одной — плейлиста нет, [playlist] — `null`.
 */
data class PlaylistImport(
    val playlist: Playlist?,
    val added: Int,
    val notFound: Int,
)

/** Запись плейлиста в его порядке; позицию ведёт ядро, снаружи — индексы. */
data class PlaylistItem(
    val id: PlaylistEntryId,
    val track: TrackId,
    val addedAt: Instant,
)

/**
 * Видимый трек плейлиста со своей записью: один трек может стоять дважды.
 * Пропавшие файлы скрыты, поэтому индекс здесь — не индекс среди всех
 * записей ([PlaylistItem]), по которому переставляет ядро.
 */
data class CorePlaylistTrack(
    val entry: PlaylistEntryId,
    val track: CoreTrack,
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
 * Что ядро сделало при открытии: база была испорчена или миграция на ней не
 * прошла, и файл отложен в сторону; порчу могла найти и проверка прошлого
 * запуска ([PlinthCore.checkIntegrity]). Лайки, плейлисты и история собраны
 * заново из журнала. Каталог после этого вернёт скан.
 *
 * [journalStartedOver] — снимок журнала не читался: он отложен рядом копией,
 * журнал начат заново с тем, что показывала база, а у установки новое имя
 * (копия в папке, записанная до порчи, теперь чужая). Перенесено ли
 * что-нибудь — по [restoredFromJournal]: `false` — вернуть данные может
 * только копия в папке.
 */
data class StartupReport(
    val databaseRecovered: Boolean,
    val restoredFromJournal: Boolean,
    val journalStartedOver: Boolean = false,
)

/** Открылось ли ядро ([PlinthCore.opening], Р1.4). */
enum class CoreOpening {
    /** Ещё не пробовали. */
    PENDING,
    OPEN,

    /** Последняя попытка не удалась; данные не тронуты. */
    FAILED,
}

/** Итог плановой проверки целостности базы ([PlinthCore.checkIntegrity]). */
enum class CoreIntegrity {
    /** Не этот запуск (проверка — раз в 20) или уже проверено. */
    NOT_DUE,
    HEALTHY,

    /** Нашлась порча: следующее открытие отложит базу и соберёт её заново. */
    DAMAGED,
}
