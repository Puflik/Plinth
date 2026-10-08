use std::time::Duration;

use plinth_types::{Mbid, TrackId};

use super::OnlineSource;
use crate::text::normalize;

/// Разница длительности одной песни в разных файлах: кодировщики по-разному
/// считают тишину в начале и конце.
const DURATION_TOLERANCE: Duration = Duration::from_secs(2);

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
    /// Варианты у провайдеров (E3): по ним сетевой трек возвращается после
    /// переустановки без скана. Файлы устройства сюда не попадают.
    pub sources: Vec<OnlineSource>,
}

/// Сравнение паспортов — одно на перепривязку после переустановки (C4) и на
/// скан, который узнаёт перенесённый файл (Р1.2): правила не должны разойтись.
impl TrackPassport {
    /// По чему паспорта сводятся в пары: нормализованные название и исполнитель.
    pub fn key(&self) -> (String, String) {
        (normalize(&self.title), normalize(&self.artist))
    }

    /// Насколько `other` похож на этот паспорт; `None` — это другой трек.
    /// Ключи сверяет вызывающий. MBID, если известен у обоих, — совпадает
    /// (+4); альбом у обоих один (+2); длительность, если известна у обоих, —
    /// в пределах двух секунд (+1), дальше — `None`. Симметрично.
    pub fn likeness(&self, other: &TrackPassport) -> Option<u8> {
        let mut score = 0;
        if let (Some(a), Some(b)) = (self.mbid, other.mbid) {
            if a != b {
                return None;
            }
            score += 4;
        }
        if let (Some(a), Some(b)) = (&self.album, &other.album)
            && normalize(a) == normalize(b)
        {
            score += 2;
        }
        if let (Some(a), Some(b)) = (self.duration, other.duration) {
            if a.abs_diff(b) > DURATION_TOLERANCE {
                return None;
            }
            score += 1;
        }
        Some(score)
    }

    /// Лучший из подходящих `candidates`; при равенстве — первый.
    pub fn best_match(&self, candidates: &[TrackPassport]) -> Option<usize> {
        let mut best: Option<(u8, usize)> = None;
        for (index, candidate) in candidates.iter().enumerate() {
            if let Some(score) = self.likeness(candidate)
                && best.is_none_or(|(top, _)| score > top)
            {
                best = Some((score, index));
            }
        }
        best.map(|(_, index)| index)
    }
}
