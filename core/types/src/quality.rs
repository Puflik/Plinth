//! Формат, битрейт и лестница качества (A1.2, plan.md 2.3). Ступень решает,
//! какой источник версии играть и когда искать лучший (апгрейд трека, 2.4).

/// Кодек звука, а не контейнер: ALAC и AAC оба бывают в `.m4a`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Format {
    Flac,
    Alac,
    Wav,
    Aiff,
    Mp3,
    Aac,
    Vorbis,
    Opus,
    /// Распознать не удалось или формат редкий; считается lossy.
    Other,
}

impl Format {
    pub fn is_lossless(self) -> bool {
        matches!(self, Self::Flac | Self::Alac | Self::Wav | Self::Aiff)
    }
}

/// Средний битрейт в кбит/с.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Bitrate(u32);

impl Bitrate {
    pub const fn kbps(value: u32) -> Self {
        Self(value)
    }

    pub const fn as_kbps(self) -> u32 {
        self.0
    }
}

/// Ступень лестницы качества; сравнение — «лучше/хуже».
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum QualityTier {
    /// Opus ~160, SoundCloud 128, стрим Bandcamp 128.
    Standard,
    /// MP3 320, AAC 256.
    HighLossy,
    /// FLAC, ALAC, WAV, AIFF.
    Lossless,
}

impl QualityTier {
    /// Lossy с неизвестным битрейтом — `Standard`: лучше недооценить
    /// источник и поискать замену, чем считать его хорошим.
    pub fn of(format: Format, bitrate: Option<Bitrate>) -> Self {
        if format.is_lossless() {
            Self::Lossless
        } else if bitrate.is_some_and(|b| b >= HIGH_LOSSY_FROM) {
            Self::HighLossy
        } else {
            Self::Standard
        }
    }
}

/// Нижняя граница `HighLossy`: AAC 256 на лестнице — «высокий lossy».
const HIGH_LOSSY_FROM: Bitrate = Bitrate::kbps(256);

#[cfg(test)]
mod tests {
    use super::{Bitrate, Format, QualityTier};

    #[test]
    fn lossless_formats_are_lossless_whatever_the_bitrate() {
        for format in [Format::Flac, Format::Alac, Format::Wav, Format::Aiff] {
            assert!(format.is_lossless(), "{format:?}");
            assert_eq!(QualityTier::of(format, None), QualityTier::Lossless);
            assert_eq!(QualityTier::of(format, Some(Bitrate::kbps(128))), QualityTier::Lossless);
        }
    }

    /// Лестница качества, plan.md 2.3.
    #[test]
    fn ladder_rungs() {
        let cases = [
            (Format::Mp3, 320, QualityTier::HighLossy),
            (Format::Aac, 256, QualityTier::HighLossy),
            (Format::Opus, 160, QualityTier::Standard),
            (Format::Mp3, 128, QualityTier::Standard),
            (Format::Aac, 128, QualityTier::Standard),
        ];

        for (format, kbps, tier) in cases {
            assert_eq!(QualityTier::of(format, Some(Bitrate::kbps(kbps))), tier, "{format:?} {kbps}");
        }
    }

    #[test]
    fn unknown_bitrate_of_lossy_is_standard() {
        assert_eq!(QualityTier::of(Format::Mp3, None), QualityTier::Standard);
        assert_eq!(QualityTier::of(Format::Other, None), QualityTier::Standard);
    }

    #[test]
    fn tiers_compare_by_quality() {
        assert!(QualityTier::Lossless > QualityTier::HighLossy);
        assert!(QualityTier::HighLossy > QualityTier::Standard);
    }
}
