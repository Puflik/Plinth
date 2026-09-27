use std::time::Duration;

use plinth_types::{Mbid, ProviderId};

use super::AudioSpec;

/// Что провайдер знает о треке — без типов провайдеров: каталог о них не знает.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OnlineTrack {
    pub title: String,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub number: Option<u16>,
    pub year: Option<u16>,
    pub duration: Option<Duration>,
    pub mbid: Option<Mbid>,
    /// Варианты: один и тот же трек FLAC, MP3, Ogg.
    pub sources: Vec<OnlineSource>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OnlineSource {
    pub provider: ProviderId,
    pub external_id: String,
    pub audio: AudioSpec,
}
