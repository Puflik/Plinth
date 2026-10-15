//! Р1.11б (`docs/work/r1-11b.md`, находка 20 ревью v0.2): длина кадра хвоста,
//! испорченная посреди файла, — порча, а не оборванный конец. Раньше кадр,
//! чья длина выходила за конец файла, считался оборванным при записи: файл
//! молча усекался, и из шести лайков оставался один без отложенной копии.
//! Оборванным остаётся только тот кадр, за которым ничего целого нет.
//!
//! Тесты идут через `Journal::open` и сами разбирают байты `tail`: заголовок
//! файла 22 байта, кадр — длина (u32, LE), CRC32 и данные.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;

use std::fs;
use std::path::PathBuf;

use common::Scratch;
use plinth_sync::journal::{Journal, Op};
use plinth_types::{DeviceId, TrackId};

/// Магия, формат, вид, идентификатор журнала, номер операции.
const HEADER: usize = 4 + 1 + 1 + 8 + 8;
const FRAME_HEADER: usize = 4 + 4;
const FRAMES: usize = 6;

/// Журнал из `FRAMES` лайков, по кадру на лайк; хвост не свёрнут.
struct Pristine {
    snapshot: Vec<u8>,
    tail: Vec<u8>,
    tracks: Vec<TrackId>,
}

impl Pristine {
    fn new() -> Self {
        let dir = Scratch::new();
        let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
        let tracks: Vec<TrackId> = (0..FRAMES).map(|_| TrackId::new()).collect();
        for track in &tracks {
            journal.record(&Op::Like { track: *track }).unwrap();
        }
        drop(journal);
        Self {
            snapshot: fs::read(dir.journal().join("snapshot")).unwrap(),
            tail: fs::read(dir.journal().join("tail")).unwrap(),
            tracks,
        }
    }

    /// (начало, конец) каждого кадра целого хвоста.
    fn spans(&self) -> Vec<(usize, usize)> {
        let mut spans = Vec::new();
        let mut at = HEADER;
        while at < self.tail.len() {
            let length = u32::from_le_bytes(self.tail[at..at + 4].try_into().unwrap()) as usize;
            spans.push((at, at + FRAME_HEADER + length));
            at += FRAME_HEADER + length;
        }
        assert_eq!(spans.len(), FRAMES);
        spans
    }

    /// Первые `count` лайков, в порядке сравнения.
    fn first(&self, count: usize) -> Vec<TrackId> {
        sorted(self.tracks[..count].to_vec())
    }

    /// Каталог журнала с этим снимком и данным хвостом; файлы пишутся без `fsync`.
    fn dir_with(&self, tail: &[u8]) -> Scratch {
        let dir = Scratch::new();
        fs::create_dir_all(dir.journal()).unwrap();
        fs::write(dir.journal().join("snapshot"), &self.snapshot).unwrap();
        fs::write(dir.journal().join("tail"), tail).unwrap();
        dir
    }
}

fn sorted(mut tracks: Vec<TrackId>) -> Vec<TrackId> {
    tracks.sort_by_key(ToString::to_string);
    tracks
}

fn open(dir: &Scratch) -> Journal {
    Journal::open(&dir.journal(), DeviceId::new()).unwrap()
}

fn liked(dir: &Scratch) -> Vec<TrackId> {
    sorted(open(dir).state().likes)
}

/// Копии испорченного хвоста рядом с журналом.
fn kept_aside(dir: &Scratch) -> Vec<PathBuf> {
    let mut found: Vec<PathBuf> = fs::read_dir(dir.journal())
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .filter(|path| path.file_name().unwrap().to_string_lossy().starts_with("tail.damaged-"))
        .collect();
    found.sort();
    found
}

/// Хвост без последних кадров и `extra` за ним.
fn cut_after(pristine: &Pristine, frames: usize, extra: &[u8]) -> Vec<u8> {
    let end = if frames == 0 { HEADER } else { pristine.spans()[frames - 1].1 };
    let mut bytes = pristine.tail[..end].to_vec();
    bytes.extend_from_slice(extra);
    bytes
}

/// Повтор находки 20: у второго из шести кадров испорчен старший байт длины.
#[test]
fn a_spoiled_length_in_the_middle_is_damage_and_is_kept_aside() {
    let pristine = Pristine::new();
    let mut bytes = pristine.tail.clone();
    bytes[pristine.spans()[1].0 + 3] = 0x7f;
    let dir = pristine.dir_with(&bytes);

    let mut journal = open(&dir);

    assert_eq!(sorted(journal.state().likes), pristine.first(1), "всё до порчи читается");
    let aside = kept_aside(&dir);
    assert_eq!(aside.len(), 1, "порча не проходит молча");
    assert_eq!(fs::read(&aside[0]).unwrap(), bytes, "копия — файл как он был: остальное можно достать оттуда");

    let fresh = TrackId::new();
    journal.record(&Op::Like { track: fresh }).unwrap();
    drop(journal);
    assert_eq!(liked(&dir), sorted([pristine.first(1), vec![fresh]].concat()), "журнал после ремонта пишется");
    assert_eq!(kept_aside(&dir).len(), 1, "второй копии нет: хвост начат заново");
}

/// Порча каждого байта длины у каждого кадра: до испорченного кадра не теряется
/// ничего; если за ним есть целые кадры, файл отложен рядом целиком.
#[test]
fn any_spoiled_length_keeps_what_came_before_it() {
    let pristine = Pristine::new();
    for (index, (start, _)) in pristine.spans().into_iter().enumerate() {
        for byte in 0..4 {
            for mask in [0x01_u8, 0x80, 0xff] {
                let mut bytes = pristine.tail.clone();
                bytes[start + byte] ^= mask;
                let dir = pristine.dir_with(&bytes);
                let what = format!("frame {index}, length byte {byte}, mask {mask:#x}");

                let kept = liked(&dir);

                assert_eq!(kept, pristine.first(index), "{what}: kept");
                let aside = kept_aside(&dir);
                if index < FRAMES - 1 {
                    assert_eq!(aside.len(), 1, "{what}: целые кадры за испорченным — это порча");
                    assert_eq!(fs::read(&aside[0]).unwrap(), bytes, "{what}: копия");
                } else {
                    assert!(aside.len() <= 1, "{what}: у последнего кадра за ним ничего нет");
                }
            }
        }
    }
}

/// Страж: оборванная запись последнего кадра (сбой питания) — не порча.
#[test]
fn a_frame_torn_in_the_middle_of_a_write_is_dropped_quietly() {
    let pristine = Pristine::new();
    let (start, end) = pristine.spans()[FRAMES - 1];
    for cut in [1, 4, 7, 8, 9, end - start - 1] {
        let dir = pristine.dir_with(&pristine.tail[..start + cut]);
        let what = format!("cut {cut} bytes into the last frame");

        let mut journal = open(&dir);

        assert_eq!(sorted(journal.state().likes), pristine.first(FRAMES - 1), "{what}");
        assert!(kept_aside(&dir).is_empty(), "{what}: сбой питания не порча");
        assert_eq!(fs::metadata(dir.journal().join("tail")).unwrap().len(), start as u64, "{what}: хвост обрезан");
        let fresh = TrackId::new();
        journal.record(&Op::Like { track: fresh }).unwrap();
        drop(journal);
        assert_eq!(liked(&dir).len(), FRAMES, "{what}: следующая запись ложится за хвостом");
    }
}

/// Страж: целиком записанный последний кадр с испорченной длиной ничем не
/// отличим от оборванного (за ним ничего нет) — теряется он один.
#[test]
fn a_spoiled_length_in_the_last_frame_costs_that_frame_only() {
    let pristine = Pristine::new();
    let mut bytes = pristine.tail.clone();
    bytes[pristine.spans()[FRAMES - 1].0 + 3] = 0x7f;
    let dir = pristine.dir_with(&bytes);

    assert_eq!(liked(&dir), pristine.first(FRAMES - 1));
    assert!(kept_aside(&dir).is_empty());
}

/// Страж: оборванный кадр, от которого записались одни нули, не «целые кадры».
/// Заголовок из восьми нулей (длина 0, сумма 0) выглядит кадром с пустыми
/// данными, но настоящий кадр пустым не бывает.
#[test]
fn zeros_in_a_torn_frame_are_not_frames() {
    let pristine = Pristine::new();
    let mut torn = Vec::new();
    torn.extend_from_slice(&300_u32.to_le_bytes());
    torn.extend_from_slice(&0xdead_beef_u32.to_le_bytes());
    torn.extend_from_slice(&[0_u8; 100]);
    let dir = pristine.dir_with(&cut_after(&pristine, FRAMES - 1, &torn));

    assert_eq!(liked(&dir), pristine.first(FRAMES - 1));
    assert!(kept_aside(&dir).is_empty());
}

/// Страж: оборванный кадр с мусором вместо данных — тоже оборванный конец.
#[test]
fn junk_in_a_torn_frame_is_still_a_torn_end() {
    let pristine = Pristine::new();
    let mut torn = Vec::new();
    torn.extend_from_slice(&500_u32.to_le_bytes());
    torn.extend_from_slice(&1_u32.to_le_bytes());
    let mut seed = 0x9e37_79b9_u32;
    for _ in 0..120 {
        seed ^= seed << 13;
        seed ^= seed >> 17;
        seed ^= seed << 5;
        torn.push(seed.to_le_bytes()[0]);
    }
    let dir = pristine.dir_with(&cut_after(&pristine, FRAMES - 1, &torn));

    assert_eq!(liked(&dir), pristine.first(FRAMES - 1));
    assert!(kept_aside(&dir).is_empty());
}

/// Страж: испорченные данные кадра с целой длиной по-прежнему откладываются.
#[test]
fn a_spoiled_body_is_kept_aside() {
    let pristine = Pristine::new();
    let mut bytes = pristine.tail.clone();
    bytes[pristine.spans()[2].0 + FRAME_HEADER] ^= 0xff;
    let dir = pristine.dir_with(&bytes);

    assert_eq!(liked(&dir), pristine.first(2));
    assert_eq!(kept_aside(&dir).len(), 1);
}
