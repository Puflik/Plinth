//! Паспорта треков в журнале (C4, ADR 0007). ID каталога не выводятся из
//! тегов: после переустановки скан выдаст файлам новые ID. Поэтому трек, на
//! который есть пользовательские данные, журнал описывает — название,
//! исполнитель, альбом, длительность, MBID — и по описанию узнаёт его в
//! новом каталоге (`relink.rs`).

use std::collections::BTreeSet;

use plinth_library::db::Database;
use plinth_library::model::BlockTarget;
use plinth_types::{CoreError, TrackId};

use super::projection::record_and_project_all;
use super::{Journal, JournalState, Op};

/// Паспорта треков каталога, которых касаются `ops`, если в журнале их нет
/// или теги с тех пор изменились. Снятие лайка или оценки не описывает.
pub(crate) fn describe(journal: &Journal, db: &Database, ops: &[Op]) -> Result<Vec<Op>, CoreError> {
    let tracks: BTreeSet<TrackId> = ops.iter().flat_map(tracks_of).collect();
    let mut described = Vec::new();
    for track in tracks {
        if let Some(passport) = db.track_passport(track)?
            && journal.passport(track).as_ref() != Some(&passport)
        {
            described.push(Op::Describe(passport));
        }
    }
    Ok(described)
}

/// Дописывает паспорта трекам каталога, на которые в журнале есть данные, а
/// паспорта нет (журнал до C4), — одной правкой. Сколько дописано.
pub fn describe_missing(journal: &mut Journal, db: &Database) -> Result<usize, CoreError> {
    let state = journal.state();
    let described: BTreeSet<TrackId> = state.passports.iter().map(|p| p.track).collect();
    let mut ops = Vec::new();
    for track in referenced(&state).difference(&described) {
        if let Some(passport) = db.track_passport(*track)? {
            ops.push(Op::Describe(passport));
        }
    }
    if !ops.is_empty() {
        record_and_project_all(journal, db, &ops)?;
    }
    Ok(ops.len())
}

/// Треки, на которые в журнале есть данные.
pub(crate) fn referenced(state: &JournalState) -> BTreeSet<TrackId> {
    let mut tracks: BTreeSet<TrackId> = state.likes.iter().copied().collect();
    tracks.extend(state.ratings.iter().map(|(track, _)| *track));
    tracks.extend(state.entries.iter().map(|entry| entry.track));
    tracks.extend(state.plays.iter().map(|play| play.track));
    tracks.extend(state.decisions.iter().flat_map(|decision| <[TrackId; 2]>::from(decision.pair.tracks())));
    tracks.extend(state.blocklist.iter().filter_map(|entry| match entry.target {
        BlockTarget::Track(track) => Some(track),
        BlockTarget::Artist(_) => None,
    }));
    tracks
}

fn tracks_of(op: &Op) -> Vec<TrackId> {
    match op {
        Op::Like { track } | Op::Rate { track, rating: Some(_) } => vec![*track],
        Op::AddEntry(entry) => vec![entry.track],
        Op::Play(play) => vec![play.track],
        Op::Decide(decision) => <[TrackId; 2]>::from(decision.pair.tracks()).to_vec(),
        Op::Block(entry) => match entry.target {
            BlockTarget::Track(track) => vec![track],
            BlockTarget::Artist(_) => Vec::new(),
        },
        _ => Vec::new(),
    }
}
