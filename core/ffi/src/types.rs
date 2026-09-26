//! Типы, которые пересекают границу (ADR 0010). Типы модели живут в
//! `plinth-types`, `plinth-library` и `plinth-sync`, которые об UniFFI не
//! знают, поэтому здесь — их копии для генератора (`remote`): разойдётся
//! копия с оригиналом — не соберётся. Своих записей у границы немного:
//! то, чего в модели нет, — отчёт о запуске, строка плейлиста без позиции,
//! прослушивание без идентификатора.
//!
//! Идентификаторы в Kotlin — строки в 36 знаков, время — миллисекунды от
//! эпохи, длительности — `java.time.Duration`, оценка — число от 1 до 5.

use std::time::Duration;

use plinth_library::db::query::{AlbumRow, AlbumSort, ArtistRow, PlaylistRow, TrackRow, TrackSort};
use plinth_library::model::{
    OutputDevice, PlayEvent, Playlist, PlaylistKind, Rating, SyncedSettings, TrackUserData, VersionPreference,
};
use plinth_library::scan::Artwork;
use plinth_types::{
    AlbumId, ArtistId, PlayEventId, PlaylistEntryId, PlaylistId, SourceId, Timestamp, TrackId, VersionId,
};

macro_rules! text_ids {
    ($($id:ident),+ $(,)?) => {$(
        uniffi::custom_type!($id, String, {
            remote,
            lower: |id| id.to_string(),
            try_lift: |text| Ok(text.parse::<$id>()?),
        });
    )+};
}

text_ids!(TrackId, AlbumId, ArtistId, VersionId, SourceId, PlaylistId, PlaylistEntryId, PlayEventId);

uniffi::custom_type!(Timestamp, i64, {
    remote,
    lower: |at| at.as_millis(),
    try_lift: |millis| Ok(Timestamp::from_millis(millis)),
});

uniffi::custom_type!(Rating, u8, {
    remote,
    lower: |rating| rating.stars(),
    try_lift: |stars| Ok(Rating::new(stars)?),
});

#[uniffi::remote(Enum)]
pub enum TrackSort {
    Title,
    Artist,
    Album,
    RecentlyAdded,
    MostPlayed,
}

#[uniffi::remote(Record)]
pub struct TrackRow {
    pub id: TrackId,
    pub title: String,
    pub artist_credit: String,
    pub album: Option<AlbumId>,
    pub album_title: Option<String>,
    pub album_artist: Option<String>,
    pub disc: Option<u16>,
    pub number: Option<u16>,
    pub duration: Option<Duration>,
    pub uri: Option<String>,
    pub folder: Option<String>,
    pub liked: bool,
    pub play_count: u32,
}

/// Строка плейлиста: запись и её трек — один трек может стоять дважды.
#[uniffi::remote(Record)]
pub struct PlaylistRow {
    pub entry: PlaylistEntryId,
    pub track: TrackRow,
}

#[uniffi::remote(Enum)]
pub enum AlbumSort {
    Title,
    Artist,
}

#[uniffi::remote(Record)]
pub struct AlbumRow {
    pub id: AlbumId,
    pub title: String,
    pub artist_credit: Option<String>,
    pub track_count: u32,
    pub cover_uri: Option<String>,
}

#[uniffi::remote(Record)]
pub struct ArtistRow {
    pub id: ArtistId,
    pub name: String,
    pub album_count: u32,
    pub track_count: u32,
}

#[uniffi::remote(Record)]
pub struct Artwork {
    pub mime: Option<String>,
    pub data: Vec<u8>,
}

#[uniffi::remote(Record)]
pub struct TrackUserData {
    pub track: TrackId,
    pub liked: bool,
    pub rating: Option<Rating>,
    pub play_count: u32,
    pub last_played_at: Option<Timestamp>,
}

#[uniffi::remote(Enum)]
pub enum PlaylistKind {
    Manual,
}

#[uniffi::remote(Record)]
pub struct Playlist {
    pub id: PlaylistId,
    pub name: String,
    pub kind: PlaylistKind,
    pub created_at: Timestamp,
}

#[uniffi::remote(Enum)]
pub enum OutputDevice {
    Speaker,
    Headphones,
    Bluetooth,
    Car,
    Cast,
    Unknown,
}

#[uniffi::remote(Record)]
pub struct PlayEvent {
    pub id: PlayEventId,
    pub track: TrackId,
    pub version: Option<VersionId>,
    pub source: Option<SourceId>,
    pub started_at: Timestamp,
    pub utc_offset_minutes: i16,
    pub listened: Duration,
    pub track_length: Option<Duration>,
    pub skipped_at: Option<Duration>,
    pub output: OutputDevice,
    pub previous_track: Option<TrackId>,
}

#[uniffi::remote(Enum)]
pub enum VersionPreference {
    Original,
    Clean,
    Any,
}

#[uniffi::remote(Record)]
pub struct SyncedSettings {
    pub version_preference: VersionPreference,
}

/// Что ядро сделало при открытии. `database_recovered` — база была
/// испорчена и отложена в сторону; `restored_from_journal` — лайки,
/// плейлисты и история собраны из журнала заново (база пропала, испорчена
/// или отстала). Каталог после этого вернёт скан.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct StartupReport {
    pub database_recovered: bool,
    pub restored_from_journal: bool,
}

/// Запись плейлиста в его порядке. Позиция — дело ядра: Kotlin работает с
/// индексами.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PlaylistItem {
    pub id: PlaylistEntryId,
    pub track: TrackId,
    pub added_at: Timestamp,
}

/// Прослушивание, которое записывает Kotlin: факты без идентификатора — его
/// выдаёт ядро (UUIDv7).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NewPlay {
    pub track: TrackId,
    pub version: Option<VersionId>,
    pub source: Option<SourceId>,
    pub started_at: Timestamp,
    /// Пояс устройства в момент прослушивания: время суток — местное.
    pub utc_offset_minutes: i16,
    /// Сколько звучало на самом деле, без перемотки вперёд.
    pub listened: Duration,
    pub track_length: Option<Duration>,
    /// Где пропустили; `None` — дослушали или остановили не пропуском.
    pub skipped_at: Option<Duration>,
    pub output: OutputDevice,
    pub previous_track: Option<TrackId>,
}

impl NewPlay {
    pub(crate) fn into_event(self) -> PlayEvent {
        PlayEvent {
            id: PlayEventId::new(),
            track: self.track,
            version: self.version,
            source: self.source,
            started_at: self.started_at,
            utc_offset_minutes: self.utc_offset_minutes,
            listened: self.listened,
            track_length: self.track_length,
            skipped_at: self.skipped_at,
            output: self.output,
            previous_track: self.previous_track,
        }
    }
}
