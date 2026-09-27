use std::cmp::Reverse;
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

/// Сеть, по которой пойдёт поток (ответ автора, E2): по безлимитной играет
/// лучший вариант, по лимитной (сотовая) — MP3.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Network {
    Unmetered,
    Metered,
}

/// Варианты в порядке, в котором их пробовать играть (E2.3; политики
/// качества — v0.5). Безлимитная сеть — по ступени качества, внутри — по
/// битрейту. Лимитная — MP3 по битрейту, потом прочий lossy, lossless —
/// последним. Равные остаются в исходном порядке.
pub fn playback_order(sources: &[SourceOption], network: Network) -> Vec<&SourceOption> {
    let mut ordered: Vec<&SourceOption> = sources.iter().collect();
    // `Reverse(Option)`: больший битрейт раньше, неизвестный — последним.
    let kbps = |source: &SourceOption| Reverse(source.bitrate.map(Bitrate::as_kbps));
    match network {
        Network::Unmetered => ordered.sort_by_key(|source| (Reverse(source.tier()), kbps(source))),
        Network::Metered => {
            ordered.sort_by_key(|source| (source.is_lossless(), source.format != Format::Mp3, kbps(source)));
        }
    }
    ordered
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

    use super::{Network, SourceOption, playback_order};
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

    #[test]
    fn unmetered_network_plays_the_best() {
        let sources = [
            option(Format::Vorbis, None),
            option(Format::Mp3, Some(64)),
            option(Format::Flac, None),
            option(Format::Mp3, Some(320)),
        ];

        let order: Vec<_> =
            playback_order(&sources, Network::Unmetered).iter().map(|s| (s.format, s.bitrate)).collect();

        assert_eq!(
            order,
            [
                (Format::Flac, None),
                (Format::Mp3, Some(Bitrate::kbps(320))),
                (Format::Mp3, Some(Bitrate::kbps(64))),
                (Format::Vorbis, None)
            ]
        );
    }

    #[test]
    fn metered_network_plays_mp3_then_other_lossy_then_lossless() {
        let sources = [
            option(Format::Flac, None),
            option(Format::Vorbis, Some(500)),
            option(Format::Mp3, Some(64)),
            option(Format::Mp3, Some(233)),
        ];

        let order: Vec<_> = playback_order(&sources, Network::Metered).iter().map(|s| (s.format, s.bitrate)).collect();

        assert_eq!(
            order,
            [
                (Format::Mp3, Some(Bitrate::kbps(233))),
                (Format::Mp3, Some(Bitrate::kbps(64))),
                (Format::Vorbis, Some(Bitrate::kbps(500))),
                (Format::Flac, None)
            ]
        );
    }

    #[test]
    fn nothing_to_order() {
        assert!(playback_order(&[], Network::Metered).is_empty());
    }
}
