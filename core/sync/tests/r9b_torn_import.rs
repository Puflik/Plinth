//! Р9б (`docs/work/r9b.md`, находка 4 ревью R1): оборванный кадр огромного импорта.
//! Одна правка с 30+ тысячами строк — кадр в мегабайты; место кончается или процесс
//! убит посреди записи, и в хвосте остаётся его начало. Это оборванный конец
//! (сбой посреди записи): его отрезают молча, как до Р1.11б, — места не нужно.
//! Поиск целого кадра за кадром, чьи данные не помещаются в файл (Р1.11б), на
//! таких байтах упирался в предел работы и объявлял порчу: копия на мегабайты
//! рядом, свёртка в снимок и, на полном диске, отказ открытия.
//!
//! Целый кадр за испорченной длиной — это кадр, который кончается ровно в конце
//! файла: проверенный суммой. Случайные байты оборванного кадра такого кадра не
//! содержат, и считать суммы кандидатов мегабайтами не нужно.
//!
//! Снимок записать нельзя (каталог на месте `snapshot.tmp`) — это и есть полный
//! телефон: хвост с кадром импорта не уходит в снимок, как не ушёл бы и при записи.

#![allow(clippy::unwrap_used, clippy::expect_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r9b_support;

use std::fs;
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

use common::{Scratch, playlist};
use plinth_library::model::PlaylistEntry;
use plinth_sync::journal::{Journal, Op};
use plinth_types::{DeviceId, PlaylistEntryId, Position, Timestamp, TrackId};
use r9b_support::{FRAME_HEADER, dir_with, kept_aside, liked, listing, sorted, spans};

/// Пакет импорта плейлиста из `lines` строк — как его пишет `import_playlist`:
/// `CreatePlaylist` и `AddEntry` с короткими позициями.
fn import(lines: usize) -> Vec<Op> {
    let list = playlist("Import");
    let mut ops = vec![Op::CreatePlaylist(list.clone())];
    ops.extend(Position::run(lines).into_iter().map(|position| {
        Op::AddEntry(PlaylistEntry {
            id: PlaylistEntryId::new(),
            playlist: list.id,
            track: TrackId::new(),
            position,
            added_at: Timestamp::from_millis(1_790_000_000_000),
        })
    }));
    ops
}

/// Журнал: лайк, затем один кадр импорта плейлиста из `lines` строк.
struct Imported {
    snapshot: Vec<u8>,
    tail: Vec<u8>,
    first: TrackId,
}

impl Imported {
    fn new(lines: usize) -> Self {
        let dir = Scratch::new();
        let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
        let first = TrackId::new();
        journal.record(&Op::Like { track: first }).unwrap();
        // Кадр в мегабайты переводит хвост за предел, и свёртка унесла бы его в снимок. На
        // почти полном телефоне снимок записать нельзя — кадр остаётся в хвосте: так он и
        // обрывается. Каталог на месте `snapshot.tmp` не даёт записать снимок.
        block_snapshots(&dir);
        journal.record_all(&import(lines)).unwrap();
        drop(journal);
        let tail = fs::read(dir.journal().join("tail")).unwrap();
        assert_eq!(spans(&tail).len(), 2, "лайк и кадр импорта");
        Self { snapshot: fs::read(dir.journal().join("snapshot")).unwrap(), tail, first }
    }

    fn big_frame(&self) -> (usize, usize) {
        spans(&self.tail)[1]
    }
}

/// Снимок записать нельзя: на месте временного файла каталог.
fn block_snapshots(dir: &Scratch) {
    fs::create_dir(dir.journal().join("snapshot.tmp")).unwrap();
}

/// Открытие в своём потоке со сторожем: затянувшийся поиск не должен вешать прогон.
/// Возвращает время открытия, лайки и лайк, записанный сразу после (в том же потоке).
fn open_and_write(dir: &Scratch, within: Duration) -> Result<(Duration, Vec<TrackId>, TrackId), String> {
    let (sender, receiver) = mpsc::channel();
    let journal_dir = dir.journal();
    let next = TrackId::new();
    thread::spawn(move || {
        let started = Instant::now();
        let outcome = Journal::open(&journal_dir, DeviceId::new()).and_then(|mut journal| {
            let took = started.elapsed();
            let likes = journal.state().likes;
            journal.record(&Op::Like { track: next }).map(|_| (took, likes))
        });
        let _ = sender.send(outcome.map_err(|error| error.to_string()));
    });
    match receiver.recv_timeout(within) {
        Ok(outcome) => outcome.map(|(took, likes)| (took, likes, next)),
        Err(_) => Err("открытие не уложилось в срок".to_owned()),
    }
}

/// Хвост с оборванным кадром открывается за секунды, без копий рядом и без свёртки,
/// всё до кадра цело, журнал пишется дальше и переживает перезапуск.
fn assert_cut_quietly(dir: &Scratch, first: TrackId, what: &str) {
    let opened = open_and_write(dir, Duration::from_secs(20));
    assert!(opened.is_ok(), "{what}: {opened:?}");
    let (took, likes, next) = opened.unwrap();
    assert!(took < Duration::from_secs(10), "{what}: открытие заняло {took:?}");
    assert_eq!(likes, vec![first], "{what}: до оборванного кадра всё цело");
    let names: Vec<String> = listing(dir).into_iter().map(|(name, _)| name).collect();
    assert!(
        names.iter().all(|name| !name.starts_with("tail.") && !name.starts_with("snapshot.damaged")),
        "{what}: оборванный конец — не порча, копий рядом быть не должно: {names:?}"
    );
    assert_eq!(liked(dir), sorted(vec![first, next]), "{what}: журнал пишется дальше и переживает перезапуск");
}

/// Оборванный кадр импорта на настоящих байтах: хвост отрезан без копии и без
/// свёртки, всё до него цело. На 60 тысячах строк (кадр в мегабайты) поиск Р1.11б
/// упирается в предел работы, как только за заголовком кадра остаются мегабайты.
/// Последний прогон — на «полном телефоне»: снимок не записать, ядро открывается.
#[test]
fn a_torn_import_frame_is_cut_quietly() {
    let imported = Imported::new(60_000);
    let (start, end) = imported.big_frame();
    assert!(end - start > 4 * 1024 * 1024, "кадр импорта должен быть в мегабайты: {}", end - start);
    for (share, full_disk) in [(0.1_f64, false), (0.5, false), (0.999, false), (0.5, true)] {
        let cut = start + FRAME_HEADER + ((end - start - FRAME_HEADER) as f64 * share) as usize;
        let dir = dir_with(&imported.snapshot, &imported.tail[..cut]);
        if full_disk {
            block_snapshots(&dir);
        }

        assert_cut_quietly(&dir, imported.first, &format!("обрыв на {share}, полный диск: {full_disk}"));
    }
}

/// Оборванный кадр из «похожих на кадры» байт: с каждого кратного четырём
/// смещения «длина» — 1 МБ и помещается в остаток. Хеширование кандидатов
/// съело бы предел работы; кадра ровно в конец файла нет ни у одного смещения.
#[test]
fn a_torn_frame_full_of_frame_like_bytes_is_cut_quietly() {
    let first = TrackId::new();
    let source = Scratch::new();
    {
        let mut journal = Journal::open(&source.journal(), DeviceId::new()).unwrap();
        journal.record(&Op::Like { track: first }).unwrap();
    }
    let snapshot = fs::read(source.journal().join("snapshot")).unwrap();
    let mut tail = fs::read(source.journal().join("tail")).unwrap();
    let payload = 8 * 1024 * 1024;
    tail.extend_from_slice(&u32::try_from(payload + 1).unwrap().to_le_bytes());
    tail.extend_from_slice(&[0xAB; 4]);
    tail.extend_from_slice(&[0_u8, 0, 0x10, 0].repeat(payload / 4));
    for full_disk in [false, true] {
        let dir = dir_with(&snapshot, &tail);
        if full_disk {
            block_snapshots(&dir);
        }

        assert_cut_quietly(&dir, first, &format!("байты, похожие на кадры, полный диск: {full_disk}"));
    }
}

/// Страж: длина кадра испорчена посреди хвоста, а за ним — настоящий кадр импорта в
/// сотни килобайт и ещё один кадр. Это по-прежнему порча: хвост из целых кадров
/// кончается ровно в конце файла, и найти его надо, не хешируя всё подряд.
#[test]
fn a_spoiled_length_before_a_big_frame_is_still_damage() {
    let dir = Scratch::new();
    let (before, middle, after) = (TrackId::new(), TrackId::new(), TrackId::new());
    {
        let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
        journal.record(&Op::Like { track: before }).unwrap();
        journal.record(&Op::Like { track: middle }).unwrap();
        // Снимок записать нельзя (см. `Imported::new`): кадр импорта остаётся в хвосте.
        block_snapshots(&dir);
        journal.record_all(&import(4_000)).unwrap();
        journal.record(&Op::Like { track: after }).unwrap();
    }
    let snapshot = fs::read(dir.journal().join("snapshot")).unwrap();
    let mut tail = fs::read(dir.journal().join("tail")).unwrap();
    let frames = spans(&tail);
    assert_eq!(frames.len(), 4);
    assert!(frames[2].1 - frames[2].0 > 256 * 1024, "кадр импорта крупный");
    tail[frames[1].0 + 3] = 0x7f;
    let dir = dir_with(&snapshot, &tail);

    let opened = open_and_write(&dir, Duration::from_secs(20));

    assert!(opened.is_ok(), "{opened:?}");
    let (took, likes, _) = opened.unwrap();
    assert!(took < Duration::from_secs(10), "открытие заняло {took:?}");
    assert_eq!(likes, vec![before], "всё до испорченного кадра читается");
    let copies = kept_aside(&dir);
    assert_eq!(copies.len(), 1, "кадры за испорченным — порча, а не оборванный конец");
    assert_eq!(fs::read(&copies[0]).unwrap(), tail, "копия — файл как он был");
}
