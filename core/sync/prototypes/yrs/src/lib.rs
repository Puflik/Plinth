//! C1.1: журнал на `yrs` — порт Yjs (выбрасываемый прототип).
//!
//! Корневые типы Yjs именованные и не конфликтуют: `likes` — map трек →
//! true; `playlists` — map id → { name, tracks: array }; `plays` — array
//! строк. Каждое событие — отдельная транзакция.

use proto_common::{Event, Journal, Projection};
use yrs::updates::decoder::Decode;
use yrs::{
    Any, Array, ArrayPrelim, ArrayRef, Doc, In, Map, MapPrelim, MapRef, Out, ReadTxn, StateVector, Transact, Update,
};

pub struct YrsJournal {
    doc: Doc,
    /// Обновления транзакций с прошлого `save_incremental` — то, что дописывается в файл.
    pending: Vec<u8>,
}

impl YrsJournal {
    fn with_doc(doc: Doc) -> Self {
        Self { doc, pending: Vec::new() }
    }

    fn likes(&self) -> MapRef {
        self.doc.get_or_insert_map("likes")
    }

    fn playlists(&self) -> MapRef {
        self.doc.get_or_insert_map("playlists")
    }

    fn plays(&self) -> ArrayRef {
        self.doc.get_or_insert_array("plays")
    }

    fn tracks<T: ReadTxn>(playlists: &MapRef, txn: &T, playlist: &str) -> Option<ArrayRef> {
        match playlists.get(txn, playlist)? {
            Out::YMap(map) => match map.get(txn, "tracks")? {
                Out::YArray(tracks) => Some(tracks),
                _ => None,
            },
            _ => None,
        }
    }

    fn text(out: Out) -> Option<String> {
        match out {
            Out::Any(Any::String(s)) => Some(s.to_string()),
            _ => None,
        }
    }

    fn apply_update_v1(&self, update: &[u8]) {
        self.doc.transact_mut().apply_update(Update::decode_v1(update).expect("decode")).expect("apply");
    }
}

impl Journal for YrsJournal {
    const NAME: &'static str = "yrs 0.28";

    fn genesis() -> Self {
        Self::with_doc(Doc::with_client_id(1_000))
    }

    fn fork(&self, device: u64) -> Self {
        let copy = Self::with_doc(Doc::with_client_id(device));
        let state = self.doc.transact().encode_state_as_update_v1(&StateVector::default());
        copy.apply_update_v1(&state);
        copy
    }

    fn apply(&mut self, event: &Event) {
        let (likes, playlists, plays) = (self.likes(), self.playlists(), self.plays());
        let mut txn = self.doc.transact_mut();
        match event {
            Event::Like { track } => {
                likes.insert(&mut txn, track.as_str(), true);
            }
            Event::Unlike { track } => {
                likes.remove(&mut txn, track);
            }
            Event::CreatePlaylist { playlist, name } => {
                let created = MapPrelim::from([
                    ("name", In::from(Any::from(name.as_str()))),
                    ("tracks", In::Array(ArrayPrelim::default())),
                ]);
                playlists.insert(&mut txn, playlist.as_str(), created);
            }
            Event::AddToPlaylist { playlist, track } => {
                if let Some(tracks) = Self::tracks(&playlists, &txn, playlist) {
                    tracks.push_back(&mut txn, track.as_str());
                }
            }
            Event::MoveInPlaylist { playlist, from, to } => {
                if let Some(tracks) = Self::tracks(&playlists, &txn, playlist) {
                    if let Some(track) = tracks.get(&txn, *from).and_then(Self::text) {
                        tracks.remove(&mut txn, *from);
                        tracks.insert(&mut txn, *to, track.as_str());
                    }
                }
            }
            Event::RemoveFromPlaylist { playlist, index } => {
                if let Some(tracks) = Self::tracks(&playlists, &txn, playlist) {
                    tracks.remove(&mut txn, *index);
                }
            }
            Event::Play { record } => {
                plays.push_back(&mut txn, record.as_str());
            }
        }
        // Обновление ровно этой транзакции, с её удалениями. Разница по
        // вектору состояния тут не годится: он не помнит удалений, и в
        // каждую такую разницу Yjs кладёт все удаления документа.
        self.pending.extend(txn.encode_update_v1());
    }

    /// Снимок — формат v2: у Yjs он компактнее v1 на больших документах.
    fn save(&mut self) -> Vec<u8> {
        self.doc.transact().encode_state_as_update_v2(&StateVector::default())
    }

    fn other_sizes(&mut self) -> Vec<(&'static str, usize)> {
        vec![("v1", self.doc.transact().encode_state_as_update_v1(&StateVector::default()).len())]
    }

    /// Лог — обновления транзакций в v1, как их шлют провайдеры Yjs: у
    /// маленьких правок v1 короче v2.
    fn save_incremental(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.pending)
    }

    fn load(bytes: &[u8]) -> Self {
        let journal = Self::with_doc(Doc::new());
        journal.doc.transact_mut().apply_update(Update::decode_v2(bytes).expect("decode")).expect("apply");
        journal
    }

    fn merge_from(&mut self, other: &mut Self) {
        let known = self.doc.transact().state_vector();
        let missing = other.doc.transact().encode_state_as_update_v1(&known);
        self.apply_update_v1(&missing);
    }

    fn project(&self) -> Projection {
        let (likes, playlists, plays) = (self.likes(), self.playlists(), self.plays());
        let txn = self.doc.transact();
        let mut p = Projection::default();
        p.likes = likes.keys(&txn).map(str::to_owned).collect();
        for (id, value) in playlists.iter(&txn) {
            let Out::YMap(map) = value else { continue };
            let name = map.get(&txn, "name").and_then(Self::text).unwrap_or_default();
            let tracks = match map.get(&txn, "tracks") {
                Some(Out::YArray(tracks)) => tracks.iter(&txn).filter_map(Self::text).collect(),
                _ => Vec::new(),
            };
            p.playlists.insert(id.to_owned(), (name, tracks));
        }
        for value in plays.iter(&txn) {
            if let Some(record) = Self::text(value) {
                p.count_play(&record);
            }
        }
        p
    }
}

/// Для замера веса в APK: без вызова линкер выбросил бы библиотеку целиком.
#[unsafe(no_mangle)]
pub extern "C" fn proto_run(events: u32) -> u64 {
    let mut journal = YrsJournal::genesis();
    for event in proto_common::workload(events as usize, 42) {
        journal.apply(&event);
    }
    let bytes = journal.save();
    YrsJournal::load(&bytes).project().plays as u64 + bytes.len() as u64
}
