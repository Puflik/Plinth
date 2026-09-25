//! Перечисления модели ↔ текстовые коды в базе. Коды — часть схемы: их
//! меняют только миграцией (они же стоят в `CHECK` таблиц).

use plinth_types::{Availability, Format};
use rusqlite::Row;

use super::sql::invalid;
use crate::model::{CacheState, Explicitness, OutputDevice, PlaylistKind, VersionKind};

pub(crate) trait Code: Sized + Copy + 'static {
    const ALL: &'static [Self];
    fn code(self) -> &'static str;

    fn from_code(code: &str) -> Option<Self> {
        Self::ALL.iter().copied().find(|value| value.code() == code)
    }
}

macro_rules! codes {
    ($ty:ty { $($variant:path => $code:literal),+ $(,)? }) => {
        impl Code for $ty {
            const ALL: &'static [Self] = &[$($variant),+];

            fn code(self) -> &'static str {
                match self {
                    $($variant => $code),+
                }
            }
        }
    };
}

codes!(VersionKind {
    VersionKind::Original => "original",
    VersionKind::Live => "live",
    VersionKind::Acoustic => "acoustic",
    VersionKind::Remaster => "remaster",
    VersionKind::RadioEdit => "radio_edit",
});

codes!(Explicitness {
    Explicitness::Explicit => "explicit",
    Explicitness::Clean => "clean",
    Explicitness::Unknown => "unknown",
});

codes!(Format {
    Format::Flac => "flac",
    Format::Alac => "alac",
    Format::Wav => "wav",
    Format::Aiff => "aiff",
    Format::Mp3 => "mp3",
    Format::Aac => "aac",
    Format::Vorbis => "vorbis",
    Format::Opus => "opus",
    Format::Other => "other",
});

codes!(Availability {
    Availability::Available => "available",
    Availability::Degraded => "degraded",
    Availability::Unavailable => "unavailable",
});

codes!(CacheState {
    CacheState::NotCached => "not_cached",
    CacheState::Partial => "partial",
    CacheState::Cached => "cached",
});

codes!(PlaylistKind {
    PlaylistKind::Manual => "manual",
});

codes!(OutputDevice {
    OutputDevice::Speaker => "speaker",
    OutputDevice::Headphones => "headphones",
    OutputDevice::Bluetooth => "bluetooth",
    OutputDevice::Car => "car",
    OutputDevice::Cast => "cast",
    OutputDevice::Unknown => "unknown",
});

pub(crate) fn column<T: Code>(row: &Row<'_>, column: &str) -> rusqlite::Result<T> {
    let text: String = row.get(column)?;
    T::from_code(&text).ok_or_else(|| invalid(column, &text))
}

pub(crate) fn opt_column<T: Code>(row: &Row<'_>, column: &str) -> rusqlite::Result<Option<T>> {
    row.get::<_, Option<String>>(column)?
        .map(|text| T::from_code(&text).ok_or_else(|| invalid(column, &text)))
        .transpose()
}

#[cfg(test)]
mod tests {
    use std::collections::HashSet;

    use super::Code;
    use crate::model::{CacheState, Explicitness, OutputDevice, PlaylistKind, VersionKind};
    use plinth_types::{Availability, Format};

    fn round_trips<T: Code + PartialEq + std::fmt::Debug>() {
        let codes: HashSet<&str> = T::ALL.iter().map(|v| v.code()).collect();
        assert_eq!(codes.len(), T::ALL.len(), "codes must be unique");
        for value in T::ALL {
            assert_eq!(T::from_code(value.code()), Some(*value));
        }
        assert_eq!(T::from_code("no such code"), None);
    }

    #[test]
    fn every_code_round_trips() {
        round_trips::<VersionKind>();
        round_trips::<Explicitness>();
        round_trips::<Format>();
        round_trips::<Availability>();
        round_trips::<CacheState>();
        round_trips::<PlaylistKind>();
        round_trips::<OutputDevice>();
    }
}
