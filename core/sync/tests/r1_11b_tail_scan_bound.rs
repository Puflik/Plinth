//! Р1.11б, поправка Opus при чтении плана (`docs/work/r1-11b.md`, «Решение, А», п. 3):
//! поиск целого кадра за подозрительным кадром ограничен по работе. Оборванный при
//! сбое питания кадр большого импорта — это мегабайты, и наивный поиск (CRC с каждого
//! смещения, чья «длина» помещается в остаток) на неудачных байтах хеширует
//! терабайты: открытие журнала висело бы часами. Открытие укладывается в секунды при
//! любых байтах, а всё, что до оборванного кадра, цело. На коде до задачи тест
//! зелёный: поиска там нет вовсе.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;

use std::fs;
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

use common::Scratch;
use plinth_sync::journal::{Journal, Op};
use plinth_types::{DeviceId, TrackId};

/// Сколько данных у оборванного кадра.
const PAYLOAD: usize = 8 * 1024 * 1024;

fn sorted(mut tracks: Vec<TrackId>) -> Vec<TrackId> {
    tracks.sort_by_key(ToString::to_string);
    tracks
}

#[test]
fn a_torn_frame_full_of_frame_like_bytes_opens_in_seconds() {
    let source = Scratch::new();
    let first = TrackId::new();
    {
        let mut journal = Journal::open(&source.journal(), DeviceId::new()).unwrap();
        journal.record(&Op::Like { track: first }).unwrap();
    }
    let snapshot = fs::read(source.journal().join("snapshot")).unwrap();
    let mut tail = fs::read(source.journal().join("tail")).unwrap();
    // Оборванный кадр: заголовок целый, данных меньше заявленного. Данные — четвёрки
    // [0, 0, 0x10, 0]: с каждого кратного четырём смещения «длина» — 1 МБ и помещается
    // в остаток, так что кандидат в кадры почти на каждом шаге.
    tail.extend_from_slice(&u32::try_from(PAYLOAD + 1).unwrap().to_le_bytes());
    tail.extend_from_slice(&[0xAB; 4]);
    tail.extend_from_slice(&[0u8, 0, 0x10, 0].repeat(PAYLOAD / 4));

    let dir = Scratch::new();
    fs::create_dir_all(dir.journal()).unwrap();
    fs::write(dir.journal().join("snapshot"), &snapshot).unwrap();
    fs::write(dir.journal().join("tail"), &tail).unwrap();

    // Открытие — в своём потоке со сторожем: наивный поиск не должен повесить прогон,
    // тест падает через десять секунд, а не через часы.
    let (sender, receiver) = mpsc::channel();
    let journal_dir = dir.journal();
    let second = TrackId::new();
    thread::spawn(move || {
        let started = Instant::now();
        let mut journal = Journal::open(&journal_dir, DeviceId::new()).unwrap();
        let took = started.elapsed();
        let likes = journal.state().likes;
        journal.record(&Op::Like { track: second }).unwrap();
        sender.send((took, likes)).unwrap();
    });
    let (took, likes) = receiver.recv_timeout(Duration::from_secs(10)).expect("открытие не уложилось в 10 с");
    assert!(took < Duration::from_secs(10), "открытие заняло {took:?}");
    assert_eq!(likes, vec![first], "до оборванного кадра всё цело");

    let reopened = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    assert_eq!(sorted(reopened.state().likes), sorted(vec![first, second]), "журнал пишется дальше");
}
