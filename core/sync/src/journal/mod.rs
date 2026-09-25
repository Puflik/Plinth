//! Журнал пользовательских данных на `yrs` (C2, ADR 0006 и 0007).
//!
//! [`Journal`] — документ CRDT в памяти и его файлы на диске. Операция
//! ([`Op`]) ложится в документ одной транзакцией (`doc.rs`), обновление этой
//! транзакции — кадром в хвост файла (`store.rs`). Проекция в базу — C3
//! (`apply.rs`, `rebuild.rs`, `projection.rs`).

mod apply;
mod codec;
mod doc;
pub mod op;
pub mod op_meta;
mod projection;
mod rebuild;
mod store;

use std::path::Path;

use plinth_library::db::JournalMark;
use plinth_types::{CoreError, DeviceId};
use yrs::updates::decoder::Decode;
use yrs::updates::encoder::Encode;
use yrs::{Doc, ReadTxn, StateVector, Transact, TransactionMut, Update};

pub use doc::JournalState;
use doc::Roots;
pub use op::Op;
pub use op_meta::{OP_SCHEMA, OpMeta};
pub use projection::{CatchUp, catch_up, record_and_project};
pub use rebuild::rebuild;
use store::Store;

pub struct Journal {
    doc: Doc,
    roots: Roots,
    store: Store,
    device: DeviceId,
}

impl Journal {
    /// Открывает журнал в каталоге `dir`, создавая новый, если его там нет.
    /// `device` — эта установка приложения, автор новых операций.
    ///
    /// Идентификатор клиента `yrs` новый на каждое открытие, как в Yjs: так
    /// две реплики не получат один идентификатор, даже если журнал
    /// восстановлен из копии на другом телефоне. Постоянный автор — в
    /// метаданных операции.
    pub fn open(dir: &Path, device: DeviceId) -> Result<Self, CoreError> {
        let (store, loaded) = Store::open(dir)?;
        let doc = Doc::new();
        let roots = Roots::new(&doc);
        {
            let mut txn = doc.transact_mut();
            if !loaded.snapshot.is_empty() {
                let update = Update::decode_v2(&loaded.snapshot).map_err(|e| unreadable("snapshot", &e))?;
                txn.apply_update(update).map_err(|e| unreadable("snapshot", &e))?;
            }
            for (index, frame) in loaded.frames.iter().enumerate() {
                // Сумма кадра сошлась, значит, байты те, что записаны: не
                // разбирается — ошибка кода, не диска. Кадр пропускается.
                if let Err(error) = apply_v1(&mut txn, frame) {
                    log::error!("journal: frame {index} is not an update: {error}");
                }
            }
        }
        let mut journal = Self { doc, roots, store, device };
        if loaded.repair {
            journal.compact()?;
        }
        Ok(journal)
    }

    pub fn device(&self) -> DeviceId {
        self.device
    }

    /// Какое состояние журнала сейчас: меняется с каждой правкой.
    pub fn mark(&self) -> JournalMark {
        JournalMark { journal: self.store.journal_id(), seq: i64::try_from(self.store.seq()).unwrap_or(i64::MAX) }
    }

    /// Записывает операцию; `None` — она ничего не меняет, и журнал не
    /// тронут. Ошибка записи на диск — правка осталась в памяти, следующая
    /// запишет её вместе с собой.
    pub fn record(&mut self, op: &Op) -> Result<Option<OpMeta>, CoreError> {
        let meta = OpMeta::now(self.device);
        let update = {
            let mut txn = self.doc.transact_mut();
            if !self.roots.write(&mut txn, op, &meta)? {
                return Ok(None);
            }
            txn.encode_update_v1()
        };
        let doc = &self.doc;
        self.store.persist(&update, || snapshot(doc))?;
        Ok(Some(meta))
    }

    pub fn state(&self) -> JournalState {
        self.roots.state(&self.doc.transact())
    }

    /// Что журнал уже знает — для обмена с другим журналом (C4, v1.5).
    pub fn state_vector(&self) -> Vec<u8> {
        self.doc.transact().state_vector().encode_v1()
    }

    /// Всё, чего не знает журнал с вектором `state_vector`, — update v1.
    pub fn updates_since(&self, state_vector: &[u8]) -> Result<Vec<u8>, CoreError> {
        let known = StateVector::decode_v1(state_vector).map_err(|e| unreadable("state vector", &e))?;
        Ok(self.doc.transact().encode_state_as_update_v1(&known))
    }

    /// Вливает `update` из [`Journal::updates_since`] другого журнала;
    /// `false` — ничего нового. Проекцию после слияния пересобирают.
    pub fn merge(&mut self, update: &[u8]) -> Result<bool, CoreError> {
        let changed = {
            let mut txn = self.doc.transact_mut();
            apply_v1(&mut txn, update)?;
            !(txn.insert_set().is_empty() && txn.delete_set().is_empty())
        };
        if changed {
            let doc = &self.doc;
            self.store.persist(update, || snapshot(doc))?;
        }
        Ok(changed)
    }

    /// Сворачивает хвост в снимок, не дожидаясь его длины.
    pub fn compact(&mut self) -> Result<(), CoreError> {
        self.store.compact(&snapshot(&self.doc))
    }
}

fn snapshot(doc: &Doc) -> Vec<u8> {
    doc.transact().encode_state_as_update_v2(&StateVector::default())
}

fn apply_v1(txn: &mut TransactionMut, update: &[u8]) -> Result<(), CoreError> {
    let update = Update::decode_v1(update).map_err(|e| unreadable("update", &e))?;
    txn.apply_update(update).map_err(|e| unreadable("update", &e))
}

fn unreadable(what: &str, error: &impl std::fmt::Display) -> CoreError {
    CoreError::storage(format!("journal: unreadable {what}: {error}"))
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::PathBuf;

    use plinth_library::model::{Playlist, PlaylistKind};
    use plinth_types::{DeviceId, PlaylistId, Timestamp, TrackId};

    use super::{Journal, Op};

    struct Scratch(PathBuf);

    impl Scratch {
        fn new() -> Self {
            Self(std::env::temp_dir().join(format!("plinth-journal-{}", DeviceId::new())))
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn mix() -> Playlist {
        Playlist {
            id: PlaylistId::new(),
            name: "Mix".to_owned(),
            kind: PlaylistKind::Manual,
            created_at: Timestamp::now(),
        }
    }

    #[test]
    fn recorded_ops_survive_a_reopen() {
        let dir = Scratch::new();
        let device = DeviceId::new();
        let track = TrackId::new();
        let mut journal = Journal::open(&dir.0, device).unwrap();
        journal.record(&Op::Like { track }).unwrap();
        journal.record(&Op::CreatePlaylist(mix())).unwrap();
        let (state, mark) = (journal.state(), journal.mark());
        drop(journal);

        let journal = Journal::open(&dir.0, device).unwrap();

        assert_eq!(journal.state(), state);
        assert_eq!(journal.mark(), mark);
    }

    #[test]
    fn state_survives_compaction() {
        let dir = Scratch::new();
        let device = DeviceId::new();
        let mut journal = Journal::open(&dir.0, device).unwrap();
        journal.record(&Op::Like { track: TrackId::new() }).unwrap();
        journal.compact().unwrap();
        journal.record(&Op::Like { track: TrackId::new() }).unwrap();
        let (state, mark) = (journal.state(), journal.mark());
        drop(journal);

        let journal = Journal::open(&dir.0, device).unwrap();

        assert_eq!(journal.state(), state);
        assert_eq!(journal.state().likes.len(), 2);
        assert_eq!(journal.mark(), mark);
    }

    /// Метка сдвигается на единицу с каждой правкой, и только с правкой.
    #[test]
    fn mark_moves_with_every_change_only() {
        let dir = Scratch::new();
        let mut journal = Journal::open(&dir.0, DeviceId::new()).unwrap();
        let track = TrackId::new();
        let start = journal.mark();

        journal.record(&Op::Like { track }).unwrap();
        assert!(journal.record(&Op::Like { track }).unwrap().is_none());
        journal.record(&Op::Unlike { track }).unwrap();

        assert_eq!(journal.mark().journal, start.journal);
        assert_eq!(journal.mark().seq, start.seq + 2);
    }

    #[test]
    fn different_journals_have_different_ids() {
        let (a, b) = (Scratch::new(), Scratch::new());

        let first = Journal::open(&a.0, DeviceId::new()).unwrap().mark();
        let second = Journal::open(&b.0, DeviceId::new()).unwrap().mark();

        assert_ne!(first.journal, second.journal);
    }

    #[test]
    fn operations_carry_their_author() {
        let dir = Scratch::new();
        let device = DeviceId::new();
        let mut journal = Journal::open(&dir.0, device).unwrap();

        let meta = journal.record(&Op::Like { track: TrackId::new() }).unwrap().unwrap();

        assert_eq!(meta.device, device);
        assert_eq!(meta.schema, super::OP_SCHEMA);
    }

    /// Два устройства обменялись изменениями — у обоих одно и то же, и это
    /// переживает перезапуск.
    #[test]
    fn merged_journals_agree_and_keep_it() {
        let (a, b) = (Scratch::new(), Scratch::new());
        let (phone_device, tablet_device) = (DeviceId::new(), DeviceId::new());
        let mut phone = Journal::open(&a.0, phone_device).unwrap();
        let mut tablet = Journal::open(&b.0, tablet_device).unwrap();
        phone.record(&Op::Like { track: TrackId::new() }).unwrap();
        tablet.record(&Op::CreatePlaylist(mix())).unwrap();

        let to_tablet = phone.updates_since(&tablet.state_vector()).unwrap();
        let to_phone = tablet.updates_since(&phone.state_vector()).unwrap();
        assert!(tablet.merge(&to_tablet).unwrap());
        assert!(phone.merge(&to_phone).unwrap());
        assert!(!phone.merge(&to_phone).unwrap());

        assert_eq!(phone.state(), tablet.state());
        let state = phone.state();
        drop(phone);
        assert_eq!(Journal::open(&a.0, phone_device).unwrap().state(), state);
    }

    /// Порча кадра в хвосте: читается всё до неё, и это сразу ложится снимком.
    #[test]
    fn a_damaged_tail_is_repaired_on_open() {
        let dir = Scratch::new();
        let device = DeviceId::new();
        let (kept, lost) = (TrackId::new(), TrackId::new());
        let mut journal = Journal::open(&dir.0, device).unwrap();
        journal.record(&Op::Like { track: kept }).unwrap();
        journal.record(&Op::Like { track: lost }).unwrap();
        drop(journal);
        let tail = dir.0.join(super::store::TAIL);
        let mut bytes = fs::read(&tail).unwrap();
        let last = bytes.len() - 1;
        bytes[last] ^= 0xff;
        fs::write(&tail, bytes).unwrap();

        let repaired = Journal::open(&dir.0, device).unwrap().state();
        let reopened = Journal::open(&dir.0, device).unwrap().state();

        assert_eq!(repaired.likes, vec![kept]);
        assert_eq!(reopened, repaired);
    }
}
