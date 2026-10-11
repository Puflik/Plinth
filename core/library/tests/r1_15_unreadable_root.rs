//! Р1.15, чтение плана Opus: корень тома, который система отдала готовым, но
//! сведения о котором не прочлись (ввод-вывод умирающей карты, права), — это
//! «не увидели», а не «вынутая карта». Вынутая — когда корня нет на диске
//! (`NotFound`; тест `a_root_that_is_gone_from_the_disk_is_an_ejected_card`).
//!
//! Осечку делает папка над корнем без прав (`---`): сведения о корне — `EACCES`.
//! Windows так не умеет, поэтому файл только для Unix; в CI он идёт на Linux.
//! Суперпользователю закрытая папка не помеха — тогда тест сам замечает это и
//! ничего не проверяет.

#![cfg(unix)]
// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod r1_15_volumes;

use std::fs::{self, Permissions};
use std::io::ErrorKind;
use std::os::unix::fs::PermissionsExt;

use plinth_library::db::Database;

use r1_15_volumes::{Base, FIRST_SCAN, LATER, Volume, available, run_plain, track_at, visible};

/// Папка над томами без прав; при выходе из области видимости права
/// возвращаются, иначе временную папку не стереть.
struct Locked<'a>(&'a Base);

impl<'a> Locked<'a> {
    fn new(base: &'a Base) -> Self {
        fs::set_permissions(&base.0, Permissions::from_mode(0o000)).unwrap();
        Self(base)
    }
}

impl Drop for Locked<'_> {
    fn drop(&mut self) {
        let _ = fs::set_permissions(&self.0.0, Permissions::from_mode(0o755));
    }
}

/// Том отдан готовым, а сведения о его корне не прочлись: треки на месте и в
/// списках, отчёт не считает ни файл, ни том пропавшим; когда корень снова
/// читается, следующий скан находит файл нетронутым — тот же трек.
#[test]
fn a_volume_whose_root_cannot_be_read_is_not_an_ejected_card() {
    let base = Base::new();
    let card = Volume::inside(&base, "Card");
    card.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    run_plain(&db, &[&card], FIRST_SCAN);
    let track = track_at(&db, &card, "Music/Song.mp3").unwrap();

    let lock = Locked::new(&base);
    if !matches!(fs::metadata(&card.root), Err(error) if error.kind() == ErrorKind::PermissionDenied) {
        eprintln!("skipped: the permissions of the folder do not stop this user");
        return;
    }
    let report = run_plain(&db, &[&card], LATER);
    drop(lock);

    assert_eq!((report.missing, report.missing_volumes), (0, 0), "том отдан готовым, его просто не прочли");
    assert!(available(&db, &card.uri("Music/Song.mp3")));
    assert_eq!(visible(&db), [(track, Some(card.uri("Music/Song.mp3")))]);

    let next = run_plain(&db, &[&card], LATER + 1);
    assert_eq!((next.found, next.added, next.changed, next.returned, next.missing), (1, 0, 0, 0, 0));
    assert_eq!(visible(&db), [(track, Some(card.uri("Music/Song.mp3")))]);
}
