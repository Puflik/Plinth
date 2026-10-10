//! Общее для тестов Р1.6 (`docs/work/r1-6.md`): пять регистров журнала как
//! таблица, реплики с обменом обновлениями и эталон журнала «до Р1.6».
//!
//! Регистр — запись, у которой по ключу действует последнее значение:
//! оценка, плейлист (имя), запись плейлиста (позиция), настройка, паспорт.

#![allow(dead_code, reason = "каждый тестовый файл берёт свою часть")]
#![allow(clippy::unwrap_used, reason = "помощники тестов: упавший unwrap и есть упавший тест")]

use std::fs;
use std::path::Path;
use std::time::Duration;

use plinth_library::model::{Playlist, PlaylistEntry, PlaylistKind, Rating, Setting, TrackPassport, VersionPreference};
use plinth_sync::journal::{Journal, Op};
use plinth_types::{DeviceId, PlaylistEntryId, PlaylistId, Position, Timestamp, TrackId};

use crate::common::Scratch;

/// ID эталона `tests/fixtures/registers-legacy` — те же, что при его создании.
pub fn t1() -> TrackId {
    "11111111-1111-7111-8111-111111111111".parse().unwrap()
}

pub fn t2() -> TrackId {
    "22222222-2222-7222-8222-222222222222".parse().unwrap()
}

pub fn p1() -> PlaylistId {
    "33333333-3333-7333-8333-333333333333".parse().unwrap()
}

pub fn e1() -> PlaylistEntryId {
    "44444444-4444-7444-8444-444444444444".parse().unwrap()
}

pub fn rating(stars: u8) -> Option<Rating> {
    Some(Rating::new(stars).unwrap())
}

pub fn playlist(name: &str) -> Playlist {
    Playlist {
        id: p1(),
        name: name.to_owned(),
        kind: PlaylistKind::Manual,
        created_at: Timestamp::from_millis(1_790_000_000_000),
    }
}

pub fn entry(position: Position) -> PlaylistEntry {
    PlaylistEntry {
        id: e1(),
        playlist: p1(),
        track: t1(),
        position,
        added_at: Timestamp::from_millis(1_790_000_000_000),
    }
}

pub fn passport(title: &str) -> TrackPassport {
    TrackPassport {
        track: t1(),
        title: title.to_owned(),
        artist: "Кино".to_owned(),
        album: None,
        duration: Some(Duration::from_secs(398)),
        mbid: None,
        sources: Vec::new(),
    }
}

/// Позиции, которыми спорят записи: первая и две за ней.
pub fn position(step: usize) -> Position {
    (0..step).fold(Position::first(), |last, _| Position::after(&last))
}

/// Какое из двух значений регистра записывают.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Pick {
    A,
    B,
}

/// Регистр: что должно лежать в журнале до спора, чем спорят и как прочесть победителя.
pub struct Kind {
    pub name: &'static str,
    pub base: fn(&mut Journal),
    pub ops: fn(Pick) -> Vec<Op>,
    pub read: fn(&Journal) -> String,
    pub shown: fn(Pick) -> String,
}

fn nothing(_: &mut Journal) {}

fn playlist_with_entry(journal: &mut Journal) {
    journal.record_all(&[Op::CreatePlaylist(playlist("Base")), Op::AddEntry(entry(position(0)))]).unwrap();
}

fn pick<T>(pick: Pick, a: T, b: T) -> T {
    if pick == Pick::A { a } else { b }
}

pub const KINDS: [Kind; 5] = [
    Kind {
        name: "rating",
        base: nothing,
        ops: |p| vec![Op::Rate { track: t1(), rating: rating(pick(p, 5, 3)) }],
        read: |j| format!("{:?}", j.state().ratings.iter().find(|(t, _)| *t == t1()).map(|(_, r)| *r)),
        shown: |p| format!("{:?}", rating(pick(p, 5, 3))),
    },
    Kind {
        name: "playlist name",
        base: playlist_with_entry,
        ops: |p| vec![Op::RenamePlaylist { playlist: p1(), name: pick(p, "Alpha", "Beta").to_owned() }],
        read: |j| j.state().playlists.iter().find(|l| l.id == p1()).map(|l| l.name.clone()).unwrap_or_default(),
        shown: |p| pick(p, "Alpha", "Beta").to_owned(),
    },
    Kind {
        name: "entry position",
        base: playlist_with_entry,
        ops: |p| vec![Op::MoveEntry { entry: e1(), position: position(pick(p, 1, 2)) }],
        read: |j| {
            let state = j.state();
            format!("{:?}", state.entries.iter().find(|e| e.id == e1()).map(|e| e.position.clone()))
        },
        shown: |p| format!("{:?}", Some(position(pick(p, 1, 2)))),
    },
    Kind {
        name: "setting",
        base: nothing,
        ops: |p| {
            let preference = pick(p, VersionPreference::Clean, VersionPreference::Original);
            vec![Op::Set(Setting::VersionPreference(preference))]
        },
        read: |j| format!("{:?}", j.state().settings),
        shown: |p| {
            format!(
                "{:?}",
                vec![Setting::VersionPreference(pick(p, VersionPreference::Clean, VersionPreference::Original))]
            )
        },
    },
    Kind {
        name: "passport",
        base: nothing,
        ops: |p| vec![Op::Describe(passport(pick(p, "Alpha", "Beta")))],
        read: |j| j.passport(t1()).map(|p| p.title).unwrap_or_default(),
        shown: |p| pick(p, "Alpha", "Beta").to_owned(),
    },
];

/// Журнал в своём каталоге; установка — `device`.
pub struct Replica {
    pub journal: Journal,
    _dir: Scratch,
}

impl Replica {
    pub fn new() -> Self {
        Self::with_device(DeviceId::new())
    }

    pub fn with_device(device: DeviceId) -> Self {
        let dir = Scratch::new();
        let journal = Journal::open(&dir.journal(), device).unwrap();
        Self { journal, _dir: dir }
    }

    /// Журнал из эталона `tests/fixtures/registers-legacy`: сделан кодом до Р1.6.
    pub fn legacy() -> Self {
        let dir = Scratch::new();
        let from = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/registers-legacy/journal");
        fs::create_dir_all(dir.journal()).unwrap();
        for name in ["snapshot", "tail"] {
            fs::copy(from.join(name), dir.journal().join(name)).unwrap();
        }
        let journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
        Self { journal, _dir: dir }
    }

    /// Забирает у `other` всё, чего здесь ещё нет.
    pub fn take_from(&mut self, other: &Replica) {
        let update = other.journal.updates_since(&self.journal.state_vector()).unwrap();
        self.journal.merge(&update).unwrap();
    }
}

/// Обмен обновлениями в обе стороны.
pub fn exchange(a: &mut Replica, b: &mut Replica) {
    a.take_from(b);
    b.take_from(a);
}

/// Две установки с общим началом `kind.base`, дальше они пишут порознь.
pub fn pair(kind: &Kind) -> (Replica, Replica) {
    let mut base = Replica::new();
    (kind.base)(&mut base.journal);
    let (mut a, mut b) = (Replica::new(), Replica::new());
    a.take_from(&base);
    b.take_from(&base);
    (a, b)
}
