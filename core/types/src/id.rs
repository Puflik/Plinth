//! Типизированные идентификаторы (A1.2). Внутри — UUIDv7: уникален без
//! координации между устройствами (журнал синхронизируется, v1.5) и растёт
//! со временем создания — вставки в индекс SQLite идут в конец.
//!
//! Идентификатор **не выводится из тегов**. Одну и ту же песню на двух
//! устройствах связывает склейка (`MergeDecision`), а не совпавший ID: иначе
//! смена правил нормализации в новой версии оторвала бы от треков лайки и
//! историю.

use std::fmt;
use std::str::FromStr;

use uuid::Uuid;

use crate::CoreError;

/// Общее у всех идентификаторов сущностей: 16 байт UUID. По нему хранилище
/// читает и пишет любой из них одним кодом.
pub trait EntityId: Copy {
    fn from_bytes(bytes: [u8; 16]) -> Self;
    fn as_bytes(&self) -> &[u8; 16];
}

macro_rules! ids {
    ($($(#[$doc:meta])* $name:ident),+ $(,)?) => {$(
        $(#[$doc])*
        #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
        pub struct $name(Uuid);

        impl $name {
            /// Новый идентификатор; созданные позже сортируются после.
            #[allow(clippy::new_without_default, reason = "у идентификатора нет значения по умолчанию")]
            pub fn new() -> Self {
                Self(Uuid::now_v7())
            }

        }

        /// 16 байт — так идентификатор лежит в SQLite (BLOB).
        impl EntityId for $name {
            fn from_bytes(bytes: [u8; 16]) -> Self {
                Self(Uuid::from_bytes(bytes))
            }

            fn as_bytes(&self) -> &[u8; 16] {
                self.0.as_bytes()
            }
        }

        /// 36 знаков, строчные: `0192f7c4-…`. Так идентификатор идёт в журнал и в Kotlin.
        impl fmt::Display for $name {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                self.0.hyphenated().fmt(f)
            }
        }

        impl FromStr for $name {
            type Err = CoreError;

            fn from_str(text: &str) -> Result<Self, Self::Err> {
                Uuid::try_parse(text)
                    .map(Self)
                    .map_err(|error| CoreError::parse(format!("{}: {error}", stringify!($name))))
            }
        }
    )+};
}

ids! {
    /// Песня (`Track`).
    TrackId,
    /// Конкретная запись песни (`Version`).
    VersionId,
    /// Откуда брать байты записи (`Source`).
    SourceId,
    AlbumId,
    ArtistId,
    PlaylistId,
    /// Запись в плейлисте: один трек может стоять в плейлисте дважды.
    PlaylistEntryId,
    PlayEventId,
    /// Решение о склейке или разъединении треков.
    MergeDecisionId,
    /// Установка приложения — автор операции журнала (C2). Новая установка на
    /// том же телефоне — новое устройство.
    DeviceId,
}

/// Идентификатор MusicBrainz (MBID) — «золотой» уровень идентичности
/// (plan.md 4.2). Чужой: не создаётся, только читается из тегов и ответов.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Mbid(Uuid);

impl fmt::Display for Mbid {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.0.hyphenated().fmt(f)
    }
}

impl FromStr for Mbid {
    type Err = CoreError;

    fn from_str(text: &str) -> Result<Self, Self::Err> {
        Uuid::try_parse(text.trim()).map(Self).map_err(|error| CoreError::parse(format!("MBID: {error}")))
    }
}

#[cfg(test)]
mod tests {
    use super::{EntityId, Mbid, PlaylistEntryId, TrackId};
    use crate::CoreError;

    #[test]
    fn new_ids_are_unique_and_ordered_by_creation() {
        let ids: Vec<TrackId> = (0..1_000).map(|_| TrackId::new()).collect();

        let mut sorted = ids.clone();
        sorted.sort();
        sorted.dedup();
        assert_eq!(sorted, ids);
    }

    #[test]
    fn text_form_round_trips() {
        let id = TrackId::new();

        let text = id.to_string();

        assert_eq!(text.len(), 36);
        assert_eq!(text.parse::<TrackId>(), Ok(id));
    }

    #[test]
    fn bytes_round_trip() {
        let id = PlaylistEntryId::new();

        assert_eq!(PlaylistEntryId::from_bytes(*id.as_bytes()), id);
    }

    #[test]
    fn mbid_from_tag_text() {
        let mbid: Mbid = " b1a9c0e9-d987-4042-ae91-78d6a3267d69 ".parse().unwrap();

        assert_eq!(mbid.to_string(), "b1a9c0e9-d987-4042-ae91-78d6a3267d69");
        assert!("radiohead".parse::<Mbid>().is_err());
    }

    #[test]
    fn garbage_is_a_parse_error() {
        assert!(matches!("not-an-id".parse::<TrackId>(), Err(CoreError::Parse { .. })));
    }
}
