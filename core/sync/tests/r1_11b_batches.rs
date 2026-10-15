//! Р1.11б (`docs/work/r1-11b.md`, находка 21 ревью v0.2): пакет операций
//! `Journal::record_all` — одна правка, всё или ничего. Раньше операция, которая
//! не применялась посреди пакета (плейлиста нет), оставляла в документе всё, что
//! стояло перед ней: в памяти и в снимке для копии оно было, а на диск не
//! ложилось и после перезапуска пропадало.
//!
//! Пакет ведёт себя как те же операции по одной: если по одной они все
//! применяются — пакет применяется, если какая-то нет — пакет отказывает и не
//! оставляет следа (ни в памяти, ни на диске, ни в метке журнала).

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::Path;

use common::Scratch;
use plinth_library::model::{Playlist, PlaylistEntry, PlaylistKind};
use plinth_sync::journal::{Journal, Op, state_of};
use plinth_types::{CoreError, DeviceId, PlaylistEntryId, PlaylistId, Position, Timestamp, TrackId};

const AT: Timestamp = Timestamp::from_millis(1_790_000_000_000);

fn playlist_id(n: u8) -> PlaylistId {
    format!("{n:08x}-0000-7000-8000-00000000000{n:x}").parse().unwrap()
}

fn entry_id(n: u8) -> PlaylistEntryId {
    format!("{n:08x}-0000-7000-8000-00000000010{n:x}").parse().unwrap()
}

fn track(n: u8) -> TrackId {
    format!("{n:08x}-0000-7000-8000-00000000020{n:x}").parse().unwrap()
}

/// Операция пакета в виде, который понимает и журнал, и модель ниже.
#[derive(Debug, Clone, Copy)]
enum Step {
    Like(u8),
    Create(u8),
    Rename(u8),
    Delete(u8),
    /// Запись `.0` в плейлист `.1`.
    Add(u8, u8),
    Move(u8),
    Remove(u8),
}

impl Step {
    fn op(self) -> Op {
        match self {
            Self::Like(t) => Op::Like { track: track(t) },
            Self::Create(p) => Op::CreatePlaylist(Playlist {
                id: playlist_id(p),
                name: format!("Playlist {p}"),
                kind: PlaylistKind::Manual,
                created_at: AT,
            }),
            Self::Rename(p) => Op::RenamePlaylist { playlist: playlist_id(p), name: format!("Renamed {p}") },
            Self::Delete(p) => Op::DeletePlaylist { playlist: playlist_id(p) },
            Self::Add(e, p) => Op::AddEntry(PlaylistEntry {
                id: entry_id(e),
                playlist: playlist_id(p),
                track: track(1),
                position: position(e),
                added_at: AT,
            }),
            Self::Move(e) => Op::MoveEntry { entry: entry_id(e), position: position(e + 10) },
            Self::Remove(e) => Op::RemoveEntry { entry: entry_id(e) },
        }
    }
}

fn position(step: u8) -> Position {
    (0..step).fold(Position::first(), |last, _| Position::after(&last))
}

/// Что есть в журнале по смыслу «существует ли»: плейлисты и записи (запись → плейлист).
#[derive(Clone)]
struct Model {
    playlists: BTreeSet<u8>,
    entries: BTreeMap<u8, u8>,
}

impl Model {
    /// Плейлист 1 с записями 1 и 2.
    fn start() -> Self {
        Self { playlists: BTreeSet::from([1]), entries: BTreeMap::from([(1, 1), (2, 1)]) }
    }

    /// Применилась ли операция. Правила — как у журнала по одной операции.
    fn apply(&mut self, step: Step) -> bool {
        match step {
            Step::Like(_) => true,
            Step::Create(p) => {
                self.playlists.insert(p);
                true
            }
            Step::Rename(p) => self.playlists.contains(&p),
            Step::Delete(p) => {
                self.playlists.remove(&p);
                self.entries.retain(|_, owner| *owner != p);
                true
            }
            Step::Add(e, p) => {
                let known = self.playlists.contains(&p);
                if known {
                    self.entries.entry(e).or_insert(p);
                }
                known
            }
            Step::Move(e) => self.entries.contains_key(&e),
            Step::Remove(e) => {
                self.entries.remove(&e);
                true
            }
        }
    }

    /// Все операции применяются подряд (первая неудача — отказ всех).
    fn accepts(&self, steps: &[Step]) -> bool {
        let mut model = self.clone();
        steps.iter().all(|step| model.apply(*step))
    }
}

/// Журнал с плейлистом 1 и записями 1 и 2 в нём; закрыт, лежит в `dir`.
fn started(dir: &Scratch) {
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    for step in [Step::Create(1), Step::Add(1, 1), Step::Add(2, 1)] {
        journal.record_at(&step.op(), AT).unwrap();
    }
}

fn copy_dir(from: &Path, to: &Path) {
    fs::create_dir_all(to).unwrap();
    for file in fs::read_dir(from).unwrap() {
        let path = file.unwrap().path();
        fs::copy(&path, to.join(path.file_name().unwrap())).unwrap();
    }
}

fn open(dir: &Scratch) -> Journal {
    Journal::open(&dir.journal(), DeviceId::new()).unwrap()
}

fn batch(steps: &[Step]) -> Vec<Op> {
    steps.iter().map(|step| step.op()).collect()
}

/// Повтор находки 21: лайк, потом переименование плейлиста, которого нет.
#[test]
fn a_failed_batch_leaves_no_trace_in_memory_or_on_disk() {
    let dir = Scratch::new();
    started(&dir);
    let mut journal = open(&dir);
    let (state, mark) = (journal.state(), journal.mark());

    let result = journal.record_all(&batch(&[Step::Like(1), Step::Rename(9)]));

    assert!(matches!(result, Err(CoreError::Unavailable { .. })), "{result:?}");
    assert_eq!(journal.state(), state, "в памяти ничего не изменилось");
    assert_eq!(journal.mark(), mark, "метка журнала не сдвинулась");
    let copy = state_of(&[&journal.snapshot()]).unwrap();
    assert_eq!(copy.likes, state.likes, "в снимке для копии в папке тоже");

    journal.record_at(&Step::Like(2).op(), AT).unwrap();
    drop(journal);
    let reopened = open(&dir).state();
    assert_eq!(reopened.likes, vec![track(2)], "после перезапуска — только то, что записалось");
}

/// Страж: что пакет создаёт сам, ему же и доступно; удачный пакет лежит на диске.
#[test]
fn a_batch_may_use_what_it_has_just_created() {
    let dir = Scratch::new();
    started(&dir);
    let mut journal = open(&dir);
    let steps = [Step::Create(3), Step::Rename(3), Step::Add(4, 3), Step::Move(4), Step::Add(3, 1), Step::Move(3)];

    journal.record_all(&batch(&steps)).unwrap();

    let memory = journal.state();
    assert_eq!(memory.entries.len(), 4, "записи 1, 2, 3 и 4");
    drop(journal);
    assert_eq!(open(&dir).state(), memory, "то, что применилось, записано");
}

/// Пакет — те же операции по одной, но без следа при отказе. Перебор всех пакетов
/// до трёх операций из одиннадцати: плейлисты 1 (есть), 2 (нет), 3 (заводится в
/// пакете), записи 1 и 2 (есть), 3 и 4 (заводятся в пакете). Удаление плейлиста
/// уносит его записи; пересозданный плейлист записей не возвращает. Любой отказ
/// отменяет весь пакет — в том числе после операций, которые что-то меняли
/// (завести плейлист, стереть запись).
#[test]
fn a_batch_acts_like_its_operations_one_by_one() {
    let alphabet = [
        Step::Remove(1),
        Step::Create(1),
        Step::Create(3),
        Step::Rename(1),
        Step::Rename(2),
        Step::Rename(3),
        Step::Delete(1),
        Step::Add(3, 1),
        Step::Add(4, 3),
        Step::Move(1),
        Step::Move(3),
    ];
    let template = Scratch::new();
    started(&template);
    let mut cases: Vec<Vec<Step>> = alphabet.iter().map(|step| vec![*step]).collect();
    let mut at = 0;
    for _ in 1..3 {
        let end = cases.len();
        for case in at..end {
            for step in alphabet {
                cases.push([cases[case].as_slice(), &[step]].concat());
            }
        }
        at = end;
    }
    assert_eq!(cases.len(), 11 + 121 + 1331);

    for steps in &cases {
        let what = format!("{steps:?}");
        let dir = Scratch::new();
        copy_dir(&template.journal(), &dir.journal());
        let mut journal = open(&dir);
        let (state, mark) = (journal.state(), journal.mark());
        let expected = Model::start().accepts(steps);

        let result = journal.record_all_at(&batch(steps), AT);

        assert_eq!(result.is_ok(), expected, "{what}: {result:?}");
        if result.is_err() {
            assert_eq!(journal.state(), state, "{what}: в памяти что-то осталось");
            assert_eq!(journal.mark(), mark, "{what}: метка сдвинулась");
        }
        let memory = journal.state();
        drop(journal);
        assert_eq!(open(&dir).state(), memory, "{what}: память и диск разошлись");
    }
}
