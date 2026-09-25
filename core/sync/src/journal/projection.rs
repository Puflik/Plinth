//! Инкрементальная проекция (C3.1): операция сначала в журнал, потом в базу.
//!
//! База помнит, какое состояние журнала она отражает, — метку
//! [`JournalMark`] в той же транзакции, что и изменения. Метка совпала с
//! журналом до операции — достаточно применить одну операцию. Не совпала
//! (сбой между журналом и базой, база новая или восстановленная, журнал
//! слит с чужим) — база пересобирается целиком. Пересборка дешёвая: сотни
//! миллисекунд на годы истории, и случается редко.
//!
//! [`JournalMark`]: plinth_library::db::JournalMark

use plinth_library::db::Database;
use plinth_types::CoreError;

use super::apply::apply;
use super::rebuild::{rebuild, write};
use super::{Journal, Op};

/// Что сделал [`catch_up`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CatchUp {
    UpToDate,
    Rebuilt,
}

/// Записывает операцию в журнал и отражает её в базе. Журнал — первым: если
/// до базы операция не дойдёт, её вернёт пересборка, а наоборот — нечем.
pub fn record_and_project(journal: &mut Journal, db: &Database, op: &Op) -> Result<(), CoreError> {
    let before = journal.mark();
    if journal.record(op)?.is_none() {
        return Ok(());
    }
    let journal: &Journal = journal;
    let after = journal.mark();
    db.in_transaction(|db| {
        if db.journal_mark()? == Some(before) {
            apply(db, op)?;
            db.set_journal_mark(after)
        } else {
            log::info!("projection is behind the journal: rebuilding");
            write(db, &journal.state(), after)
        }
    })
}

/// Сверка при старте: база отражает не то состояние журнала — пересобрать.
pub fn catch_up(journal: &Journal, db: &Database) -> Result<CatchUp, CoreError> {
    if db.journal_mark()? == Some(journal.mark()) {
        return Ok(CatchUp::UpToDate);
    }
    rebuild(journal, db)?;
    Ok(CatchUp::Rebuilt)
}
