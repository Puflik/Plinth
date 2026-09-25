use std::time::Duration;

use plinth_types::{AlbumId, Mbid, TrackId, VersionId};

/// Конкретная запись песни (plan.md 2.1): студийная, live, remaster… и
/// explicit или clean. Каталог; статистики здесь нет — она на [`crate::model::Track`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Version {
    pub id: VersionId,
    pub track: TrackId,
    pub kind: VersionKind,
    pub explicitness: Explicitness,
    pub duration: Option<Duration>,
    /// Где запись стоит на альбоме. Та же запись на сборнике — отдельная версия.
    pub album: Option<AlbumPlacement>,
    pub release_year: Option<u16>,
    /// Запись MusicBrainz.
    pub mbid_recording: Option<Mbid>,
    /// Акустический отпечаток — «серебряный» уровень склейки; заполняется в v0.3.
    pub fingerprint: Option<Fingerprint>,
}

/// Какая это запись. Цензура — отдельная ось ([`Explicitness`]): бывает и
/// clean-версия live-записи.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum VersionKind {
    Original,
    Live,
    Acoustic,
    Remaster,
    RadioEdit,
}

/// По ней работает политика версий (plan.md 2.2).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Explicitness {
    Explicit,
    Clean,
    /// В тегах локальных файлов пометки почти никогда нет.
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct AlbumPlacement {
    pub album: AlbumId,
    pub disc: Option<u16>,
    pub number: Option<u16>,
}

/// Отпечаток Chromaprint в сжатой текстовой форме — той же, что принимает AcoustID.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Fingerprint(pub String);
