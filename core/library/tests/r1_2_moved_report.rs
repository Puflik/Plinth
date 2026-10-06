//! Р1.2: итог скана различает перенесённые файлы. Файл, найденный на новом
//! месте, — `moved`: он не «добавлен» и не «пропал», даже если его прежний
//! путь пропал ещё прошлым сканом. Следующий скан о нём уже молчит.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use plinth_library::db::Database;
use plinth_library::scan::{ArtistSplit, FolderConfig, RawTags, ScanReport, TagReader, Tags, normalized, scan};
use plinth_types::{CoreError, DeviceId, Timestamp};

/// Том во временной папке — корень, который скан получает от Kotlin.
struct Volume(PathBuf);

impl Volume {
    fn new() -> Self {
        let volume = Self(std::env::temp_dir().join(format!("plinth-r1-2-report-{}", DeviceId::new())));
        fs::create_dir_all(&volume.0).unwrap();
        volume
    }

    fn put(&self, file: &str) {
        let path = self.path(file);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, b"audio").unwrap();
    }

    fn shift(&self, from: &str, to: &str) {
        let target = self.path(to);
        fs::create_dir_all(target.parent().unwrap()).unwrap();
        fs::rename(self.path(from), target).unwrap();
    }

    fn path(&self, file: &str) -> PathBuf {
        file.split('/').fold(self.0.clone(), |dir, segment| dir.join(segment))
    }
}

impl Drop for Volume {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// Теги по имени файла: название — имя без расширения, исполнитель один.
struct Named;

impl TagReader for Named {
    fn read(&self, path: &Path) -> Result<Tags, CoreError> {
        let title = path.file_stem().unwrap().to_string_lossy().into_owned();
        let raw = RawTags {
            title: Some(title),
            artist: vec!["Band".to_owned()],
            album: Some("Album".to_owned()),
            duration: Some(Duration::from_secs(180)),
            ..RawTags::default()
        };
        Ok(normalized(raw, &ArtistSplit::default()))
    }
}

fn run(db: &Database, volume: &Volume) -> ScanReport {
    scan(db, std::slice::from_ref(&volume.0), &FolderConfig::default(), &Named, &mut |_| true, Timestamp::now())
        .unwrap()
}

/// (found, added, changed, returned, missing, moved)
fn counts(report: ScanReport) -> (u32, u32, u32, u32, u32, u32) {
    (report.found, report.added, report.changed, report.returned, report.missing, report.moved)
}

#[test]
fn a_moved_file_is_counted_as_moved_and_then_as_nothing() {
    let volume = Volume::new();
    volume.put("Download/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    run(&db, &volume);

    volume.shift("Download/Song.mp3", "Music/Song.mp3");
    let moved = run(&db, &volume);
    let again = run(&db, &volume);

    assert_eq!(counts(moved), (1, 0, 0, 0, 0, 1));
    assert_eq!(counts(again), (1, 0, 0, 0, 0, 0));
}

#[test]
fn a_file_gone_earlier_and_found_now_is_moved_not_added() {
    let volume = Volume::new();
    volume.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    run(&db, &volume);

    volume.shift("Music/Song.mp3", "Other/Song.mp3");
    let gone = run(&db, &volume);
    volume.shift("Other/Song.mp3", "Music/New/Song.mp3");
    let found = run(&db, &volume);

    assert_eq!(counts(gone), (0, 0, 0, 0, 1, 0));
    assert_eq!(counts(found), (1, 0, 0, 0, 0, 1));
}

/// Перенос, новый файл и пропажа в одном скане — каждый в своём счёте.
#[test]
fn moved_added_and_missing_files_are_told_apart() {
    let volume = Volume::new();
    volume.put("Music/a.mp3");
    volume.put("Music/b.mp3");
    let db = Database::open_in_memory().unwrap();
    run(&db, &volume);

    volume.shift("Music/a.mp3", "Download/a.mp3");
    volume.shift("Music/b.mp3", "Other/b.mp3");
    volume.put("Music/c.mp3");
    let report = run(&db, &volume);

    assert_eq!(counts(report), (2, 1, 0, 0, 1, 1));
}
