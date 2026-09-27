//! Перепривязка (C4, ADR 0007): трек журнала, которого нет в каталоге,
//! узнаётся по паспорту среди треков, на которые у журнала данных нет, и
//! каталог берёт его ID из журнала. Так после переустановки и нового скана
//! лайки, плейлисты и история снова на своих треках.
//!
//! Сравнение: нормализованные название и исполнитель совпадают; MBID, если
//! известен у обоих, — тоже; длительность, если известна у обоих, — с
//! точностью до двух секунд. Среди подходящих лучше тот, у кого совпал
//! MBID, потом альбом, потом длительность.

use std::collections::{BTreeSet, HashMap};
use std::time::Duration;

use plinth_library::db::Database;
use plinth_library::model::{OnlineTrack, TrackPassport};
use plinth_library::text::normalize;
use plinth_types::{CoreError, Timestamp, TrackId};

use super::Journal;
use super::passport::referenced;

/// Разница длительности одной песни в разных файлах: кодировщики по-разному
/// считают тишину в начале и конце.
const DURATION_TOLERANCE: Duration = Duration::from_secs(2);

/// Переводит треки каталога на ID журнала по паспортам; сколько переведено.
/// Одной транзакцией базы; журнал не меняется.
pub fn relink(journal: &Journal, db: &Database) -> Result<usize, CoreError> {
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
    for track in catalog.into_iter().filter(|p| !claimed.contains(&p.track)) {
        free.entry(key(&track)).or_default().push(track);
    }
    let now = Timestamp::now();
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
            }
        }
        Ok((moved, restored))
    })?;
    log::info!(
        "relink: {moved} of {} tracks of the journal found in the catalog, {restored} online tracks restored",
        state.passports.len()
    );
    Ok(moved + restored)
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
