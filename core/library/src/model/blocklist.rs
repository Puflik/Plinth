use plinth_types::{ArtistId, Timestamp, TrackId};

/// Что не предлагать в рекомендациях и радио (B1.4, v0.9). Живёт в журнале.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum BlockTarget {
    Track(TrackId),
    Artist(ArtistId),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BlockEntry {
    pub target: BlockTarget,
    pub since: Timestamp,
}
