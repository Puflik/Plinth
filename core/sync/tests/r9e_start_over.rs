//! Р9д (`docs/work/r9e.md`, ревью R1, находка 5 и «Из Р9г»): `start_over` помечает прежнюю
//! установку сменённой — если что-то перенёс из базы, — и пишет перенесённое пачками, а не одной
//! правкой: `Journal::record_all` растёт квадратом от числа прослушиваний в пачке.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod common;
mod r9e_support;

use std::fs;
use std::time::{Duration, Instant};

use common::{Scratch, dump, memory_db, play, playlist};
use plinth_library::db::Database;
use plinth_library::model::PlaylistEntry;
use plinth_sync::journal::{
    CatchUp, Journal, JournalState, Op, catch_up, record_and_project, record_and_project_all, start_over,
};
use plinth_sync::mirror::decode;
use plinth_types::{DeviceId, PlaylistEntryId, Position, Timestamp, TrackId};
use r9e_support::{Installation, OTHER, PLAYED, SONG};

/// Портит снимок журнала в `dir`: сумма не сходится.
fn spoil(dir: &Scratch) {
    let path = dir.journal().join("snapshot");
    let mut bytes = fs::read(&path).unwrap();
    let last = bytes.len() - 1;
    bytes[last] ^= 0xFF;
    fs::write(&path, &bytes).unwrap();
}

/// Журнал с `n` прослушиваниями, записанными пачками по 500 (быстрее, чем по одному), свёрнутый
/// в снимок; снимок испорчен. База отражает всё.
fn long_history(n: usize) -> (Scratch, Database) {
    let dir = Scratch::new();
    let db = memory_db();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    let tracks: Vec<TrackId> = (0..50).map(|_| TrackId::new()).collect();
    for start in (0..n).step_by(500) {
        let ops: Vec<Op> = (start..(start + 500).min(n))
            .map(|i| {
                let at = PLAYED + i64::try_from(i).unwrap() * 1_000;
                Op::Play(play(tracks[i % tracks.len()], at, 300, Some(180)))
            })
            .collect();
        record_and_project_all(&mut journal, &db, &ops).unwrap();
    }
    journal.compact().unwrap();
    drop(journal);
    spoil(&dir);
    (dir, db)
}

/// Журнал начат заново из базы — прежняя установка помечена сменённой: пометка лежит в самом
/// журнале, переживает открытие, едет в копию и сливается в чужой журнал.
#[test]
fn starting_over_marks_the_replaced_installation() {
    let mut old = Installation::scanned(&[SONG, OTHER]);
    let (song, other) = (old.track(SONG), old.track(OTHER));
    old.like(song);
    old.rate(other, 5);
    old.list("Road", other);
    let replaced = old.device;

    let started = old.started_over();

    assert_eq!(started.journal.superseded().into_iter().collect::<Vec<_>>(), [replaced]);
    assert_eq!(started.journal.state().superseded, [replaced]);
    // Пометку принесла копия — чужой журнал её знает.
    let copy = started.copy();
    let mut elsewhere = Installation::new();
    elsewhere.journal.merge_snapshot(decode(&copy).unwrap().snapshot).unwrap();
    assert_eq!(elsewhere.journal.superseded().into_iter().collect::<Vec<_>>(), [replaced]);
    // Открытие её не теряет.
    let Installation { dir, device, journal, .. } = started;
    drop(journal);
    let reopened = Journal::open(&dir.journal(), device).unwrap();
    assert_eq!(reopened.superseded().into_iter().collect::<Vec<_>>(), [replaced]);
}

/// В базе пусто — переносить нечего: журнал пуст, как у нового, без пометки. Копия прежней
/// установки остаётся единственным путём назад (как у `Core::open` с пропавшей базой).
#[test]
fn with_nothing_carried_nothing_is_marked() {
    let dir = Scratch::new();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    record_and_project(&mut journal, &memory_db(), &Op::Like { track: TrackId::new() }).unwrap();
    journal.compact().unwrap();
    drop(journal);
    spoil(&dir);
    let device = DeviceId::new();

    let carried = start_over(&dir.journal(), device, &memory_db(), DeviceId::new()).unwrap();
    let journal = Journal::open(&dir.journal(), device).unwrap();

    assert_eq!(carried, 0);
    assert!(journal.superseded().is_empty());
    assert_eq!(journal.state(), JournalState::default());
    assert_eq!(journal.mark().seq, 0);
}

/// `start_over` возвращает, сколько записей перенесено из базы: пометка в это число не входит.
#[test]
fn the_marker_is_not_counted_as_carried() {
    let (dir, db) = long_history(3);
    let device = DeviceId::new();

    let carried = start_over(&dir.journal(), device, &db, DeviceId::new()).unwrap();

    assert_eq!(carried, 3);
    let state = Journal::open(&dir.journal(), device).unwrap().state();
    assert_eq!((state.plays.len(), state.superseded.len()), (3, 1));
}

/// Пачки не теряют и не путают ничего на границах: прослушиваний больше двух пачек, а плейлист создан
/// в одной пачке и наполняется в нескольких следующих (`AddEntry` проверяет, что плейлист есть). Журнал
/// из нового снимка даёт ту же проекцию, что была в базе.
#[test]
fn a_long_history_is_carried_whole_across_chunk_boundaries() {
    let dir = Scratch::new();
    let db = memory_db();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    let track = TrackId::new();
    let road = playlist("Road");
    let mut ops: Vec<Op> = (0..1_300).map(|i| Op::Play(play(track, PLAYED + i * 1_000, 300, Some(180)))).collect();
    ops.push(Op::CreatePlaylist(road.clone()));
    ops.extend((0..1_200).map(|_| {
        Op::AddEntry(PlaylistEntry {
            id: PlaylistEntryId::new(),
            playlist: road.id,
            track,
            position: Position::first(),
            added_at: Timestamp::now(),
        })
    }));
    for chunk in ops.chunks(500) {
        record_and_project_all(&mut journal, &db, chunk).unwrap();
    }
    journal.compact().unwrap();
    drop(journal);
    let before = dump(&db);
    spoil(&dir);
    let device = DeviceId::new();

    let carried = start_over(&dir.journal(), device, &db, DeviceId::new()).unwrap();
    let journal = Journal::open(&dir.journal(), device).unwrap();

    assert_eq!(carried, 1_300 + 1 + 1_200, "every play, the playlist and every entry");
    let state = journal.state();
    assert_eq!((state.plays.len(), state.playlists.len(), state.entries.len()), (1_300, 1, 1_200));
    assert_eq!(catch_up(&journal, &db).unwrap(), CatchUp::Rebuilt);
    assert_eq!(dump(&db), before);
}

/// Находка Р9г: `Journal::record_all` растёт квадратом от размера пачки (12 000 прослушиваний одной
/// правкой — 5,3 с, пачками по 500 — 0,23 с), а `start_over` писал всё одной транзакцией. На `main`
/// 24 000 прослушиваний в базе — около 22 с (debug), в пачках — меньше секунды. Предел щедрый: тест
/// отличает рост квадратом от линейного, а не меряет железо.
#[test]
fn starting_over_a_long_history_takes_linear_time() {
    let (dir, db) = long_history(24_000);
    let device = DeviceId::new();
    let started = Instant::now();

    let carried = start_over(&dir.journal(), device, &db, DeviceId::new()).unwrap();

    let took = started.elapsed();
    assert_eq!(carried, 24_000);
    assert!(took < Duration::from_secs(10), "start_over took {took:?} on 24 000 plays");
    // Не только быстро, но и верно: все прослушивания на месте.
    assert_eq!(Journal::open(&dir.journal(), device).unwrap().state().plays.len(), 24_000);
}
