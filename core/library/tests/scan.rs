//! D1: сканер на подготовленном дереве файлов — полный цикл: первый скан,
//! повторный без изменений, правка, пропажа и возвращение файла, исключённая
//! папка, остановка, нечитаемые теги, запись пачками.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use plinth_library::db::Database;
use plinth_library::db::query::TrackSort;
use plinth_library::scan::{FileNameOnly, FolderConfig, ScanPhase, ScanProgress, ScanReport, TagReader, Tags, scan};
use plinth_types::{CoreError, DeviceId, Timestamp};

struct Tree(PathBuf);

impl Tree {
    fn new(files: &[&str]) -> Self {
        let tree = Self(std::env::temp_dir().join(format!("plinth-scan-{}", DeviceId::new())));
        fs::create_dir_all(&tree.0).unwrap();
        for file in files {
            tree.write(file, b"audio");
        }
        tree
    }

    fn write(&self, file: &str, bytes: &[u8]) {
        let path = self.0.join(file);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, bytes).unwrap();
    }

    /// Путь по сегментам — с теми же разделителями, что у обхода.
    fn path(&self, file: &str) -> PathBuf {
        file.split('/').fold(self.0.clone(), |dir, segment| dir.join(segment))
    }
}

impl Drop for Tree {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn run(db: &Database, tree: &Tree, config: &FolderConfig, reader: &dyn TagReader) -> ScanReport {
    scan(db, std::slice::from_ref(&tree.0), config, reader, &mut |_| true, Timestamp::from_millis(1_790_000_000_000))
        .unwrap()
}

fn titles(db: &Database) -> Vec<String> {
    db.track_list(TrackSort::Title, None).unwrap().into_iter().map(|row| row.title).collect()
}

fn available(db: &Database, tree: &Tree, file: &str) -> bool {
    db.known_local_files().unwrap()[&tree.path(file).to_string_lossy().into_owned()].available
}

#[test]
fn the_first_scan_adds_files_and_a_second_changes_nothing() {
    let tree = Tree::new(&["Music/Queen/Bohemian Rhapsody.flac", "Music/Creep.mp3", "Download/talk.opus"]);
    let db = Database::open_in_memory().unwrap();

    let first = run(&db, &tree, &FolderConfig::default(), &FileNameOnly);
    let second = run(&db, &tree, &FolderConfig::default(), &FileNameOnly);

    assert_eq!((first.found, first.added), (3, 3));
    assert_eq!(titles(&db), vec!["Bohemian Rhapsody", "Creep", "talk"]);
    assert_eq!((second.found, second.added, second.changed, second.missing), (3, 0, 0, 0));
    assert_eq!(titles(&db).len(), 3);
}

/// Изменённый файл перечитывается и остаётся тем же треком: лайки и плейлисты на месте.
#[test]
fn an_edited_file_keeps_its_track() {
    let tree = Tree::new(&["Music/a.mp3"]);
    let db = Database::open_in_memory().unwrap();
    run(&db, &tree, &FolderConfig::default(), &FileNameOnly);
    let before = db.track_list(TrackSort::Title, None).unwrap()[0].id;

    tree.write("Music/a.mp3", b"re-tagged audio");
    let report = run(&db, &tree, &FolderConfig::default(), &Titled("Retitled"));

    let rows = db.track_list(TrackSort::Title, None).unwrap();
    assert_eq!(report.changed, 1);
    assert_eq!(rows.len(), 1);
    assert_eq!((rows[0].id, rows[0].title.as_str()), (before, "Retitled"));
}

/// Пропавший файл не удаляется: трек остаётся недоступным и оживает, когда файл вернули.
#[test]
fn a_missing_file_turns_unavailable_and_comes_back() {
    let tree = Tree::new(&["Music/a.mp3", "Music/b.mp3"]);
    let db = Database::open_in_memory().unwrap();
    run(&db, &tree, &FolderConfig::default(), &FileNameOnly);
    let aside = tree.path("a.mp3.aside");

    fs::rename(tree.path("Music/a.mp3"), &aside).unwrap();
    let gone = run(&db, &tree, &FolderConfig::default(), &FileNameOnly);
    let unavailable = !available(&db, &tree, "Music/a.mp3");
    fs::rename(&aside, tree.path("Music/a.mp3")).unwrap();
    let back = run(&db, &tree, &FolderConfig::default(), &FileNameOnly);

    assert_eq!(gone.missing, 1);
    assert!(unavailable);
    assert_eq!(titles(&db).len(), 2);
    assert_eq!(back.returned, 1);
    assert!(available(&db, &tree, "Music/a.mp3"));
}

#[test]
fn an_excluded_folder_hides_its_tracks() {
    let tree = Tree::new(&["Music/a.mp3", "Music/Podcasts/ep.mp3"]);
    let db = Database::open_in_memory().unwrap();
    run(&db, &tree, &FolderConfig::default(), &FileNameOnly);

    let config = FolderConfig { excluded: vec!["Music/Podcasts/".to_owned()], ..FolderConfig::default() };
    let report = run(&db, &tree, &config, &FileNameOnly);

    assert_eq!(report.missing, 1);
    assert!(!available(&db, &tree, "Music/Podcasts/ep.mp3"));
    assert!(available(&db, &tree, "Music/a.mp3"));
}

/// Файл с битыми тегами — не повод его потерять: он в каталоге под именем файла.
#[test]
fn a_file_with_unreadable_tags_is_added_by_its_name() {
    let tree = Tree::new(&["Music/broken.flac"]);
    let db = Database::open_in_memory().unwrap();

    let report = run(&db, &tree, &FolderConfig::default(), &Broken);

    assert_eq!((report.added, report.unreadable_files), (1, 1));
    assert_eq!(titles(&db), vec!["broken"]);
}

/// Остановка во время чтения тегов: в каталог не попадает ничего.
#[test]
fn a_scan_stopped_while_reading_writes_nothing() {
    let tree = Tree::new(&["Music/a.mp3", "Music/b.mp3"]);
    let db = Database::open_in_memory().unwrap();

    let report = scan(
        &db,
        std::slice::from_ref(&tree.0),
        &FolderConfig::default(),
        &FileNameOnly,
        &mut |p: ScanProgress| p.phase != ScanPhase::Reading,
        Timestamp::from_millis(1),
    )
    .unwrap();

    assert!(report.stopped);
    assert!(titles(&db).is_empty());
}

/// Большая папка пишется пачками, и ход записи виден до конца.
#[test]
fn many_files_are_written_in_batches() {
    let names: Vec<String> = (0..450).map(|i| format!("Music/track {i:03}.mp3")).collect();
    let tree = Tree::new(&names.iter().map(String::as_str).collect::<Vec<_>>());
    let db = Database::open_in_memory().unwrap();
    let mut writing: Vec<(u32, u32)> = Vec::new();

    let report = scan(
        &db,
        std::slice::from_ref(&tree.0),
        &FolderConfig::default(),
        &FileNameOnly,
        &mut |p: ScanProgress| {
            if p.phase == ScanPhase::Writing {
                writing.push((p.done, p.total));
            }
            true
        },
        Timestamp::from_millis(1),
    )
    .unwrap();

    assert_eq!(report.added, 450);
    assert_eq!(titles(&db).len(), 450);
    assert_eq!(writing, vec![(200, 450), (400, 450), (450, 450)]);
}

/// Теги из «читателя» — в каталог: название, исполнитель, длительность.
#[test]
fn tags_reach_the_catalog() {
    let tree = Tree::new(&["Music/01.flac"]);
    let db = Database::open_in_memory().unwrap();

    run(&db, &tree, &FolderConfig::default(), &Titled("Creep"));

    let row = &db.track_list(TrackSort::Title, None).unwrap()[0];
    assert_eq!((row.title.as_str(), row.artist_credit.as_str()), ("Creep", "Radiohead"));
    assert_eq!(row.duration, Some(Duration::from_secs(238)));
}

struct Titled(&'static str);

impl TagReader for Titled {
    fn read(&self, _path: &Path) -> Result<Tags, CoreError> {
        Ok(Tags {
            title: Some(self.0.to_owned()),
            artist: Some("Radiohead".to_owned()),
            duration: Some(Duration::from_secs(238)),
        })
    }
}

struct Broken;

impl TagReader for Broken {
    fn read(&self, _path: &Path) -> Result<Tags, CoreError> {
        Err(CoreError::parse("not a FLAC stream"))
    }
}
