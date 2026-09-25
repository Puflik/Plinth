//! C1.1: журнал на `automerge` (выбрасываемый прототип).
//!
//! Документ: `likes` — map трек → true; `playlists` — map id → { name, tracks: list };
//! `plays` — list строк. Каждое событие — отдельный commit.

use automerge::transaction::Transactable;
use automerge::{ActorId, AutoCommit, ObjId, ObjType, ROOT, ReadDoc};
use proto_common::{Event, Journal, Projection};

pub struct AutomergeJournal {
    doc: AutoCommit,
}

impl AutomergeJournal {
    fn root(&self, name: &str) -> ObjId {
        self.doc.get(ROOT, name).ok().flatten().map(|(_, id)| id).expect("root object")
    }

    fn playlist_tracks(&self, playlist: &str) -> Option<ObjId> {
        let playlists = self.root("playlists");
        let (_, list) = self.doc.get(&playlists, playlist).ok().flatten()?;
        self.doc.get(&list, "tracks").ok().flatten().map(|(_, id)| id)
    }
}

impl Journal for AutomergeJournal {
    const NAME: &'static str = "automerge 0.12";

    fn genesis() -> Self {
        // Корень создаёт одно «устройство» с фиксированным id: у всех
        // устройств он один и тот же, иначе параллельно созданные `likes`
        // конфликтуют и одна из копий теряется.
        let mut doc = AutoCommit::new().with_actor(ActorId::from([0_u8; 16]));
        for (name, kind) in [("likes", ObjType::Map), ("playlists", ObjType::Map), ("plays", ObjType::List)] {
            doc.put_object(ROOT, name, kind).expect("genesis");
        }
        doc.commit();
        Self { doc }
    }

    fn fork(&self, device: u64) -> Self {
        let mut doc = self.doc.clone();
        let mut actor = [0_u8; 16];
        actor[..8].copy_from_slice(&device.to_be_bytes());
        doc.set_actor(ActorId::from(actor));
        Self { doc }
    }

    fn apply(&mut self, event: &Event) {
        match event {
            Event::Like { track } => {
                let likes = self.root("likes");
                self.doc.put(&likes, track.as_str(), true).expect("like");
            }
            Event::Unlike { track } => {
                let likes = self.root("likes");
                if self.doc.get(&likes, track.as_str()).ok().flatten().is_some() {
                    self.doc.delete(&likes, track.as_str()).expect("unlike");
                }
            }
            Event::CreatePlaylist { playlist, name } => {
                let playlists = self.root("playlists");
                let created = self.doc.put_object(&playlists, playlist.as_str(), ObjType::Map).expect("playlist");
                self.doc.put(&created, "name", name.as_str()).expect("name");
                self.doc.put_object(&created, "tracks", ObjType::List).expect("tracks");
            }
            Event::AddToPlaylist { playlist, track } => {
                if let Some(tracks) = self.playlist_tracks(playlist) {
                    let end = self.doc.length(&tracks);
                    self.doc.insert(&tracks, end, track.as_str()).expect("add");
                }
            }
            Event::MoveInPlaylist { playlist, from, to } => {
                if let Some(tracks) = self.playlist_tracks(playlist) {
                    let (from, to) = (*from as usize, *to as usize);
                    let track =
                        self.doc.get(&tracks, from).ok().flatten().and_then(|(v, _)| v.as_str().map(str::to_owned));
                    if let Some(track) = track {
                        self.doc.delete(&tracks, from).expect("move: delete");
                        self.doc.insert(&tracks, to, track.as_str()).expect("move: insert");
                    }
                }
            }
            Event::RemoveFromPlaylist { playlist, index } => {
                if let Some(tracks) = self.playlist_tracks(playlist) {
                    self.doc.delete(&tracks, *index as usize).expect("remove");
                }
            }
            Event::Play { record } => {
                let plays = self.root("plays");
                let end = self.doc.length(&plays);
                self.doc.insert(&plays, end, record.as_str()).expect("play");
            }
        }
        self.doc.commit();
    }

    fn save(&mut self) -> Vec<u8> {
        self.doc.save()
    }

    fn save_incremental(&mut self) -> Vec<u8> {
        self.doc.save_incremental()
    }

    fn load(bytes: &[u8]) -> Self {
        Self { doc: AutoCommit::load(bytes).expect("load") }
    }

    fn merge_from(&mut self, other: &mut Self) {
        self.doc.merge(&mut other.doc).expect("merge");
    }

    fn project(&self) -> Projection {
        let mut p = Projection::default();
        p.likes = self.doc.keys(self.root("likes")).collect();
        let playlists = self.root("playlists");
        for id in self.doc.keys(&playlists) {
            let Some((_, obj)) = self.doc.get(&playlists, id.as_str()).ok().flatten() else { continue };
            let name = self.doc.get(&obj, "name").ok().flatten().and_then(|(v, _)| v.as_str().map(str::to_owned));
            let tracks = self.doc.get(&obj, "tracks").ok().flatten().map(|(_, id)| id);
            let tracks = tracks
                .map(|t| self.doc.values(&t).filter_map(|(v, _)| v.as_str().map(str::to_owned)).collect())
                .unwrap_or_default();
            p.playlists.insert(id, (name.unwrap_or_default(), tracks));
        }
        for (value, _) in self.doc.values(self.root("plays")) {
            if let Some(record) = value.as_str() {
                p.count_play(record);
            }
        }
        p
    }
}

/// Для замера веса в APK: без вызова линкер выбросил бы библиотеку целиком.
#[unsafe(no_mangle)]
pub extern "C" fn proto_run(events: u32) -> u64 {
    let mut journal = AutomergeJournal::genesis();
    for event in proto_common::workload(events as usize, 42) {
        journal.apply(&event);
    }
    let bytes = journal.save();
    AutomergeJournal::load(&bytes).project().plays as u64 + bytes.len() as u64
}
