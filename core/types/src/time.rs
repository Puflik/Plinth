//! Момент времени (A1.2). Длительности — `std::time::Duration`.

use std::ops::Add;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// Миллисекунды от 1970-01-01 UTC. Часовой пояс здесь не хранится: где он
/// важен (время суток прослушивания), его пишут рядом.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Timestamp(i64);

impl Timestamp {
    pub fn now() -> Self {
        // Часы до 1970 года — сбитые часы устройства: такой момент считаем эпохой.
        let millis = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis());
        Self(i64::try_from(millis).unwrap_or(i64::MAX))
    }

    pub const fn from_millis(millis: i64) -> Self {
        Self(millis)
    }

    pub const fn as_millis(self) -> i64 {
        self.0
    }

    /// Сколько прошло от `earlier`; `None`, если `earlier` позже.
    pub fn since(self, earlier: Self) -> Option<Duration> {
        let delta = self.0.checked_sub(earlier.0)?;
        u64::try_from(delta).ok().map(Duration::from_millis)
    }
}

impl Add<Duration> for Timestamp {
    type Output = Self;

    fn add(self, duration: Duration) -> Self {
        let millis = i64::try_from(duration.as_millis()).unwrap_or(i64::MAX);
        Self(self.0.saturating_add(millis))
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::Timestamp;

    #[test]
    fn now_is_after_2026() {
        assert!(Timestamp::now() > Timestamp::from_millis(1_767_225_600_000));
    }

    #[test]
    fn adding_a_duration_moves_forward() {
        let start = Timestamp::from_millis(1_000);

        assert_eq!(start + Duration::from_millis(250), Timestamp::from_millis(1_250));
        assert_eq!((start + Duration::from_secs(2)).as_millis(), 3_000);
    }

    #[test]
    fn elapsed_between_two_moments() {
        let (start, end) = (Timestamp::from_millis(1_000), Timestamp::from_millis(4_500));

        assert_eq!(end.since(start), Some(Duration::from_millis(3_500)));
        assert_eq!(start.since(end), None);
    }
}
