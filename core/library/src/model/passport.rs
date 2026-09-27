use std::time::Duration;

use plinth_types::{Mbid, TrackId};

/// Паспорт трека (C4, ADR 0007): по чему узнать трек, когда скан новой
/// установки выдаст его файлу новый ID. Лежит в журнале рядом с
/// пользовательскими данными трека; путей нет — они привязаны к устройству.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackPassport {
    pub track: TrackId,
    pub title: String,
    /// Исполнитель строкой, как в каталоге.
    pub artist: String,
    /// Альбом — различает одну песню с альбома и со сборника.
    pub album: Option<String>,
    pub duration: Option<Duration>,
    /// Запись MusicBrainz — если известна, узнаёт трек точнее всего.
    pub mbid: Option<Mbid>,
}
