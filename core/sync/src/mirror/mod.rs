//! Копия журнала в папке человека (C4, план 17.5, уровень 1) — главный
//! способ пережить переустановку: без Google-аккаунта и системной копии.
//!
//! Файлы пишет и читает Kotlin через SAF: ядро отдаёт копию байтами
//! ([`copy_of`]), осматривает найденные ([`inspect`]) и вливает их в журнал
//! ([`restore`]). Слияние — CRDT: сделанное до восстановления не теряется, а
//! одна и та же копия, влитая дважды, ничего не меняет.

mod format;

use plinth_library::db::Database;
use plinth_types::{CoreError, Timestamp};

pub use format::{Copy, decode, encode};

use crate::journal::{Journal, catch_up, check_snapshot, relink, state_of};

/// Что лежит в найденных копиях — для вопроса «Восстановить?».
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Found {
    pub likes: usize,
    pub playlists: usize,
    pub plays: usize,
    /// Время самой свежей копии; прочитанных нет — `None`.
    pub written_at: Option<Timestamp>,
    /// В копиях есть то, чего журнал не знает, — есть что восстанавливать.
    pub news: bool,
    /// Файлы, которые не прочлись: испорчены, обрезаны или не копии.
    pub unreadable: usize,
}

/// Итог [`restore`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Restored {
    /// Копии, принёсшие новое.
    pub merged: usize,
    pub unreadable: usize,
    /// Треки каталога, перешедшие на ID журнала (`relink`).
    pub relinked: usize,
}

/// Копия журнала для файла, записанная в `written_at`.
pub fn copy_of(journal: &Journal, written_at: Timestamp) -> Vec<u8> {
    encode(&journal.snapshot(), written_at)
}

/// Осматривает копии `files`: что в них вместе и есть ли новое для `journal`.
pub fn inspect(journal: &Journal, files: &[&[u8]]) -> Result<Found, CoreError> {
    let (copies, unreadable) = readable(files);
    let snapshots: Vec<&[u8]> = copies.iter().map(|copy| copy.snapshot).collect();
    let state = state_of(&snapshots)?;
    let mut news = false;
    for snapshot in &snapshots {
        news |= journal.news_in(snapshot)?;
    }
    Ok(Found {
        likes: state.likes.len(),
        playlists: state.playlists.len(),
        plays: state.plays.len(),
        written_at: copies.iter().map(|copy| copy.written_at).max(),
        news,
        unreadable,
    })
}

/// Вливает копии `files` в журнал, пересобирает проекцию и переводит треки
/// каталога на ID журнала. Испорченные копии пропускаются и считаются.
pub fn restore(journal: &mut Journal, db: &Database, files: &[&[u8]]) -> Result<Restored, CoreError> {
    let (copies, unreadable) = readable(files);
    let mut merged = 0;
    for copy in &copies {
        if journal.merge_snapshot(copy.snapshot)? {
            merged += 1;
        }
    }
    catch_up(journal, db)?;
    let relinked = relink(journal, db)?;
    log::info!(
        "mirror: restored {merged} of {} copies, {unreadable} unreadable, {relinked} tracks relinked",
        files.len()
    );
    Ok(Restored { merged, unreadable, relinked })
}

/// Прочитанные копии и сколько файлов не прочлось.
fn readable<'a>(files: &[&'a [u8]]) -> (Vec<Copy<'a>>, usize) {
    let mut copies = Vec::with_capacity(files.len());
    let mut unreadable = 0;
    for file in files {
        match decode(file).and_then(|copy| check_snapshot(copy.snapshot).map(|()| copy)) {
            Ok(copy) => copies.push(copy),
            Err(error) => {
                log::warn!("mirror: skipped a file: {error}");
                unreadable += 1;
            }
        }
    }
    (copies, unreadable)
}
