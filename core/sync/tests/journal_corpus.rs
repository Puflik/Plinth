//! F3: журнал каждого выпуска (`tests/fixtures/journal/<выпуск>/`) читается
//! текущим кодом — и каталог журнала, который вернул Auto Backup, и копия из
//! папки человека (C4). Эталоны делает `examples/make_journal_fixture.rs`,
//! по одному на выпуск; первый — 0.2.0, с него журнал и появился. Базе такой
//! корпус есть с B3 (`plinth-library`, `tests/migrations.rs`), но базу журнал
//! пересоберёт, а журнал не пересоберёт ничто.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;

use std::fs;
use std::path::{Path, PathBuf};

use common::{Scratch, Song, memory_db, scan, track_at};
use plinth_library::db::Database;
use plinth_sync::journal::{Journal, OP_SCHEMA, catch_up, relink};
use plinth_sync::mirror::{Found, inspect, restore};
use plinth_types::{DeviceId, Timestamp};

/// Выпуски с журналом и версия операций, которой писал каждый. Поменялся
/// формат журнала — эталон следующего выпуска и строка сюда.
const RELEASES: [(&str, u8); 1] = [("0.2.0", 2)];

/// Те же песни, что в эталоне, но на SD-карте: пути журнал не хранит.
const KINO: Song = Song {
    path: "/storage/1A2B-3C4D/Музыка/Кино/Кукушка.mp3",
    title: "Кукушка",
    artist: "Кино",
    album: Some("Звезда по имени Солнце"),
    duration_ms: Some(398_000),
};

const QUEEN: Song = Song {
    path: "/storage/1A2B-3C4D/Музыка/Queen/Bohemian Rhapsody.mp3",
    title: "Bohemian Rhapsody",
    artist: "Queen",
    album: Some("A Night at the Opera"),
    duration_ms: Some(354_000),
};

fn fixture(release: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/journal").join(release)
}

#[test]
fn every_release_with_a_journal_has_its_fixture() {
    let mut found: Vec<String> =
        fs::read_dir(fixture("")).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect();
    found.sort();
    let mut expected: Vec<&str> = RELEASES.iter().map(|&(release, _)| release).collect();
    expected.sort_unstable();

    assert_eq!(found, expected, "run: cargo run -p plinth-sync --example make_journal_fixture -- <release>");
    assert_eq!(RELEASES[RELEASES.len() - 1].1, OP_SCHEMA, "the journal changed: a fixture of the next release");
}

/// Auto Backup вернул каталог журнала, а базы и `device` нет: сетевой трек
/// возвращается сразу, файлы — после скана.
#[test]
fn a_journal_from_the_backup_of_every_release_comes_back() {
    for (release, _) in RELEASES {
        let dir = Scratch::new();
        copy_dir(&fixture(release).join("journal"), &dir.journal());
        let journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
        let db = memory_db();

        catch_up(&journal, &db).unwrap();
        relink(&journal, &db).unwrap();
        assert_eq!(liked_titles(&db), ["OH DOCTOR"], "{release}: an online track needs no scan");
        scan(&db, &[QUEEN, KINO]);
        relink(&journal, &db).unwrap();

        assert_came_back(&db, release);
    }
}

/// Копия из папки человека (C4): осмотр видит её данные, «Восстановить»
/// возвращает их на треки этой установки.
#[test]
fn a_copy_of_every_release_is_found_and_restored() {
    for (release, _) in RELEASES {
        let copy = fs::read(fixture(release).join("copy.journal")).unwrap();
        let dir = Scratch::new();
        let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
        let db = memory_db();
        scan(&db, &[QUEEN, KINO]);

        let found = inspect(&journal, &[&copy]).unwrap();
        restore(&mut journal, &db, &[&copy]).unwrap();

        let written_at = Some(Timestamp::from_millis(1_790_500_000_000));
        assert_eq!(
            found,
            Found { likes: 2, playlists: 1, plays: 1, written_at, news: true, unreadable: 0 },
            "{release}"
        );
        assert_came_back(&db, release);
    }
}

/// Лайки, плейлист в своём порядке и прослушивание — на треках каталога.
fn assert_came_back(db: &Database, release: &str) {
    let mut liked = liked_titles(db);
    liked.sort();
    assert_eq!(liked, ["OH DOCTOR", "Кукушка"], "{release}");
    let playlists = db.playlists().unwrap();
    assert_eq!(playlists.len(), 1, "{release}");
    assert_eq!(playlists[0].name, "Дорога", "{release}");
    let order: Vec<String> =
        db.entries(playlists[0].id).unwrap().into_iter().map(|e| db.track(e.track).unwrap().unwrap().title).collect();
    assert_eq!(order, ["Bohemian Rhapsody", "Кукушка"], "{release}");
    assert_eq!(db.user_data(track_at(db, KINO)).unwrap().play_count, 1, "{release}");
}

fn liked_titles(db: &Database) -> Vec<String> {
    db.liked_tracks().unwrap().into_iter().map(|row| row.title).collect()
}

fn copy_dir(from: &Path, to: &Path) {
    fs::create_dir_all(to).unwrap();
    for entry in fs::read_dir(from).unwrap() {
        let path = entry.unwrap().path();
        fs::copy(&path, to.join(path.file_name().unwrap())).unwrap();
    }
}
