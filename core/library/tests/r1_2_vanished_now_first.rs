//! Р1.2, ревью, круг 1: при равном сходстве новое место берёт файл,
//! пропавший в этом скане, а не давно пропавший дубликат. Иначе лайкнутая
//! копия, которую перенесли, отдала бы свой путь копии, удалённой ещё
//! прошлым сканом, — и лайк остался бы на скрытом треке (находка 2 в малом).

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use plinth_library::db::Database;
use plinth_library::db::query::TrackSort;
use plinth_library::scan::{ArtistSplit, FolderConfig, RawTags, TagReader, Tags, normalized, scan};
use plinth_types::{CoreError, DeviceId, Timestamp, TrackId};

/// Том во временной папке — корень, который скан получает от Kotlin.
struct Volume(PathBuf);

impl Volume {
    fn new() -> Self {
        let volume = Self(std::env::temp_dir().join(format!("plinth-r1-2-now-{}", DeviceId::new())));
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

    fn uri(&self, file: &str) -> String {
        self.path(file).to_string_lossy().into_owned()
    }
}

impl Drop for Volume {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// Одни и те же теги у любого файла: две копии одной песни.
struct SameSong;

impl TagReader for SameSong {
    fn read(&self, _path: &Path) -> Result<Tags, CoreError> {
        let raw = RawTags {
            title: Some("Song".to_owned()),
            artist: vec!["Band".to_owned()],
            album: Some("Album".to_owned()),
            duration: Some(Duration::from_secs(180)),
            ..RawTags::default()
        };
        Ok(normalized(raw, &ArtistSplit::default()))
    }
}

fn run(db: &Database, volume: &Volume) {
    scan(db, std::slice::from_ref(&volume.0), &FolderConfig::default(), &SameSong, &mut |_| true, Timestamp::now())
        .unwrap();
}

fn track_at(db: &Database, volume: &Volume, file: &str) -> Option<TrackId> {
    db.track_at(&volume.uri(file)).unwrap()
}

fn visible(db: &Database) -> Vec<(TrackId, Option<String>)> {
    db.track_list(TrackSort::Title, None).unwrap().into_iter().map(|row| (row.id, row.uri)).collect()
}

/// Две копии песни; ту, что заведена раньше (меньший ID), удалили одним
/// сканом, оставшуюся перенесли следующим. Перенесённый файл — оставшийся
/// трек; удалённый дубликат скрыт на своём пути.
#[test]
fn a_moved_file_takes_the_track_that_vanished_now_not_an_old_duplicate() {
    let volume = Volume::new();
    volume.put("Download/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    run(&db, &volume);
    volume.put("Music/Song.mp3");
    run(&db, &volume);
    let duplicate = track_at(&db, &volume, "Download/Song.mp3").unwrap();
    let kept = track_at(&db, &volume, "Music/Song.mp3").unwrap();
    assert!(duplicate < kept, "дубликат заведён раньше — при равном сходстве он был бы первым");

    volume.shift("Download/Song.mp3", "Other/Song.mp3");
    run(&db, &volume);
    volume.shift("Music/Song.mp3", "Music/New/Song.mp3");
    run(&db, &volume);

    assert_eq!(track_at(&db, &volume, "Music/New/Song.mp3"), Some(kept), "перенесённый — оставшийся трек");
    assert_eq!(track_at(&db, &volume, "Download/Song.mp3"), Some(duplicate), "дубликат — на своём пути");
    assert_eq!(visible(&db), [(kept, Some(volume.uri("Music/New/Song.mp3")))]);
}
