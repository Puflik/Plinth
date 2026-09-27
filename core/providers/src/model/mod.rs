//! Модель ответа провайдера (E1.2) — одна на всех: Internet Archive,
//! Bandcamp, YouTube отдают одно и то же. В каталог (`Track` / `Version` /
//! `Source`) её переводит ядро, когда с треком что-то сделали.

mod capabilities;
mod external_id;
mod health;
mod search_result;
mod source_option;
mod stream;

pub use capabilities::Capabilities;
pub use external_id::ExternalId;
pub use health::{Health, HealthPolicy};
pub use search_result::{ResultKind, SearchQuery, SearchResult};
pub use source_option::{Network, RemoteTrack, SourceOption, playback_order};
pub use stream::StreamRequest;
