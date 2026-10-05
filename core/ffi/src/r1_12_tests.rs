//! Р1.12 (`docs/work/r1-12.md`): ядро открывается, не дожидаясь проверки
//! целостности; проверка идёт после открытия, а найденную порчу чинит
//! следующее открытие — лайки возвращает журнал.

use std::fs;
use std::io::{Seek, SeekFrom, Write};

use plinth_library::db::{Database, IntegrityCheck};
use plinth_library::scan::{FoundFile, ScannedFile, Tags, write};
use plinth_types::{Format, Timestamp, TrackId};

use crate::session::{Core, DATABASE};
use crate::testing::Scratch;
use crate::types::IntegrityOutcome;

fn scanned(i: usize) -> ScannedFile {
    ScannedFile {
        file: FoundFile {
            uri: format!("/storage/emulated/0/Music/Album {}/{i:05} Track {i}.mp3", i % 100),
            folder: format!("Music/Album {}/", i % 100),
            format: Format::Mp3,
            modified_at: Timestamp::from_millis(1_700_000_000_000 + i64::try_from(i).unwrap()),
            size: 4_000_000,
        },
        known: None,
        tags: Tags {
            title: Some(format!("Track {i}")),
            artist: Some(format!("Artist {}", i % 40)),
            artists: vec![format!("Artist {}", i % 40)],
            album: Some(format!("Album {}", i % 100)),
            ..Tags::default()
        },
        readable: true,
    }
}

/// Ядро с лайком и каталогом в 2 000 треков; следующее открытие — двадцатый
/// запуск, очередь плановой проверки. `damaged` — три страницы ближе к концу
/// файла становятся мусором: открытие их не читает, найти порчу может только
/// полная проверка.
fn before_the_twentieth_launch(dir: &Scratch, track: TrackId, damaged: bool) {
    Core::open(dir.path()).unwrap().like(track).unwrap();
    let db = dir.0.join(DATABASE);
    {
        let opened = Database::open(&db, IntegrityCheck::Skip).unwrap();
        let files: Vec<ScannedFile> = (0..2_000).map(scanned).collect();
        for batch in files.chunks(200) {
            opened.db.in_transaction(|d| write(d, batch, Timestamp::from_millis(1_700_000_000_000))).unwrap();
        }
    }
    for _ in 3..20 {
        drop(Database::open(&db, IntegrityCheck::Skip).unwrap());
    }
    if damaged {
        assert!(!dir.0.join(format!("{DATABASE}-wal")).exists(), "the file must hold every page");
        let length = fs::metadata(&db).unwrap().len();
        let mut file = fs::OpenOptions::new().write(true).open(&db).unwrap();
        file.seek(SeekFrom::Start(length - 4096 * 6)).unwrap();
        file.write_all(&[0xFF; 4096 * 3]).unwrap();
    }
}

/// Двадцатый запуск — очередь проверки, но ядро открывается, не дожидаясь её:
/// порчу, которую открытие не читает, на открытии никто не ищет.
#[test]
fn the_core_opens_without_waiting_for_the_check() {
    let dir = Scratch::new();
    let track = TrackId::new();
    before_the_twentieth_launch(&dir, track, true);

    let core = Core::open(dir.path()).unwrap();

    assert!(!core.startup_report().unwrap().database_recovered, "the check ran on the open path");
    assert!(core.user_data(track).unwrap().liked);
}

/// Проверка после открытия нашла порчу — следующее открытие откладывает базу
/// и собирает её заново, лайк возвращает журнал. Дальше запуски обычные.
#[test]
fn damage_found_after_opening_is_repaired_on_the_next_open() {
    let dir = Scratch::new();
    let track = TrackId::new();
    before_the_twentieth_launch(&dir, track, true);
    let core = Core::open(dir.path()).unwrap();

    let outcome = core.check_integrity().unwrap();

    assert_eq!(outcome, IntegrityOutcome::Damaged);
    assert!(core.user_data(track).unwrap().liked, "the core keeps working until the next open");
    drop(core);
    let core = Core::open(dir.path()).unwrap();
    let report = core.startup_report().unwrap();
    assert!(report.database_recovered);
    assert!(report.restored_from_journal);
    assert!(core.user_data(track).unwrap().liked);
    assert_eq!(core.check_integrity().unwrap(), IntegrityOutcome::NotDue);
    drop(core);
    assert!(!Core::open(dir.path()).unwrap().startup_report().unwrap().database_recovered);
}

/// Проверка — только в свою очередь и раз за запуск; цела — `Healthy`, метки нет.
#[test]
fn the_check_runs_only_when_due_and_once() {
    let dir = Scratch::new();
    before_the_twentieth_launch(&dir, TrackId::new(), false);
    let core = Core::open(dir.path()).unwrap();

    let first = core.check_integrity().unwrap();
    let second = core.check_integrity().unwrap();

    assert_eq!(first, IntegrityOutcome::Healthy);
    assert_eq!(second, IntegrityOutcome::NotDue, "once per launch");
    drop(core);
    let core = Core::open(dir.path()).unwrap();
    assert_eq!(core.check_integrity().unwrap(), IntegrityOutcome::NotDue, "launch 21 is not due");
    assert!(!dir.0.join(format!("{DATABASE}.damaged")).exists());
}
