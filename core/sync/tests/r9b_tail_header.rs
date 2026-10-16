//! Р9б (`docs/work/r9b.md`, находка 22 ревью R1): порча 22-байтного заголовка
//! хвоста. Раньше нечитаемый заголовок (магия, вид, формат) давал «пустой хвост»,
//! который тут же перезаписывался, а заголовок с чужим номером снимка считался
//! «хвостом прошлого снимка»: до 256 КБ правок после снимка (лайки, плейлисты,
//! история) пропадали при открытии, копии `tail.damaged-*` не оставалось.
//!
//! Кадры хвоста защищены каждый своей суммой и читаются, даже если заголовок
//! испорчен; применённые второй раз к документу, где они уже есть, они ничего не
//! меняют (обновления `yrs` идемпотентны). Поэтому хвост, чей заголовок не сошёлся
//! со снимком, не выбрасывается: его кадры читаются, а файл откладывается рядом.
//! Нулевой, обрезанный до заголовка хвост (сбой посреди сброса) — не порча.
//! Идентификатор журнала (байты 6–14): чужой хвост по-прежнему не вливается —
//! после `start_over` его кадры правят прежний документ (Р1.4); тесты Р1.4 держат это.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r9b_support;

use std::fs;

use common::{Scratch, Workload};
use plinth_sync::journal::{Journal, JournalState, Op};
use plinth_types::{DeviceId, TrackId};
use r9b_support::{HEADER, beside, dir_with, kept_aside, liked, listing, open, sorted};

/// Снимок и хвост журнала из осмысленных операций всех видов (лайки, оценки, плейлисты и
/// записи, прослушивания, решения…): `compacted` операций в снимке, `in_tail` — в хвосте.
struct Parts {
    snapshot: Vec<u8>,
    tail: Vec<u8>,
    /// Что показывает такой журнал целиком.
    state: JournalState,
}

impl Parts {
    fn new(compacted: usize, in_tail: usize) -> Self {
        let dir = Scratch::new();
        let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
        let mut workload = Workload::new(0x7a11);
        let mut record = |journal: &mut Journal, count: usize| {
            for _ in 0..count {
                let op = workload.op(journal);
                journal.record(&op).unwrap();
            }
        };
        record(&mut journal, compacted);
        if compacted > 0 {
            journal.compact().unwrap();
        }
        record(&mut journal, in_tail);
        let state = journal.state();
        drop(journal);
        let tail = fs::read(dir.journal().join("tail")).unwrap();
        assert!(tail.len() > HEADER + 200, "в хвосте должны быть кадры: {} байт", tail.len());
        Self { snapshot: fs::read(dir.journal().join("snapshot")).unwrap(), tail, state }
    }
}

/// Бит-флип в магии, формате, виде или номере снимка заголовка хвоста: ни одна
/// правка не пропадает, повреждённый файл лежит рядом как был, журнал пишется дальше.
/// Формат: флип 1 → 0 заголовок принимает (формат не новее), его копия не нужна.
#[test]
fn a_damaged_tail_header_loses_nothing() {
    // (в снимке, в хвосте): свежий журнал и журнал после свёртки — номер снимка не нулевой,
    // и флип номера может дать как больший, так и меньший, чем у снимка («хвост прошлого»).
    for (compacted, in_tail) in [(0, 40), (30, 40)] {
        let parts = Parts::new(compacted, in_tail);
        for position in (0..6).chain(14..HEADER) {
            for mask in [0x01_u8, 0x80, 0xff] {
                let mut tail = parts.tail.clone();
                tail[position] ^= mask;
                let what = format!("в снимке {compacted}, в хвосте {in_tail}: байт {position} заголовка ^ {mask:#x}");
                let dir = dir_with(&parts.snapshot, &tail);

                let kept = open(&dir).state();

                assert_eq!(kept, parts.state, "{what}: правки хвоста пропали");
                let copies = kept_aside(&dir);
                let accepted_anyway = position == 4 && mask == 0x01;
                if !accepted_anyway {
                    assert_eq!(copies.len(), 1, "{what}: порча не проходит молча");
                    assert_eq!(fs::read(&copies[0]).unwrap(), tail, "{what}: копия — файл как он был");
                }
                // Починено: второе открытие видит то же и второй копии не делает.
                assert_eq!(open(&dir).state(), parts.state, "{what}: второе открытие");
                assert_eq!(kept_aside(&dir).len(), copies.len(), "{what}: копия одна");
                let fresh = TrackId::new();
                open(&dir).record(&Op::Like { track: fresh }).unwrap();
                assert!(liked(&dir).contains(&fresh), "{what}: журнал пишется");
                assert_eq!(open(&dir).state().likes.len(), parts.state.likes.len() + 1, "{what}: остальное на месте");
            }
        }
    }
}

/// Страж: сбой посреди сброса хвоста оставляет пустой файл или обрывок заголовка —
/// в нём нечего терять, и порчей это не считается: ни копий, ни шума.
#[test]
fn a_tail_cut_inside_its_header_is_quiet() {
    let parts = Parts::new(30, 40);
    for length in [0, 1, 4, 10, 21] {
        let dir = dir_with(&parts.snapshot, &parts.tail[..length]);
        let what = format!("хвост из {length} байт");

        let mut journal = open(&dir);

        let fresh = TrackId::new();
        journal.record(&Op::Like { track: fresh }).unwrap();
        drop(journal);
        assert!(liked(&dir).contains(&fresh), "{what}: журнал пишется");
        let names: Vec<String> = listing(&dir).into_iter().map(|(name, _)| name).collect();
        assert!(names.iter().all(|name| !name.contains("damaged") && !name.contains("foreign")), "{what}: {names:?}");
    }
}

/// Страж: сбой между переименованием нового снимка и сбросом хвоста оставляет хвост
/// прошлого снимка. Его кадры уже в снимке: правок не прибавляется, не двоится
/// ничего, метка журнала не уходит вперёд на число кадров хвоста. Операции всех видов:
/// повторное применение удалений, регистров, записей плейлистов ничего не меняет.
#[test]
fn a_tail_already_in_the_snapshot_changes_nothing() {
    let dir = Scratch::new();
    let device = DeviceId::new();
    let mut journal = Journal::open(&dir.journal(), device).unwrap();
    let mut workload = Workload::new(0x5eed);
    for _ in 0..300 {
        let op = workload.op(&journal);
        journal.record(&op).unwrap();
    }
    let stale_tail = fs::read(dir.journal().join("tail")).unwrap();
    assert!(stale_tail.len() < 200 * 1024, "хвост ещё не свёрнут сам");
    journal.compact().unwrap();
    let (state, mark) = (journal.state(), journal.mark());
    drop(journal);
    fs::write(dir.journal().join("tail"), &stale_tail).unwrap();

    let mut journal = Journal::open(&dir.journal(), device).unwrap();

    assert_eq!(journal.state(), state, "в документе ровно то, что было");
    assert_eq!(journal.mark(), mark, "метка не ушла вперёд: кадры устаревшего хвоста не считаются");
    let fresh = TrackId::new();
    journal.record(&Op::Like { track: fresh }).unwrap();
    drop(journal);
    assert_eq!(sorted(open(&dir).state().likes), sorted([state.likes, vec![fresh]].concat()));
    assert!(beside(&dir, "tail.foreign-").is_empty());
}
