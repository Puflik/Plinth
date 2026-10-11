//! Р1.15 (находка 4 ревью FFI/сканера): запись папки, которую обход не смог
//! прочесть, — это «не увидели», а не «нет». Файл, на котором вышла осечка
//! (ввод-вывод SD, гонка с синхронизатором), остаётся доступным и не отдаёт
//! свой трек копии; следующий скан находит его нетронутым.
//!
//! Осечку делает папка без права поиска (`r--`): имена записей читаются, а
//! сведения о каждой — `EACCES`. Windows так не умеет (там сведения приходят
//! вместе с именами и не ошибаются), поэтому файл только для Unix; в CI он идёт
//! на Linux. Суперпользователю закрытая папка не помеха — тогда тест сам
//! замечает это и ничего не проверяет.

#![cfg(unix)]
// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod r1_15_volumes;

use std::fs::{self, Permissions};
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

use plinth_library::db::Database;

use r1_15_volumes::{ByName, FIRST_SCAN, LATER, Volume, available, run, run_plain, song, track_at, visible};

/// Права на папку; при выходе из области видимости возвращаются, иначе временную
/// папку не стереть.
struct Locked(PathBuf);

impl Locked {
    fn with(dir: PathBuf, mode: u32) -> Self {
        fs::set_permissions(&dir, Permissions::from_mode(mode)).unwrap();
        Self(dir)
    }

    /// `r--`: имена читаются, записи — нет.
    fn no_search(dir: PathBuf) -> Self {
        Self::with(dir, 0o444)
    }

    /// `---`: папку не прочесть вовсе.
    fn no_access(dir: PathBuf) -> Self {
        Self::with(dir, 0o000)
    }
}

impl Drop for Locked {
    fn drop(&mut self) {
        let _ = fs::set_permissions(&self.0, Permissions::from_mode(0o755));
    }
}

/// Папку `dir` читать можно, а сведения о `file` в ней получить нельзя?
fn entries_fail(dir: &Path, file: &str) -> bool {
    fs::read_dir(dir).is_ok() && fs::symlink_metadata(dir.join(file)).is_err()
}

/// Сценарий ревью: на файле вышла осечка. Он остаётся доступным и в списках,
/// отчёт не считает его пропавшим; когда осечки нет, следующий скан находит
/// его нетронутым — тот же трек, теги не перечитаны.
#[test]
fn a_file_whose_entry_cannot_be_read_is_not_missing() {
    let phone = Volume::new();
    phone.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    run_plain(&db, &[&phone], FIRST_SCAN);
    let track = track_at(&db, &phone, "Music/Song.mp3").unwrap();
    let lock = Locked::no_search(phone.path("Music"));
    if !entries_fail(&phone.path("Music"), "Song.mp3") {
        eprintln!("skipped: the permissions of the folder do not stop this user");
        return;
    }

    let report = run_plain(&db, &[&phone], LATER);

    assert_eq!(report.missing, 0, "файл на месте, его просто не прочли");
    assert!(available(&db, &phone.uri("Music/Song.mp3")));
    assert_eq!(visible(&db), [(track, Some(phone.uri("Music/Song.mp3")))]);

    drop(lock);
    let next = run_plain(&db, &[&phone], LATER + 1);
    assert_eq!((next.found, next.added, next.changed, next.returned, next.missing), (1, 0, 0, 0, 0));
    assert_eq!(visible(&db), [(track, Some(phone.uri("Music/Song.mp3")))]);
}

/// Осечка щадит только свою запись. Рядом лежал файл, который удалили на деле:
/// он по-прежнему пропал. Папку целиком «не знать» нельзя — одна вечная
/// осечка ослепила бы скан на всю папку.
#[test]
fn an_unreadable_entry_spares_only_itself() {
    let phone = Volume::new();
    phone.put("Music/Kept.mp3");
    phone.put("Music/Deleted.mp3");
    let db = Database::open_in_memory().unwrap();
    run_plain(&db, &[&phone], FIRST_SCAN);
    phone.remove("Music/Deleted.mp3");
    let _lock = Locked::no_search(phone.path("Music"));
    if !entries_fail(&phone.path("Music"), "Kept.mp3") {
        eprintln!("skipped: the permissions of the folder do not stop this user");
        return;
    }

    let report = run_plain(&db, &[&phone], LATER);

    assert_eq!(report.missing, 1, "пропал только удалённый");
    assert!(available(&db, &phone.uri("Music/Kept.mp3")));
    assert!(!available(&db, &phone.uri("Music/Deleted.mp3")));
}

/// Р1.2 и осечка: копия песни рядом с файлом, который просто не прочли, —
/// новый трек. Оригинал не «пропал», чтобы копия забрала его трек себе.
#[test]
fn a_copy_beside_an_unreadable_file_is_a_new_track() {
    let phone = Volume::new();
    phone.put("Music/Song.mp3");
    let reader = ByName(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    let db = Database::open_in_memory().unwrap();
    run(&db, &[&phone], &reader, FIRST_SCAN);
    let original = track_at(&db, &phone, "Music/Song.mp3").unwrap();
    let _lock = Locked::no_search(phone.path("Music"));
    if !entries_fail(&phone.path("Music"), "Song.mp3") {
        eprintln!("skipped: the permissions of the folder do not stop this user");
        return;
    }

    phone.put("Download/Song.mp3");
    let report = run(&db, &[&phone], &reader, LATER);

    assert_eq!((report.added, report.moved, report.missing), (1, 0, 0));
    let copy = track_at(&db, &phone, "Download/Song.mp3").unwrap();
    assert_ne!(copy, original, "оригинал цел: копия — новый трек");
    assert_eq!(track_at(&db, &phone, "Music/Song.mp3"), Some(original));
    assert_eq!(visible(&db).len(), 2);
}

/// Страж: папка, которую не прочесть вовсе, работает как прежде — её файлы не
/// пропали, папка попала в отчёт.
#[test]
fn a_folder_that_cannot_be_listed_keeps_its_files() {
    let phone = Volume::new();
    phone.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    run_plain(&db, &[&phone], FIRST_SCAN);
    let _lock = Locked::no_access(phone.path("Music"));
    if fs::read_dir(phone.path("Music")).is_ok() {
        eprintln!("skipped: the permissions of the folder do not stop this user");
        return;
    }

    let report = run_plain(&db, &[&phone], LATER);

    assert_eq!((report.unreadable_folders, report.missing), (1, 0));
    assert!(available(&db, &phone.uri("Music/Song.mp3")));
}
