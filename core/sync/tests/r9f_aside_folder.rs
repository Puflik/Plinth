//! Р9е (`docs/work/r9f.md`, Р9б «Не проверено»): копии рядом с журналом — `tail.damaged-*`,
//! `tail.foreign-*`, `snapshot.damaged-*` — лежат в `journal/aside/`, а не в самом
//! `journal/`: правило Auto Backup исключает эту папку (квота 25 МБ на приложение), а
//! копии, которых никто не читает, не должны её выбирать. Метка в имени копии растёт, как
//! бы ни шли часы: свежая копия не оказывается «старейшей» и не стирается чисткой, а
//! недописанный временный файл прошлого запуска убирается при открытии.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r9b_support;

use std::fs;
use std::path::Path;

use common::Scratch;
use plinth_sync::journal::{Journal, Op};
use plinth_types::{DeviceId, TrackId};
use r9b_support::{FRAME_HEADER, Pristine, spans};

fn names(path: &Path) -> Vec<String> {
    let mut found: Vec<String> = fs::read_dir(path)
        .into_iter()
        .flatten()
        .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    found.sort();
    found
}

fn open(dir: &Scratch) {
    drop(Journal::open(&dir.journal(), DeviceId::new()).unwrap());
}

/// Каталог, где у хвоста испорчен третий кадр, и байты этого хвоста.
fn damaged() -> (Scratch, Vec<u8>) {
    let pristine = Pristine::new(4);
    let mut tail = pristine.tail.clone();
    tail[spans(&pristine.tail)[2].0 + FRAME_HEADER] ^= 0xff;
    (pristine.dir_with(&tail), tail)
}

/// Каталог, где хвост принадлежит другому журналу, и байты этого хвоста.
fn foreign() -> (Scratch, Vec<u8>) {
    let other = Scratch::new();
    let mut journal = Journal::open(&other.journal(), DeviceId::new()).unwrap();
    journal.record(&Op::Like { track: TrackId::new() }).unwrap();
    drop(journal);
    let tail = fs::read(other.journal().join("tail")).unwrap();
    let dir = Scratch::new();
    open(&dir);
    fs::write(dir.journal().join("tail"), &tail).unwrap();
    (dir, tail)
}

#[test]
fn a_copy_of_a_damaged_tail_goes_to_the_aside_folder() {
    let (dir, tail) = damaged();

    open(&dir);

    let aside = names(&dir.journal().join("aside"));
    assert_eq!(aside.len(), 1, "{aside:?}");
    assert!(aside[0].starts_with("tail.damaged-"), "{aside:?}");
    assert_eq!(fs::read(dir.journal().join("aside").join(&aside[0])).unwrap(), tail);
    let beside = names(&dir.journal());
    assert!(beside.iter().all(|name| !name.contains(".damaged-")), "рядом с журналом копий нет: {beside:?}");
}

#[test]
fn a_tail_of_another_journal_is_kept_in_the_aside_folder() {
    let (dir, tail) = foreign();

    open(&dir);

    let aside = names(&dir.journal().join("aside"));
    assert_eq!(aside.len(), 1, "{aside:?}");
    assert!(aside[0].starts_with("tail.foreign-"), "{aside:?}");
    assert_eq!(fs::read(dir.journal().join("aside").join(&aside[0])).unwrap(), tail);
    let beside = names(&dir.journal());
    assert!(beside.iter().all(|name| !name.contains(".foreign-")), "рядом с журналом копий нет: {beside:?}");
}

/// Сборки до Р9е клали копии рядом с журналом, и Auto Backup уносил их в облако. Открытие
/// переносит их в `aside/`; чужие имена и имена не по образцу не трогаются.
#[test]
fn copies_made_by_older_builds_move_into_the_aside_folder() {
    let dir = Scratch::new();
    open(&dir);
    let planted = [("tail.damaged-1000", b"a".as_slice()), ("snapshot.damaged-2000", b"b"), ("tail.foreign-3000", b"c")];
    for (name, bytes) in planted {
        fs::write(dir.journal().join(name), bytes).unwrap();
    }
    fs::write(dir.journal().join("notes.txt"), b"n").unwrap();
    fs::write(dir.journal().join("tail.damaged-notanumber"), b"x").unwrap();

    open(&dir);

    assert_eq!(names(&dir.journal().join("aside")), ["snapshot.damaged-2000", "tail.damaged-1000", "tail.foreign-3000"]);
    for (name, bytes) in planted {
        assert_eq!(fs::read(dir.journal().join("aside").join(name)).unwrap(), bytes, "{name}");
    }
    assert_eq!(names(&dir.journal()), ["aside", "notes.txt", "snapshot", "tail", "tail.damaged-notanumber"]);
}

/// Папку копий заводит первая копия, а не каждое открытие: журнал без порчи её не имеет.
#[test]
fn an_open_without_copies_creates_no_aside_folder() {
    let dir = Scratch::new();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    journal.record(&Op::Like { track: TrackId::new() }).unwrap();
    drop(journal);

    open(&dir);

    assert!(!dir.journal().join("aside").exists(), "{:?}", names(&dir.journal()));
}

/// Запись копии идёт во временный файл `<имя>.tmp`; запуск оборвался посреди неё — файл
/// остался. Открытие его стирает, готовые копии не трогает.
#[test]
fn leftovers_of_a_cut_copy_are_removed_at_open() {
    let dir = Scratch::new();
    open(&dir);
    let aside = dir.journal().join("aside");
    fs::create_dir_all(&aside).unwrap();
    for name in ["tail.damaged-5.tmp", "snapshot.damaged-6.tmp", "tail.foreign-7.tmp", "tail.damaged-5"] {
        fs::write(aside.join(name), b"x").unwrap();
    }

    open(&dir);

    assert_eq!(names(&aside), ["tail.damaged-5"]);
}

/// Часы ушли назад: метки прежних копий больше «сейчас» (здесь — далёкое будущее). Новая
/// копия получает метку выше всех прежних, поэтому чистка стирает старейшую из прежних, а
/// не только что сделанную копию, после которой хвост перезаписывается (Р9б, ревью).
#[test]
fn a_fresh_copy_survives_a_clock_that_went_back() {
    let (dir, tail) = damaged();
    let aside = dir.journal().join("aside");
    fs::create_dir_all(&aside).unwrap();
    let (older, newer) = ("tail.damaged-99999999999990", "tail.damaged-99999999999991");
    fs::write(aside.join(older), b"older").unwrap();
    fs::write(aside.join(newer), b"newer").unwrap();

    open(&dir);

    let copies = names(&aside);
    assert_eq!(copies.len(), 2, "{copies:?}");
    assert!(copies.contains(&newer.to_owned()), "прежняя новейшая осталась: {copies:?}");
    let fresh = copies.iter().find(|name| name.as_str() != newer).unwrap();
    assert!(fresh.as_str() > newer, "метка новой копии выше прежних: {fresh}");
    assert_eq!(fs::read(aside.join(fresh)).unwrap(), tail, "новая копия — испорченный хвост");
}
