//! Общие типы ядра (A1.2). Крейт ни от кого в ядре не зависит: иначе
//! `library` и `providers` начнут тянуть типы друг у друга по кругу.
//! Здесь — словарь, общий для каталога (`library`), журнала (`sync`) и
//! провайдеров: идентификаторы, время, качество, доступность, позиция.

#![forbid(unsafe_code)]

mod availability;
mod error;
mod id;
mod position;
mod quality;
mod time;

pub use availability::Availability;
pub use error::CoreError;
pub use id::{
    AlbumId, ArtistId, DeviceId, EntityId, Mbid, MergeDecisionId, PlayEventId, PlaylistEntryId, PlaylistId, SourceId,
    TrackId, VersionId,
};
pub use position::Position;
pub use quality::{Bitrate, Format, QualityTier};
pub use time::Timestamp;
