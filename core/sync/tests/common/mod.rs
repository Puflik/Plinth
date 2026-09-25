//! Общее для тестов проекции: каталог во временной папке, слепок таблиц
//! проекции и детерминированный поток операций.

#![allow(dead_code, reason = "каждый тестовый файл берёт свою часть")]
#![allow(clippy::unwrap_used, reason = "помощники тестов: упавший unwrap и есть упавший тест")]

use std::fs;
use std::path::PathBuf;
use std::time::Duration;

use plinth_library::db::Database;
use plinth_library::model::{
    BlockEntry, BlockTarget, DecidedBy, IdentityBasis, MergeDecision, OutputDevice, PlayEvent, Playlist, PlaylistEntry,
    PlaylistKind, Rating, Setting, Subscription, SyncedSettings, TrackPair, TrackUserData, Verdict, VersionPreference,
    ordered_entries, position_for,
};
use plinth_sync::journal::{Journal, Op};
use plinth_types::{ArtistId, DeviceId, MergeDecisionId, PlayEventId, PlaylistEntryId, PlaylistId, Timestamp, TrackId};

/// Временный каталог; стирается в `Drop`.
pub struct Scratch(pub PathBuf);

impl Scratch {
    pub fn new() -> Self {
        let dir = std::env::temp_dir().join(format!("plinth-sync-{}", DeviceId::new()));
        fs::create_dir_all(&dir).unwrap();
        Self(dir)
    }

    pub fn journal(&self) -> PathBuf {
        self.0.join("journal")
    }

    pub fn db(&self) -> PathBuf {
        self.0.join("library.db")
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// Всё, что отражает журнал, в сравнимом виде.
#[derive(Debug, PartialEq)]
pub struct Dump {
    pub user: Vec<TrackUserData>,
    pub playlists: Vec<(Playlist, Vec<PlaylistEntry>)>,
    pub plays: Vec<PlayEvent>,
    pub decisions: Vec<MergeDecision>,
    pub subscriptions: Vec<Subscription>,
    pub blocklist: Vec<BlockEntry>,
    pub settings: SyncedSettings,
}

pub fn dump(db: &Database) -> Dump {
    Dump {
        user: db.all_user_data().unwrap(),
        playlists: db
            .playlists()
            .unwrap()
            .into_iter()
            .map(|playlist| {
                let entries = db.entries(playlist.id).unwrap();
                (playlist, entries)
            })
            .collect(),
        plays: db.recent_plays(u32::MAX).unwrap(),
        decisions: db.merge_decisions().unwrap(),
        subscriptions: db.subscriptions().unwrap(),
        blocklist: db.blocklist().unwrap(),
        settings: db.synced_settings().unwrap(),
    }
}

pub fn playlist(name: &str) -> Playlist {
    Playlist { id: PlaylistId::new(), name: name.to_owned(), kind: PlaylistKind::Manual, created_at: Timestamp::now() }
}

pub fn play(track: TrackId, at: i64, listened_s: u64, length_s: Option<u64>) -> PlayEvent {
    PlayEvent {
        id: PlayEventId::new(),
        track,
        version: None,
        source: None,
        started_at: Timestamp::from_millis(at),
        utc_offset_minutes: 180,
        listened: Duration::from_secs(listened_s),
        track_length: length_s.map(Duration::from_secs),
        skipped_at: None,
        output: OutputDevice::Headphones,
        previous_track: None,
    }
}

/// Детерминированный поток осмысленных операций: плейлисты и записи берутся
/// из текущего состояния журнала, треки и артисты — из небольшого набора,
/// чтобы операции попадали друг в друга.
pub struct Workload {
    seed: u64,
    tracks: Vec<TrackId>,
    artists: Vec<ArtistId>,
    clock: i64,
}

impl Workload {
    pub fn new(seed: u64) -> Self {
        Self {
            seed,
            tracks: (0..12).map(|_| TrackId::new()).collect(),
            artists: (0..4).map(|_| ArtistId::new()).collect(),
            clock: 1_790_000_000_000,
        }
    }

    fn next(&mut self, bound: usize) -> usize {
        self.seed ^= self.seed << 13;
        self.seed ^= self.seed >> 7;
        self.seed ^= self.seed << 17;
        (self.seed % bound.max(1) as u64) as usize
    }

    fn track(&mut self) -> TrackId {
        let index = self.next(self.tracks.len());
        self.tracks[index]
    }

    fn artist(&mut self) -> ArtistId {
        let index = self.next(self.artists.len());
        self.artists[index]
    }

    fn pick<T: Clone>(&mut self, items: &[T]) -> Option<T> {
        if items.is_empty() { None } else { Some(items[self.next(items.len())].clone()) }
    }

    /// Следующая операция, осмысленная для текущего состояния журнала.
    pub fn op(&mut self, journal: &Journal) -> Op {
        let state = journal.state();
        self.clock += 60_000;
        loop {
            let op = match self.next(17) {
                0 | 1 => Some(Op::Like { track: self.track() }),
                2 => Some(Op::Unlike { track: self.track() }),
                3 => {
                    let stars = self.next(6) as u8;
                    Some(Op::Rate { track: self.track(), rating: Rating::new(stars).ok() })
                }
                4 => Some(Op::CreatePlaylist(playlist("List"))),
                5 => self
                    .pick(&state.playlists)
                    .map(|p| Op::RenamePlaylist { playlist: p.id, name: format!("Renamed {}", self.clock) }),
                6 => (self.next(4) == 0)
                    .then(|| self.pick(&state.playlists))
                    .flatten()
                    .map(|p| Op::DeletePlaylist { playlist: p.id }),
                7 | 8 => self.pick(&state.playlists).map(|p| {
                    let entries: Vec<PlaylistEntry> =
                        ordered_entries(state.entries.iter().filter(|e| e.playlist == p.id).cloned().collect());
                    let index = self.next(entries.len() + 1);
                    Op::AddEntry(PlaylistEntry {
                        id: PlaylistEntryId::new(),
                        playlist: p.id,
                        track: self.track(),
                        position: position_for(&entries, index, None),
                        added_at: Timestamp::from_millis(self.clock),
                    })
                }),
                9 => self.pick(&state.entries).map(|moved| {
                    let siblings: Vec<PlaylistEntry> = ordered_entries(
                        state.entries.iter().filter(|e| e.playlist == moved.playlist).cloned().collect(),
                    );
                    let index = self.next(siblings.len());
                    Op::MoveEntry { entry: moved.id, position: position_for(&siblings, index, Some(moved.id)) }
                }),
                10 => self.pick(&state.entries).map(|e| Op::RemoveEntry { entry: e.id }),
                11 | 12 => {
                    let length = [None, Some(20), Some(180), Some(600)][self.next(4)];
                    let listened = self.next(400) as u64;
                    let at = self.clock - self.next(10) as i64 * 1_000_000;
                    Some(Op::Play(play(self.track(), at, listened, length)))
                }
                13 => {
                    let (a, b) = (self.track(), self.track());
                    TrackPair::new(a, b).ok().map(|pair| {
                        let by = if self.next(2) == 0 {
                            DecidedBy::User
                        } else {
                            DecidedBy::Auto { basis: IdentityBasis::Fingerprint, confidence: 0.5 }
                        };
                        let verdict = if self.next(2) == 0 { Verdict::Merge } else { Verdict::Split };
                        Op::Decide(MergeDecision {
                            id: MergeDecisionId::new(),
                            pair,
                            verdict,
                            by,
                            decided_at: Timestamp::from_millis(self.clock),
                        })
                    })
                }
                14 => {
                    let artist = self.artist();
                    Some(if self.next(2) == 0 {
                        Op::Subscribe(Subscription { artist, since: Timestamp::from_millis(self.clock) })
                    } else {
                        Op::Unsubscribe { artist }
                    })
                }
                15 => {
                    let target = if self.next(2) == 0 {
                        BlockTarget::Track(self.track())
                    } else {
                        BlockTarget::Artist(self.artist())
                    };
                    Some(if self.next(2) == 0 {
                        Op::Block(BlockEntry { target, since: Timestamp::from_millis(self.clock) })
                    } else {
                        Op::Unblock { target }
                    })
                }
                _ => {
                    let preference =
                        [VersionPreference::Original, VersionPreference::Clean, VersionPreference::Any][self.next(3)];
                    Some(Op::Set(Setting::VersionPreference(preference)))
                }
            };
            if let Some(op) = op {
                return op;
            }
        }
    }
}

/// База в памяти: схема та же, что у файла.
pub fn memory_db() -> Database {
    Database::open_in_memory().unwrap()
}
