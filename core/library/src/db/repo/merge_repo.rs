use plinth_types::{CoreError, EntityId};
use rusqlite::{Row, params};

use crate::db::Database;
use crate::db::codes::{Code, column, opt_column};
use crate::db::sql::{Storage, id, invalid, timestamp};
use crate::model::{DecidedBy, MergeDecision, TrackPair};

impl Database {
    /// Решение о склейке; `false` — такое уже есть. Решения неизменны и
    /// только добавляются (B1.4), поэтому повтор ничего не переписывает.
    pub fn save_merge_decision(&self, decision: &MergeDecision) -> Result<bool, CoreError> {
        let (low, high) = decision.pair.tracks();
        let (by_user, basis, confidence) = match decision.by {
            DecidedBy::User => (true, None, None),
            DecidedBy::Auto { basis, confidence } => (false, Some(basis.code()), Some(f64::from(confidence))),
        };
        self.conn()
            .execute(
                "INSERT INTO merge_decision(id, track_low, track_high, verdict, by_user, basis, confidence, decided_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
                 ON CONFLICT(id) DO NOTHING",
                params![
                    decision.id.as_bytes(),
                    low.as_bytes(),
                    high.as_bytes(),
                    decision.verdict.code(),
                    by_user,
                    basis,
                    confidence,
                    decision.decided_at.as_millis()
                ],
            )
            .storage()
            .map(|inserted| inserted == 1)
    }

    /// Все решения по времени; какое действует — говорит `MergeDecision::supersedes`.
    pub fn merge_decisions(&self) -> Result<Vec<MergeDecision>, CoreError> {
        let mut statement = self.conn().prepare("SELECT * FROM merge_decision ORDER BY decided_at, id").storage()?;
        statement.query_map([], read_decision).storage()?.collect::<Result<_, _>>().storage()
    }
}

fn read_decision(row: &Row<'_>) -> rusqlite::Result<MergeDecision> {
    let by = if row.get("by_user")? {
        DecidedBy::User
    } else {
        let basis = opt_column(row, "basis")?.ok_or_else(|| invalid("basis", "NULL"))?;
        let confidence: f64 = row.get("confidence")?;
        #[expect(clippy::cast_possible_truncation, reason = "уверенность от 0 до 1 записана из f32")]
        let confidence = confidence as f32;
        DecidedBy::Auto { basis, confidence }
    };
    let pair = TrackPair::new(id(row, "track_low")?, id(row, "track_high")?)
        .map_err(|_| invalid("track_high", "same track as track_low"))?;
    Ok(MergeDecision {
        id: id(row, "id")?,
        pair,
        verdict: column(row, "verdict")?,
        by,
        decided_at: timestamp(row, "decided_at")?,
    })
}

#[cfg(test)]
mod tests {
    use plinth_types::{MergeDecisionId, Timestamp, TrackId};

    use crate::db::repo::fixtures::db;
    use crate::model::{DecidedBy, IdentityBasis, MergeDecision, TrackPair, Verdict};

    fn decision(by: DecidedBy, at: i64) -> MergeDecision {
        MergeDecision {
            id: MergeDecisionId::new(),
            pair: TrackPair::new(TrackId::new(), TrackId::new()).unwrap(),
            verdict: Verdict::Split,
            by,
            decided_at: Timestamp::from_millis(at),
        }
    }

    #[test]
    fn decisions_round_trip_in_time_order() {
        let db = db();
        let auto = DecidedBy::Auto { basis: IdentityBasis::Fingerprint, confidence: 0.75 };
        let (late, early) = (decision(DecidedBy::User, 2_000), decision(auto, 1_000));

        assert!(db.save_merge_decision(&late).unwrap());
        assert!(db.save_merge_decision(&early).unwrap());

        assert_eq!(db.merge_decisions().unwrap(), vec![early, late]);
    }

    /// Решения только добавляются: повтор той же записи ничего не меняет.
    #[test]
    fn a_decision_is_saved_once() {
        let db = db();
        let first = decision(DecidedBy::User, 1_000);

        assert!(db.save_merge_decision(&first).unwrap());
        assert!(!db.save_merge_decision(&MergeDecision { verdict: Verdict::Merge, ..first }).unwrap());

        assert_eq!(db.merge_decisions().unwrap(), vec![first]);
    }
}
