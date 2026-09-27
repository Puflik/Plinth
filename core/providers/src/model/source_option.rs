use std::time::Duration;

use plinth_types::{Bitrate, Format, Mbid, QualityTier};

use super::ExternalId;

/// Трек у провайдера — то, что отдаёт `resolve` (plan.md 6.1: `VersionInfo`
/// и варианты источника). В каталоге станет `Track` → `Version` → `Source`,
/// когда с треком что-то сделают.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RemoteTrack {
    pub external: ExternalId,
    /// Непустое, как у [`super::SearchResult`].
    pub title: String,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub disc: Option<u16>,
    pub number: Option<u16>,
    pub year: Option<u16>,
    pub duration: Option<Duration>,
    /// Запись MusicBrainz, если провайдер её знает.
    pub mbid: Option<Mbid>,
    /// Хотя бы один: трек без звука провайдер не отдаёт.
    pub sources: Vec<SourceOption>,
}

/// Вариант источника (E1.2): один и тот же трек у Internet Archive лежит
/// FLAC, MP3 и Ogg — это три варианта. `external` — то, что уйдёт в
/// `SourceLocation::Provider` и потом в `stream_url`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceOption {
    pub external: ExternalId,
    pub format: Format,
    pub bitrate: Option<Bitrate>,
}

impl SourceOption {
    pub fn is_lossless(&self) -> bool {
        self.format.is_lossless()
    }

    pub fn tier(&self) -> QualityTier {
        QualityTier::of(self.format, self.bitrate)
    }
}

#[cfg(test)]
mod tests {
    use plinth_types::{Bitrate, Format, QualityTier};

    use super::SourceOption;
    use crate::model::ExternalId;

    fn option(format: Format, kbps: Option<u32>) -> SourceOption {
        SourceOption { external: ExternalId::new("item/file").unwrap(), format, bitrate: kbps.map(Bitrate::kbps) }
    }

    #[test]
    fn quality_comes_from_format_and_bitrate() {
        assert!(option(Format::Flac, None).is_lossless());
        assert_eq!(option(Format::Flac, None).tier(), QualityTier::Lossless);
        assert_eq!(option(Format::Mp3, Some(320)).tier(), QualityTier::HighLossy);
        assert_eq!(option(Format::Vorbis, None).tier(), QualityTier::Standard);
        assert!(!option(Format::Mp3, Some(320)).is_lossless());
    }
}
