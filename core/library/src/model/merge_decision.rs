//! Склейка и разъединение треков (B1.4, plan.md 4.2). **Обратимость** —
//! фундамент безопасности библиотеки: решения только добавляются, ни одно
//! не стирается. Разъединить — значит принять новое решение поверх
//! склейки. Действует последнее решение по паре (`supersedes`), а решение
//! человека автомат не отменяет. Как из цепочки решений получаются группы
//! треков — склейка, v0.3. Живёт в журнале.

use plinth_types::{CoreError, MergeDecisionId, Timestamp, TrackId};

/// Пара треков без порядка: (a, b) и (b, a) — одна пара.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct TrackPair {
    low: TrackId,
    high: TrackId,
}

impl TrackPair {
    pub fn new(a: TrackId, b: TrackId) -> Result<Self, CoreError> {
        match a.cmp(&b) {
            std::cmp::Ordering::Less => Ok(Self { low: a, high: b }),
            std::cmp::Ordering::Greater => Ok(Self { low: b, high: a }),
            std::cmp::Ordering::Equal => Err(CoreError::parse(format!("track {a} paired with itself"))),
        }
    }

    pub fn tracks(self) -> (TrackId, TrackId) {
        (self.low, self.high)
    }

    pub fn contains(self, track: TrackId) -> bool {
        self.low == track || self.high == track
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Verdict {
    /// Это одна песня.
    Merge,
    /// Это разные песни.
    Split,
}

/// Уровень идентичности, по которому решил автомат (plan.md 4.2).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum IdentityBasis {
    /// Золотой — MBID совпал.
    Mbid,
    /// Серебряный — акустический отпечаток.
    Fingerprint,
    /// Бронзовый — нормализованные «артист + название» и длительность ±3 с.
    Normalized,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum DecidedBy {
    User,
    /// `confidence` — от 0 до 1. Спорные случаи автомат не решает, а кладёт в очередь человеку.
    Auto {
        basis: IdentityBasis,
        confidence: f32,
    },
}

impl DecidedBy {
    fn is_user(self) -> bool {
        self == Self::User
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct MergeDecision {
    pub id: MergeDecisionId,
    pub pair: TrackPair,
    pub verdict: Verdict,
    pub by: DecidedBy,
    pub decided_at: Timestamp,
}

impl MergeDecision {
    /// Отменяет ли это решение `other`. Только про одну пару. Человек
    /// сильнее автомата. Среди равных побеждает более позднее, а при равном
    /// времени — больший идентификатор, одинаково на всех устройствах.
    pub fn supersedes(&self, other: &Self) -> bool {
        if self.pair != other.pair || self.id == other.id {
            return false;
        }
        match (self.by.is_user(), other.by.is_user()) {
            (true, false) => true,
            (false, true) => false,
            _ => (self.decided_at, self.id) > (other.decided_at, other.id),
        }
    }
}

#[cfg(test)]
mod tests {
    use plinth_types::{CoreError, MergeDecisionId, Timestamp, TrackId};

    use super::{DecidedBy, IdentityBasis, MergeDecision, TrackPair, Verdict};

    fn decision(pair: TrackPair, verdict: Verdict, by: DecidedBy, at: i64) -> MergeDecision {
        MergeDecision { id: MergeDecisionId::new(), pair, verdict, by, decided_at: Timestamp::from_millis(at) }
    }

    fn auto(confidence: f32) -> DecidedBy {
        DecidedBy::Auto { basis: IdentityBasis::Fingerprint, confidence }
    }

    #[test]
    fn pair_is_the_same_whichever_way_it_is_named() {
        let (a, b) = (TrackId::new(), TrackId::new());

        assert_eq!(TrackPair::new(a, b), TrackPair::new(b, a));
        assert!(TrackPair::new(a, b).unwrap().contains(a));
    }

    #[test]
    fn a_track_is_not_a_pair_with_itself() {
        let a = TrackId::new();

        assert!(matches!(TrackPair::new(a, a), Err(CoreError::Parse { .. })));
    }

    /// Обратимость: разъединение — новое решение поверх склейки, склейка не стирается.
    #[test]
    fn later_decision_on_the_pair_wins() {
        let pair = TrackPair::new(TrackId::new(), TrackId::new()).unwrap();
        let merged = decision(pair, Verdict::Merge, DecidedBy::User, 1_000);
        let split = decision(pair, Verdict::Split, DecidedBy::User, 2_000);

        assert!(split.supersedes(&merged));
        assert!(!merged.supersedes(&split));
    }

    /// Автомат не отменяет решение человека, даже если принят позже.
    #[test]
    fn user_decision_beats_a_later_automatic_one() {
        let pair = TrackPair::new(TrackId::new(), TrackId::new()).unwrap();
        let by_user = decision(pair, Verdict::Split, DecidedBy::User, 1_000);
        let by_scan = decision(pair, Verdict::Merge, auto(0.99), 5_000);

        assert!(by_user.supersedes(&by_scan));
        assert!(!by_scan.supersedes(&by_user));
    }

    #[test]
    fn decisions_on_different_pairs_do_not_compete() {
        let one = TrackPair::new(TrackId::new(), TrackId::new()).unwrap();
        let other = TrackPair::new(TrackId::new(), TrackId::new()).unwrap();

        let first = decision(one, Verdict::Merge, DecidedBy::User, 1_000);
        let second = decision(other, Verdict::Split, DecidedBy::User, 2_000);

        assert!(!second.supersedes(&first));
    }

    /// Одновременные решения на двух устройствах: победитель один и тот же везде.
    #[test]
    fn same_moment_is_broken_by_id_the_same_way_everywhere() {
        let pair = TrackPair::new(TrackId::new(), TrackId::new()).unwrap();
        let x = decision(pair, Verdict::Merge, DecidedBy::User, 1_000);
        let y = decision(pair, Verdict::Split, DecidedBy::User, 1_000);

        assert_ne!(x.supersedes(&y), y.supersedes(&x));
    }
}
