//! Общий каркас тестов Р1.15 (`docs/work/r1-15.md`): тома во временных папках,
//! теги по имени файла и взгляд на каталог — что видно в списках и что
//! доступно. Скан каркас зовёт только прежним входом `scan`; новый вход
//! (`scan_volumes`) тесты берут сами.

// Каждый файл тестов берёт из каркаса своё.
#![allow(dead_code)]
// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use plinth_library::db::Database;
use plinth_library::db::query::TrackSort;
use plinth_library::scan::{
    ArtistSplit, FileNameOnly, FolderConfig, RawTags, ScanReport, TagReader, Tags, normalized, scan,
};
use plinth_types::{CoreError, DeviceId, Timestamp, TrackId};

pub const FIRST_SCAN: i64 = 1_790_000_000_000;
pub const LATER: i64 = FIRST_SCAN + 86_400_000;

/// Папка, в которой лежат несколько томов: как `/storage/Card` и `/storage/Card2`.
pub struct Base(pub PathBuf);

impl Base {
    pub fn new() -> Self {
        let base = Self(std::env::temp_dir().join(format!("plinth-r1-15-base-{}", DeviceId::new())));
        fs::create_dir_all(&base.0).unwrap();
        base
    }
}

impl Drop for Base {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// Том во временной папке — корень, который скан получает от Kotlin.
pub struct Volume {
    pub root: PathBuf,
    /// Куда том уходит, пока он не смонтирован.
    aside: PathBuf,
}

impl Volume {
    pub fn new() -> Self {
        Self::at(std::env::temp_dir().join(format!("plinth-r1-15-{}", DeviceId::new())))
    }

    /// Том `name` в папке `base`.
    pub fn inside(base: &Base, name: &str) -> Self {
        Self::at(base.0.join(name))
    }

    fn at(root: PathBuf) -> Self {
        fs::create_dir_all(&root).unwrap();
        let aside = std::env::temp_dir().join(format!("plinth-r1-15-aside-{}", DeviceId::new()));
        Self { root, aside }
    }

    /// Файл тома — пять байт; теги ему даёт `FileNameOnly` или `ByName`.
    pub fn put(&self, file: &str) {
        let path = self.path(file);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, b"audio").unwrap();
    }

    pub fn remove(&self, file: &str) {
        fs::remove_file(self.path(file)).unwrap();
    }

    /// Путь по сегментам — с теми же разделителями, что у обхода.
    pub fn path(&self, file: &str) -> PathBuf {
        file.split('/').fold(self.root.clone(), |dir, segment| dir.join(segment))
    }

    /// Так файл лежит в каталоге (`source.local_uri`).
    pub fn uri(&self, file: &str) -> String {
        self.path(file).to_string_lossy().into_owned()
    }

    /// Карту вынули или она ещё не смонтирована: каталога тома нет вовсе.
    pub fn unmount(&self) {
        fs::rename(&self.root, &self.aside).unwrap();
    }

    /// Точка монтирования осталась, но пуста: так выглядит том, который система
    /// ещё не отдала.
    pub fn unmount_leaving_mount_point(&self) {
        fs::rename(&self.root, &self.aside).unwrap();
        fs::create_dir_all(&self.root).unwrap();
    }

    /// Карта смонтирована: файлы на месте.
    pub fn mount(&self) {
        let _ = fs::remove_dir(&self.root);
        fs::rename(&self.aside, &self.root).unwrap();
    }
}

impl Drop for Volume {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
        let _ = fs::remove_dir_all(&self.aside);
    }
}

/// Теги по имени файла; имени нет в списке — ошибка самого теста.
pub struct ByName(pub Vec<(&'static str, Tags)>);

impl TagReader for ByName {
    fn read(&self, path: &Path) -> Result<Tags, CoreError> {
        let name = path.file_name().unwrap().to_string_lossy();
        let found = self.0.iter().find(|(file, _)| *file == name);
        Ok(found.map(|(_, tags)| tags.clone()).expect("the test gives no tags for this file"))
    }
}

/// Теги, сведённые так же, как их сведёт настоящее чтение.
pub fn song(title: &str, artist: &str, album: &str, seconds: u64) -> Tags {
    let raw = RawTags {
        title: Some(title.to_owned()),
        artist: vec![artist.to_owned()],
        album: Some(album.to_owned()),
        duration: Some(Duration::from_secs(seconds)),
        ..RawTags::default()
    };
    normalized(raw, &ArtistSplit::default())
}

/// Скан прежним входом: все тома готовы, папки — по умолчанию (`Music`, `Download`).
pub fn run(db: &Database, volumes: &[&Volume], reader: &dyn TagReader, at: i64) -> ScanReport {
    let roots: Vec<PathBuf> = volumes.iter().map(|volume| volume.root.clone()).collect();
    scan(db, &roots, &FolderConfig::default(), reader, &mut |_| true, Timestamp::from_millis(at)).unwrap()
}

/// То же, теги — из имени файла.
pub fn run_plain(db: &Database, volumes: &[&Volume], at: i64) -> ScanReport {
    run(db, volumes, &FileNameOnly, at)
}

/// Видимые треки: ID и файл, из которого трек играет. Недоступный не виден.
pub fn visible(db: &Database) -> Vec<(TrackId, Option<String>)> {
    db.track_list(TrackSort::Title, None).unwrap().into_iter().map(|row| (row.id, row.uri)).collect()
}

/// Названия видимых треков.
pub fn titles(db: &Database) -> Vec<String> {
    db.track_list(TrackSort::Title, None).unwrap().into_iter().map(|row| row.title).collect()
}

/// Доступен ли файл `uri` в каталоге; файла в каталоге нет — ошибка теста.
pub fn available(db: &Database, uri: &str) -> bool {
    db.known_local_files().unwrap()[uri].available
}

/// Трек файла `file` — в том числе недоступного; файла в каталоге нет — `None`.
pub fn track_at(db: &Database, volume: &Volume, file: &str) -> Option<TrackId> {
    db.track_at(&volume.uri(file)).unwrap()
}
