//! Модель библиотеки (B1, plan.md 2.1 и 4.1).
//!
//! Сердце — тройка [`Track`] → [`Version`] → [`Source`]: песня, конкретная
//! запись, откуда брать байты. Лайки, оценка и счётчики — на песне
//! ([`TrackUserData`]): слушал clean на работе и explicit дома — счётчик один.
//!
//! Две природы данных, граница — журнал (C2, `plan.md` 17.4):
//! - **каталог** (`Track`, `Version`, `Source`, `Artist`, `Album`)
//!   пересобирается сканом и провайдерами, живёт в SQLite;
//! - **пользовательское** (`TrackUserData`, плейлисты, прослушивания, склейки,
//!   подписки, чёрный список, синхронизируемые настройки) незаменимо и
//!   идёт из журнала; база — его проекция.

mod album;
mod artist;
mod blocklist;
mod merge_decision;
mod play_event;
mod playlist;
mod settings;
mod source;
mod subscription;
mod track;
mod version;

pub use album::Album;
pub use artist::Artist;
pub use blocklist::{BlockEntry, BlockTarget};
pub use merge_decision::{DecidedBy, IdentityBasis, MergeDecision, TrackPair, Verdict};
pub use play_event::{OutputDevice, PlayEvent};
pub use playlist::{Playlist, PlaylistEntry, PlaylistKind, ordered_entries, position_for};
pub use settings::{Setting, SyncedSettings, VersionPreference};
pub use source::{AudioSpec, CacheState, ProviderId, Source, SourceLocation};
pub use subscription::Subscription;
pub use track::{Rating, Track, TrackUserData};
pub use version::{AlbumPlacement, Explicitness, Fingerprint, Version, VersionKind};
