use std::fmt;

use plinth_types::{Availability, Bitrate, CoreError, Format, QualityTier, SourceId, Timestamp, VersionId};

/// Откуда брать байты записи (plan.md 2.1). У версии их может быть
/// несколько: локальный FLAC и стрим провайдера. Каталог.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Source {
    pub id: SourceId,
    pub version: VersionId,
    pub location: SourceLocation,
    pub audio: AudioSpec,
    pub availability: Availability,
    /// Когда доступность проверяли в последний раз.
    pub last_checked_at: Option<Timestamp>,
}

impl Source {
    pub fn tier(&self) -> QualityTier {
        self.audio.tier()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SourceLocation {
    /// Файл на этом устройстве: `content://` из `MediaStore` или путь.
    Local { uri: String },
    /// Трек у провайдера; `external_id` — его идентификатор у провайдера.
    Provider { provider: ProviderId, external_id: String, cache: CacheState },
}

impl SourceLocation {
    /// Привязан ли источник к устройству. Такой не попадает ни в журнал, ни в
    /// синхронизацию: на другом устройстве путь ничего не значит.
    pub fn is_device_bound(&self) -> bool {
        matches!(self, Self::Local { .. })
    }
}

/// Кэш стрима (v0.5). У локального файла кэша нет — он и так на месте.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum CacheState {
    NotCached,
    Partial,
    Cached,
}

/// Что за звук в источнике.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct AudioSpec {
    pub format: Format,
    pub bitrate: Option<Bitrate>,
    /// Для Hi-Res: 96 000, 192 000.
    pub sample_rate_hz: Option<u32>,
    pub bit_depth: Option<u8>,
}

impl AudioSpec {
    pub fn tier(&self) -> QualityTier {
        QualityTier::of(self.format, self.bitrate)
    }
}

/// Имя провайдера: `archive.org`, `bandcamp`. Строчные латиница, цифры,
/// точка и дефис, до 64 знаков — идёт в журнал и в ключи базы.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ProviderId(String);

impl ProviderId {
    pub fn new(name: &str) -> Result<Self, CoreError> {
        let valid = (1..=64).contains(&name.len())
            && name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'.' || b == b'-');
        if valid { Ok(Self(name.to_owned())) } else { Err(CoreError::parse(format!("provider id: {name:?}"))) }
    }
}

impl fmt::Display for ProviderId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

#[cfg(test)]
mod tests {
    use plinth_types::{Availability, Bitrate, CoreError, Format, QualityTier, SourceId, VersionId};

    use super::{AudioSpec, CacheState, ProviderId, Source, SourceLocation};

    fn source(location: SourceLocation, format: Format, kbps: Option<u32>) -> Source {
        Source {
            id: SourceId::new(),
            version: VersionId::new(),
            location,
            audio: AudioSpec { format, bitrate: kbps.map(Bitrate::kbps), sample_rate_hz: None, bit_depth: None },
            availability: Availability::Available,
            last_checked_at: None,
        }
    }

    #[test]
    fn tier_comes_from_format_and_bitrate() {
        let flac = source(SourceLocation::Local { uri: "content://media/1".to_owned() }, Format::Flac, None);
        let archive = ProviderId::new("archive.org").unwrap();
        let opus = source(
            SourceLocation::Provider { provider: archive, external_id: "x".to_owned(), cache: CacheState::NotCached },
            Format::Opus,
            Some(160),
        );

        assert_eq!(flac.tier(), QualityTier::Lossless);
        assert_eq!(opus.tier(), QualityTier::Standard);
    }

    /// Локальный путь привязан к устройству: такой источник не уходит в журнал и синк.
    #[test]
    fn only_local_files_are_device_bound() {
        let local = SourceLocation::Local { uri: "/storage/emulated/0/Music/a.flac".to_owned() };
        let remote = SourceLocation::Provider {
            provider: ProviderId::new("archive.org").unwrap(),
            external_id: "etree-1".to_owned(),
            cache: CacheState::Cached,
        };

        assert!(local.is_device_bound());
        assert!(!remote.is_device_bound());
    }

    #[test]
    fn provider_id_is_a_short_lowercase_name() {
        for good in ["archive.org", "youtube", "bandcamp", "my-navidrome"] {
            assert_eq!(ProviderId::new(good).map(|p| p.to_string()), Ok(good.to_owned()));
        }
        for bad in ["", "YouTube", "you tube", "a/b", &"x".repeat(65)] {
            assert!(matches!(ProviderId::new(bad), Err(CoreError::Parse { .. })), "{bad}");
        }
    }
}
