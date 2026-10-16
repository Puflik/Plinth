//! Р9в, находка 19 ревью R1 (`docs/review/r1-review.md`, `docs/work/r9c.md`):
//! «Восстановить» посреди «Пересканировать». Скан снимает список знакомых
//! файлов с ID треков в начале, а перепривязка (`rekey_track`) посреди скана
//! меняет ID под тем же файлом. Запись по старому ID не находила трек, но отметка
//! файла всё равно ложилась — новые теги не попадали в каталог и больше не
//! перечитывались; перенесённый в этом скане файл не сводился с пропавшим.
//!
//! «Восстановить» здесь — `rekey_track` из колбэка хода скана в фазе чтения:
//! теги к этому часу уже прочитаны, запись ещё не началась.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod r9c_support;

use std::cell::Cell;
use std::path::PathBuf;

use plinth_library::db::Database;
use plinth_library::scan::{FolderConfig, ScanPhase, ScanReport, scan};
use plinth_types::{Timestamp, TrackId};
use r9c_support::{Embedded, FIRST_SCAN, Volume, run, track_at};

/// Скан, в котором в конце чтения тегов `old` становится `new`, как при
/// «Восстановить» из настроек.
fn rescan_with_a_restore(db: &Database, volume: &Volume, old: TrackId, new: TrackId, at: i64) -> ScanReport {
    let done = Cell::new(false);
    let mut restore = |progress: plinth_library::scan::ScanProgress| {
        if progress.phase == ScanPhase::Reading && !done.replace(true) {
            db.rekey_track(old, new).unwrap();
        }
        true
    };
    let roots: Vec<PathBuf> = vec![volume.0.clone()];
    let report =
        scan(db, &roots, &FolderConfig::default(), &Embedded, &mut restore, Timestamp::from_millis(at)).unwrap();
    assert!(done.get(), "колбэк чтения не вызывался: тест ничего не проверил");
    report
}

/// Сценарий ревью: теги файла поправили, пересканирование идёт, посреди него
/// «Восстановить». Новые теги ложатся на трек под новым ID, а следующий скан не
/// видит ничего нового.
#[test]
fn a_restore_during_a_rescan_does_not_lose_the_new_tags() {
    let volume = Volume::new();
    volume.write("Music/Song.mp3", b"Song|Band|Album|180");
    let db = Database::open_in_memory().unwrap();
    run(&db, &[&volume], &Embedded, FIRST_SCAN);
    let track = track_at(&db, &volume, "Music/Song.mp3").unwrap();

    volume.write("Music/Song.mp3", b"Song (Remastered)|Band|Album|180");
    let journal_id = TrackId::new();
    let report = rescan_with_a_restore(&db, &volume, track, journal_id, FIRST_SCAN + 1);

    assert_eq!(report.changed, 1, "{report:?}");
    let renamed = db.track(journal_id).unwrap().expect("трек под ID журнала");
    assert_eq!(renamed.title, "Song (Remastered)", "новые теги легли на перепривязанный трек");
    assert_eq!(track_at(&db, &volume, "Music/Song.mp3"), Some(journal_id));

    let next = run(&db, &[&volume], &Embedded, FIRST_SCAN + 2);
    assert_eq!(next.changed, 0, "файл отмечен прочитанным, и отметка не обманывает: теги на месте ({next:?})");
    assert_eq!(db.track(journal_id).unwrap().unwrap().title, "Song (Remastered)");
}

/// Файл, перенесённый в этом скане, сводится с пропавшим, даже если ID пропавшего
/// сменился посреди скана: он остаётся тем же треком — уже под новым ID.
#[test]
fn a_file_moved_during_a_restore_still_takes_its_track() {
    let volume = Volume::new();
    volume.write("Music/Song.mp3", b"Song|Band|Album|180");
    let db = Database::open_in_memory().unwrap();
    run(&db, &[&volume], &Embedded, FIRST_SCAN);
    let track = track_at(&db, &volume, "Music/Song.mp3").unwrap();

    volume.shift("Music/Song.mp3", "Download/Song.mp3");
    let journal_id = TrackId::new();
    let report = rescan_with_a_restore(&db, &volume, track, journal_id, FIRST_SCAN + 1);

    assert_eq!((report.moved, report.added), (1, 0), "{report:?}");
    assert_eq!(
        track_at(&db, &volume, "Download/Song.mp3"),
        Some(journal_id),
        "перенесённый файл — перепривязанный трек"
    );
    assert_eq!(db.track(track).unwrap(), None);
    let next = run(&db, &[&volume], &Embedded, FIRST_SCAN + 2);
    assert_eq!((next.moved, next.added, next.missing, next.changed), (0, 0, 0, 0), "{next:?}");
}
