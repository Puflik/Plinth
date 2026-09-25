//! Полная пересборка проекции из журнала (C3.2) — механизм восстановления
//! после любого повреждения базы: удалили, испортили, миграция проекции
//! оказалась слишком сложной (ADR 0011). Каталог не трогается — его
//! возвращает скан.

use std::collections::{BTreeMap, HashSet};

use plinth_library::db::{Database, JournalMark};
use plinth_library::model::TrackUserData;
use plinth_types::{CoreError, PlaylistId, TrackId};

use super::apply::count;
use super::{Journal, JournalState};

/// Стирает таблицы проекции и собирает их из журнала одной транзакцией:
/// сбой посреди — база остаётся прежней.
pub fn rebuild(journal: &Journal, db: &Database) -> Result<(), CoreError> {
    let (state, mark) = (journal.state(), journal.mark());
    db.in_transaction(|db| write(db, &state, mark))
}

/// Пересборка внутри уже открытой транзакции.
pub(crate) fn write(db: &Database, state: &JournalState, mark: JournalMark) -> Result<(), CoreError> {
    db.clear_projection()?;

    let mut users: BTreeMap<TrackId, TrackUserData> = BTreeMap::new();
    for track in &state.likes {
        user(&mut users, *track).liked = true;
    }
    for (track, rating) in &state.ratings {
        user(&mut users, *track).rating = Some(*rating);
    }
    for play in &state.plays {
        // Повтор той же записи (одно прослушивание, пришедшее дважды) не считается.
        if db.record_play(play)? {
            count(user(&mut users, play.track), play);
        }
    }
    for data in users.values() {
        db.save_user_data(data)?;
    }

    for playlist in &state.playlists {
        db.save_playlist(playlist)?;
    }
    // Запись без плейлиста: его удалили на одном устройстве, пока на другом
    // в него добавляли. В журнале она остаётся, в базу — нет.
    let playlists: HashSet<PlaylistId> = state.playlists.iter().map(|p| p.id).collect();
    let (entries, orphans): (Vec<_>, Vec<_>) = state.entries.iter().partition(|e| playlists.contains(&e.playlist));
    for entry in entries {
        db.put_entry(entry)?;
    }

    for decision in &state.decisions {
        db.save_merge_decision(decision)?;
    }
    for subscription in &state.subscriptions {
        db.subscribe(subscription)?;
    }
    for entry in &state.blocklist {
        db.block(entry)?;
    }
    for setting in &state.settings {
        db.save_setting(*setting)?;
    }
    db.set_journal_mark(mark)?;

    log::info!(
        "projection rebuilt: {} plays, {} tracks with user data, {} playlists; skipped {} orphan entries, {} unreadable records",
        state.plays.len(),
        users.len(),
        state.playlists.len(),
        orphans.len(),
        state.unreadable
    );
    Ok(())
}

fn user(users: &mut BTreeMap<TrackId, TrackUserData>, track: TrackId) -> &mut TrackUserData {
    users.entry(track).or_insert_with(|| TrackUserData::empty(track))
}
