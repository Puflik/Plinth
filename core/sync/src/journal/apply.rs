//! Одна операция журнала → таблицы проекции (C3.1). Результат обязан
//! совпадать с пересборкой (`rebuild.rs`) того же состояния журнала — это
//! проверяет `tests/projection.rs`.

use plinth_library::db::Database;
use plinth_library::model::{PlayEvent, TrackUserData};
use plinth_types::{CoreError, TrackId};

use super::op::Op;

/// Применяет к базе операцию, которая только что изменила журнал.
pub(crate) fn apply(db: &Database, op: &Op) -> Result<(), CoreError> {
    match op {
        Op::Like { track } => update_user(db, *track, |data| data.liked = true),
        Op::Unlike { track } => update_user(db, *track, |data| data.liked = false),
        Op::Rate { track, rating } => update_user(db, *track, |data| data.rating = *rating),
        Op::CreatePlaylist(playlist) => db.save_playlist(playlist),
        Op::RenamePlaylist { playlist, name } => db.rename_playlist(*playlist, name),
        Op::DeletePlaylist { playlist } => db.delete_playlist(*playlist),
        Op::AddEntry(entry) => db.put_entry(entry),
        Op::MoveEntry { entry, position } => db.move_entry(*entry, position),
        Op::RemoveEntry { entry } => db.remove_entry(*entry),
        Op::Play(play) => {
            if db.record_play(play)? {
                update_user(db, play.track, |data| count(data, play))?;
            }
            Ok(())
        }
        Op::Decide(decision) => db.save_merge_decision(decision).map(drop),
        Op::Subscribe(subscription) => db.subscribe(subscription),
        Op::Unsubscribe { artist } => db.unsubscribe(*artist),
        Op::Block(entry) => db.block(entry),
        Op::Unblock { target } => db.unblock(*target),
        Op::Set(setting) => db.save_setting(*setting),
    }
}

/// Засчитанное прослушивание (правило — `PlayEvent::counts`) идёт в
/// счётчик и в «последнее прослушивание»; остальные — только в историю.
pub(crate) fn count(data: &mut TrackUserData, play: &PlayEvent) {
    if play.counts() {
        data.play_count = data.play_count.saturating_add(1);
        data.last_played_at = data.last_played_at.max(Some(play.started_at));
    }
}

fn update_user(db: &Database, track: TrackId, change: impl FnOnce(&mut TrackUserData)) -> Result<(), CoreError> {
    let mut data = db.user_data(track)?;
    change(&mut data);
    db.save_user_data(&data)
}
