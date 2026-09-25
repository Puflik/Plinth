use std::time::Duration;

use plinth_types::{PlayEventId, SourceId, Timestamp, TrackId, VersionId};

/// Одно прослушивание (B1.3) — факт, без выводов: засчитывать ли его в
/// счётчик, решает проекция (C3). Живёт в журнале. Контекст — для
/// рекомендаций: время суток, день недели, устройство вывода, что играло до.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlayEvent {
    pub id: PlayEventId,
    /// Счётчик — на песне, не на версии (plan.md 2.1).
    pub track: TrackId,
    pub version: Option<VersionId>,
    pub source: Option<SourceId>,
    pub started_at: Timestamp,
    /// Пояс устройства в момент прослушивания: время суток — местное.
    pub utc_offset_minutes: i16,
    /// Сколько звучало на самом деле, без перемотки вперёд.
    pub listened: Duration,
    pub track_length: Option<Duration>,
    /// Где пропустили; `None` — дослушали или остановили не пропуском.
    pub skipped_at: Option<Duration>,
    pub output: OutputDevice,
    pub previous_track: Option<TrackId>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum OutputDevice {
    Speaker,
    Headphones,
    Bluetooth,
    Car,
    Cast,
    Unknown,
}

const MINUTE_MS: i64 = 60_000;
const HOUR_MS: i64 = 60 * MINUTE_MS;
const DAY_MS: i64 = 24 * HOUR_MS;

impl PlayEvent {
    /// Доля прослушанного от длины трека, не больше единицы; без длины — `None`.
    pub fn completion(&self) -> Option<f32> {
        let length = self.track_length.filter(|length| !length.is_zero())?;
        let share = (self.listened.as_secs_f64() / length.as_secs_f64()).min(1.0);
        #[expect(clippy::cast_possible_truncation, reason = "доля от 0 до 1, точности f32 хватает")]
        Some(share as f32)
    }

    /// Местный час начала, 0–23.
    pub fn local_hour(&self) -> u8 {
        let hour = self.local_millis().rem_euclid(DAY_MS) / HOUR_MS;
        u8::try_from(hour).unwrap_or(0)
    }

    /// Местный день недели: 0 — понедельник, 6 — воскресенье.
    pub fn local_weekday(&self) -> u8 {
        // 1970-01-01 — четверг, третий день от понедельника.
        let weekday = (self.local_millis().div_euclid(DAY_MS) + 3).rem_euclid(7);
        u8::try_from(weekday).unwrap_or(0)
    }

    fn local_millis(&self) -> i64 {
        self.started_at.as_millis() + i64::from(self.utc_offset_minutes) * MINUTE_MS
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_types::{PlayEventId, Timestamp, TrackId};

    use super::{OutputDevice, PlayEvent};

    fn play(listened_s: u64, length_s: Option<u64>) -> PlayEvent {
        PlayEvent {
            id: PlayEventId::new(),
            track: TrackId::new(),
            version: None,
            source: None,
            // 2026-09-25 03:30 UTC — пятница.
            started_at: Timestamp::from_millis(1_790_307_000_000),
            utc_offset_minutes: 180,
            listened: Duration::from_secs(listened_s),
            track_length: length_s.map(Duration::from_secs),
            skipped_at: None,
            output: OutputDevice::Headphones,
            previous_track: None,
        }
    }

    #[test]
    fn completion_is_listened_share_of_the_length() {
        assert_eq!(play(60, Some(240)).completion(), Some(0.25));
        assert_eq!(play(240, Some(240)).completion(), Some(1.0));
    }

    /// Перемотка назад даёт «прослушано» больше длины — доля не выше единицы.
    #[test]
    fn completion_is_capped_and_needs_a_length() {
        assert_eq!(play(500, Some(240)).completion(), Some(1.0));
        assert_eq!(play(60, None).completion(), None);
        assert_eq!(play(60, Some(0)).completion(), None);
    }

    /// Время суток и день недели — местные, по поясу на момент прослушивания.
    #[test]
    fn local_hour_and_weekday_use_the_offset() {
        let event = play(10, None);

        assert_eq!(event.local_hour(), 6);
        assert_eq!(event.local_weekday(), 4);

        let west = PlayEvent { utc_offset_minutes: -300, ..event };
        assert_eq!(west.local_hour(), 22);
        assert_eq!(west.local_weekday(), 3);
    }
}
