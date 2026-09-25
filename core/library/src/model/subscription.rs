use plinth_types::{ArtistId, Timestamp};

/// Подписка на артиста (B1.4): новые выпуски — в ленту (v1.0). Живёт в журнале.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Subscription {
    pub artist: ArtistId,
    pub since: Timestamp,
}
