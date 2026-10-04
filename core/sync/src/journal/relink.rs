//! Перепривязка (C4, ADR 0007): трек журнала, которого нет в каталоге,
//! узнаётся по паспорту среди треков, на которые у журнала данных нет, и
//! каталог берёт его ID из журнала. Так после переустановки и нового скана
//! лайки, плейлисты и история снова на своих треках.
//!
//! Сравнение: нормализованные название и исполнитель совпадают; MBID, если
//! известен у обоих, — тоже; длительность, если известна у обоих, — с
//! точностью до двух секунд. Среди подходящих лучше тот, у кого совпал
//! MBID, потом альбом, потом длительность.
//!
//! Трек, на который новая установка успела поставить свои данные до
//! перепривязки (нажали «играть» посреди скана), тоже узнаётся: каталог берёт
//! ID из журнала, а данные новой установки перекладываются на него (ревью
//! v0.2, №1). Иначе данные старой остались бы на ID, которого в каталоге нет.

use std::collections::{BTreeSet, HashMap};
use std::time::Duration;

use plinth_library::db::Database;
use plinth_library::model::{OnlineTrack, PlayEvent, PlaylistEntry, TrackPassport};
use plinth_library::text::normalize;
use plinth_types::{CoreError, PlayEventId, PlaylistEntryId, Timestamp, TrackId};

use super::passport::referenced;
use super::projection::record_and_project_all;
use super::{Journal, JournalState, Op};

/// Разница длительности одной песни в разных файлах: кодировщики по-разному
/// считают тишину в начале и конце.
const DURATION_TOLERANCE: Duration = Duration::from_secs(2);

/// Переводит треки каталога на ID журнала по паспортам; сколько переведено.
/// Перевод на ID журнала — одной транзакцией базы; журнал меняется только при
/// слиянии с треком, у которого уже есть данные новой установки.
pub fn relink(journal: &mut Journal, db: &Database) -> Result<usize, CoreError> {
    let state = journal.state();
    let catalog = db.track_passports()?;
    let known: BTreeSet<TrackId> = catalog.iter().map(|p| p.track).collect();
    let lost: Vec<&TrackPassport> = state.passports.iter().filter(|p| !known.contains(&p.track)).collect();
    if lost.is_empty() {
        return Ok(0);
    }
    let mut claimed = referenced(&state);
    claimed.extend(state.passports.iter().map(|p| p.track));
    let mut free: HashMap<(String, String), Vec<TrackPassport>> = HashMap::new();
    let mut held: HashMap<(String, String), Vec<TrackPassport>> = HashMap::new();
    for track in catalog {
        let side = if claimed.contains(&track.track) { &mut held } else { &mut free };
        side.entry(key(&track)).or_default().push(track);
    }
    let now = Timestamp::now();
    let mut unmatched: Vec<TrackPassport> = Vec::new();
    let (moved, restored) = db.in_transaction(|db| {
        let (mut moved, mut restored) = (0, 0);
        for passport in lost {
            let found = free
                .get_mut(&key(passport))
                .and_then(|candidates| best(passport, candidates).map(|index| candidates.remove(index)));
            if let Some(found) = found {
                db.rekey_track(found.track, passport.track)?;
                moved += 1;
            } else if db.restore_online_track(passport.track, &online(passport), now)? {
                restored += 1;
            } else if passport.sources.is_empty() {
                unmatched.push(passport.clone());
            }
        }
        Ok((moved, restored))
    })?;
    let mut merged = 0;
    for passport in unmatched {
        // Только трек новее потерянного ID: ID растут со временем создания,
        // и слияние идёт в одну сторону — повторный вызов не развернёт его.
        let found = held.get_mut(&key(&passport)).and_then(|candidates| {
            let newer: Vec<TrackPassport> =
                candidates.iter().filter(|candidate| candidate.track > passport.track).cloned().collect();
            let found = best(&passport, &newer).map(|index| newer[index].clone())?;
            candidates.retain(|candidate| candidate.track != found.track);
            Some(found)
        });
        if let Some(found) = found {
            fold(journal, db, found.track, passport.track)?;
            merged += 1;
        }
    }
    log::info!(
        "relink: {moved} of {} tracks of the journal found in the catalog, {merged} merged with tracks that already had data, {restored} online tracks restored",
        state.passports.len()
    );
    Ok(moved + merged + restored)
}

/// Трек `from` каталога уже получил данные этой установки, а журнал помнит
/// ту же песню под `to`. Данные `from` кладутся на `to` (лайк объединяется,
/// оценка и плейлисты `from` новее и побеждают, прослушивания копируются), с
/// `from` снимаются, и каталог переводится на `to`. Сначала журнал, потом
/// каталог: оборванный повтор ничего не задвоит (прослушивание и запись
/// плейлиста с теми же признаками не добавляются второй раз).
fn fold(journal: &mut Journal, db: &Database, from: TrackId, to: TrackId) -> Result<(), CoreError> {
    let ops = fold_ops(&journal.state(), from, to);
    if !ops.is_empty() {
        record_and_project_all(journal, db, &ops)?;
    }
    db.rekey_track(from, to)
}

fn fold_ops(state: &JournalState, from: TrackId, to: TrackId) -> Vec<Op> {
    let mut ops = Vec::new();
    if state.likes.contains(&from) {
        ops.push(Op::Like { track: to });
        ops.push(Op::Unlike { track: from });
    }
    if let Some((_, rating)) = state.ratings.iter().find(|(track, _)| *track == from) {
        ops.push(Op::Rate { track: to, rating: Some(*rating) });
        ops.push(Op::Rate { track: from, rating: None });
    }
    for entry in state.entries.iter().filter(|entry| entry.track == from) {
        let there = state.entries.iter().any(|other| other.playlist == entry.playlist && other.track == to);
        if !there {
            ops.push(Op::AddEntry(PlaylistEntry { id: PlaylistEntryId::new(), track: to, ..entry.clone() }));
        }
        ops.push(Op::RemoveEntry { entry: entry.id });
    }
    for play in state.plays.iter().filter(|play| play.track == from) {
        let there = state
            .plays
            .iter()
            .any(|other| other.track == to && other.started_at == play.started_at && other.listened == play.listened);
        if !there {
            ops.push(Op::Play(PlayEvent { id: PlayEventId::new(), track: to, ..*play }));
        }
    }
    ops
}

/// Сетевой трек по паспорту (E3): его варианты у провайдера и описание.
/// Без вариантов — трек устройства, заводить нечего.
fn online(passport: &TrackPassport) -> OnlineTrack {
    OnlineTrack {
        title: passport.title.clone(),
        artist: Some(passport.artist.clone()).filter(|artist| !artist.is_empty()),
        album: passport.album.clone(),
        number: None,
        year: None,
        duration: passport.duration,
        mbid: passport.mbid,
        sources: passport.sources.clone(),
    }
}

fn key(passport: &TrackPassport) -> (String, String) {
    (normalize(&passport.title), normalize(&passport.artist))
}

/// Лучший из подходящих; при равенстве — первый.
fn best(passport: &TrackPassport, candidates: &[TrackPassport]) -> Option<usize> {
    let mut best: Option<(u8, usize)> = None;
    for (index, candidate) in candidates.iter().enumerate() {
        if let Some(score) = score(passport, candidate)
            && best.is_none_or(|(top, _)| score > top)
        {
            best = Some((score, index));
        }
    }
    best.map(|(_, index)| index)
}

/// Насколько `candidate` похож на `passport`; `None` — это другой трек.
fn score(passport: &TrackPassport, candidate: &TrackPassport) -> Option<u8> {
    let mut score = 0;
    if let (Some(a), Some(b)) = (passport.mbid, candidate.mbid) {
        if a != b {
            return None;
        }
        score += 4;
    }
    if let (Some(a), Some(b)) = (&passport.album, &candidate.album)
        && normalize(a) == normalize(b)
    {
        score += 2;
    }
    if let (Some(a), Some(b)) = (passport.duration, candidate.duration) {
        if a.abs_diff(b) > DURATION_TOLERANCE {
            return None;
        }
        score += 1;
    }
    Some(score)
}
