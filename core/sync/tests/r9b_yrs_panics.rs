//! Р9б (`docs/work/r9b.md`, «Не проверено» ревью R1, журнал): паника `yrs` на кадре
//! хвоста с верной суммой. `catch_unwind` стоял только у снимка; кадр хвоста, чьи
//! байты `yrs` разбирает паникой (разбор или применение), ронял `Journal::open`, а с
//! ним и открытие ядра при каждом запуске: сумма кадра верна, и убрать его некому.
//! Такой кадр — не обновление: он пропускается, остальные кадры читаются, а сам
//! файл откладывается рядом — свёртка при открытии унесла бы кадр без следа.
//!
//! Входы найдены перебором случайных байтов (скрытая в Cargo.lock версия `yrs`);
//! тест не зависит от того, паникует ли `yrs` на них сейчас: пропускается и то, что он
//! отвергает ошибкой.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r9b_support;

use std::fs;
use std::panic::{AssertUnwindSafe, catch_unwind};

use plinth_sync::journal::Journal;
use plinth_types::DeviceId;
use r9b_support::{Pristine, frame, kept_aside, liked, sorted, spans};

/// Разбор (`Update::decode_v1`) паникует.
const PANICS_WHILE_DECODING: [u8; 36] = [
    0xff, 0xe9, 0x68, 0xff, 0xc8, 0x6d, 0xa4, 0xff, 0x87, 0xff, 0xff, 0xff, 0xff, 0x7d, 0xff, 0xff, 0x73, 0xdf, 0x3d,
    0xff, 0x5c, 0xff, 0xff, 0x98, 0x86, 0xff, 0xe7, 0xff, 0x99, 0xff, 0x8a, 0x27, 0x1c, 0xff, 0xff, 0xff,
];

/// Разбирается, но применение (`apply_update`) паникует.
const PANICS_WHILE_APPLYING: [u8; 84] = [
    0x01, 0x02, 0x07, 0x01, 0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x02, 0x07, 0x05, 0x02, 0x07, 0x01, 0x01, 0x03, 0x01,
    0x04, 0x01, 0x04, 0x01, 0x07, 0x06, 0x07, 0x01, 0x00, 0x05, 0x04, 0x00, 0x03, 0x02, 0x00, 0x06, 0x03, 0x06, 0x06,
    0x06, 0x03, 0x05, 0x05, 0x02, 0x00, 0x06, 0x04, 0x04, 0x06, 0x02, 0x01, 0x02, 0x00, 0x01, 0x04, 0x04, 0x07, 0x06,
    0x02, 0x03, 0x02, 0x01, 0x01, 0x07, 0x07, 0x05, 0x02, 0x00, 0x04, 0x03, 0x05, 0x01, 0x06, 0x04, 0x06, 0x02, 0x07,
    0x05, 0x00, 0x06, 0x03, 0x04, 0x04, 0x04, 0x03,
];

/// Хвост из трёх лайков, между первым и вторым — кадр с верной суммой и данными `garbage`.
fn tail_with(pristine: &Pristine, garbage: &[u8]) -> Vec<u8> {
    let frames = spans(&pristine.tail);
    let mut tail = pristine.tail[..frames[0].1].to_vec();
    tail.extend_from_slice(&frame(garbage));
    tail.extend_from_slice(&pristine.tail[frames[1].0..]);
    tail
}

fn assert_skipped(garbage: &[u8], what: &str) {
    let pristine = Pristine::new(3);
    let tail = tail_with(&pristine, garbage);
    let dir = pristine.dir_with(&tail);

    let opened = catch_unwind(AssertUnwindSafe(|| {
        Journal::open(&dir.journal(), DeviceId::new()).map(|journal| sorted(journal.state().likes))
    }));

    assert_eq!(
        opened.ok().and_then(Result::ok),
        Some(pristine.all()),
        "{what}: кадр не обновление — он пропущен, а три лайка вокруг читаются"
    );
    let copies = kept_aside(&dir);
    assert_eq!(copies.len(), 1, "{what}: кадр не пропадает без следа — файл отложен рядом");
    assert_eq!(fs::read(&copies[0]).unwrap(), tail, "{what}: копия — файл как он был");
    assert_eq!(liked(&dir), pristine.all(), "{what}: второе открытие читает то же");
    assert_eq!(kept_aside(&dir).len(), 1, "{what}: второй копии нет: хвост начат заново");
}

#[test]
fn a_frame_that_makes_yrs_panic_while_decoding_is_skipped() {
    assert_skipped(&PANICS_WHILE_DECODING, "паника при разборе");
}

#[test]
fn a_frame_that_makes_yrs_panic_while_applying_is_skipped() {
    assert_skipped(&PANICS_WHILE_APPLYING, "паника при применении");
}

/// Кадр, который `yrs` отвергает ошибкой, не молчаливее паники: тоже откладывается
/// рядом, а не уходит в никуда со свёрткой.
#[test]
fn a_frame_yrs_rejects_with_an_error_is_kept_aside_too() {
    assert_skipped(&[0xFF; 64], "ошибка разбора");
}
