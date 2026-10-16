//! Общий каркас тестов Р9в (`docs/work/r9c.md`): том во временной папке, теги по
//! имени файла или из самого файла, скан и взгляд на каталог. Скан каркас зовёт
//! только прежним входом `scan`.

// Каждый файл тестов берёт из каркаса своё.
#![allow(dead_code)]
// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use plinth_library::db::Database;
use plinth_library::db::query::TrackSort;
use plinth_library::model::{Rating, TrackUserData};
use plinth_library::scan::{ArtistSplit, FolderConfig, RawTags, ScanReport, TagReader, Tags, normalized, scan};
use plinth_types::{CoreError, DeviceId, Timestamp, TrackId};

pub const FIRST_SCAN: i64 = 1_790_000_000_000;

/// Том во временной папке — корень, который скан получает от Kotlin.
pub struct Volume(pub PathBuf);

impl Volume {
    pub fn new() -> Self {
        let volume = Self(std::env::temp_dir().join(format!("plinth-r9c-{}", DeviceId::new())));
        fs::create_dir_all(&volume.0).unwrap();
        volume
    }

    pub fn put(&self, file: &str) {
        self.write(file, b"audio");
    }

    /// Пишет файл или переписывает его: размер и время изменения меняются вместе
    /// с содержимым, и скан видит «изменённый» файл.
    pub fn write(&self, file: &str, bytes: &[u8]) {
        let path = self.path(file);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, bytes).unwrap();
    }

    /// Переносит файл, как файловый менеджер: папка назначения создаётся.
    pub fn shift(&self, from: &str, to: &str) {
        let target = self.path(to);
        fs::create_dir_all(target.parent().unwrap()).unwrap();
        fs::rename(self.path(from), target).unwrap();
    }

    /// Копирует файл: оригинал остаётся на месте.
    pub fn copy(&self, from: &str, to: &str) {
        let target = self.path(to);
        fs::create_dir_all(target.parent().unwrap()).unwrap();
        fs::copy(self.path(from), target).unwrap();
    }

    pub fn remove(&self, file: &str) {
        fs::remove_file(self.path(file)).unwrap();
    }

    /// Путь по сегментам — с теми же разделителями, что у обхода.
    pub fn path(&self, file: &str) -> PathBuf {
        file.split('/').fold(self.0.clone(), |dir, segment| dir.join(segment))
    }

    /// Так файл лежит в каталоге (`source.local_uri`).
    pub fn uri(&self, file: &str) -> String {
        self.path(file).to_string_lossy().into_owned()
    }
}

impl Drop for Volume {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// Теги по имени файла; имена из `broken` не читаются. Имени нет ни там, ни
/// там — ошибка самого теста.
pub struct ByName {
    pub tags: Vec<(&'static str, Tags)>,
    pub broken: Vec<&'static str>,
}

pub fn tagged(tags: Vec<(&'static str, Tags)>) -> ByName {
    ByName { tags, broken: Vec::new() }
}

/// Читатель, у которого теги файла `name` не читаются (файл ещё копируется).
pub fn unreadable(name: &'static str) -> ByName {
    ByName { tags: Vec::new(), broken: vec![name] }
}

impl TagReader for ByName {
    fn read(&self, path: &Path) -> Result<Tags, CoreError> {
        let name = path.file_name().unwrap().to_string_lossy();
        if self.broken.iter().any(|broken| *broken == name) {
            return Err(CoreError::parse("not an MP3 stream"));
        }
        let found = self.tags.iter().find(|(file, _)| *file == name);
        Ok(found.map(|(_, tags)| tags.clone()).expect("the test gives no tags for this file"))
    }
}

/// Теги из самого файла: `название|исполнитель|альбом|секунды`. Правка тегов —
/// перезапись файла.
pub struct Embedded;

impl TagReader for Embedded {
    fn read(&self, path: &Path) -> Result<Tags, CoreError> {
        let text = fs::read_to_string(path).map_err(|error| CoreError::parse(error.to_string()))?;
        let mut fields = text.split('|');
        let mut next = || fields.next().unwrap_or_default().trim().to_owned();
        let (title, artist, album, seconds) = (next(), next(), next(), next());
        let raw = RawTags {
            title: Some(title),
            artist: vec![artist],
            album: Some(album).filter(|album| !album.is_empty()),
            duration: seconds.parse().ok().map(Duration::from_secs),
            ..RawTags::default()
        };
        Ok(normalized(raw, &ArtistSplit::default()))
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

/// Файл без тегов: название — имя файла, исполнителя и альбома нет.
pub fn untagged(seconds: u64) -> Tags {
    Tags { duration: Some(Duration::from_secs(seconds)), ..Tags::default() }
}

/// Скан томов `volumes` по папкам по умолчанию — `Music` и `Download`. Тома,
/// которых в списке нет, для скана вынуты.
pub fn run(db: &Database, volumes: &[&Volume], reader: &dyn TagReader, at: i64) -> ScanReport {
    let roots: Vec<PathBuf> = volumes.iter().map(|volume| volume.0.clone()).collect();
    scan(db, &roots, &FolderConfig::default(), reader, &mut |_| true, Timestamp::from_millis(at)).unwrap()
}

/// Трек файла `file` — в том числе пропавшего; файла в каталоге нет — `None`.
pub fn track_at(db: &Database, volume: &Volume, file: &str) -> Option<TrackId> {
    db.track_at(&volume.uri(file)).unwrap()
}

/// Видимые треки: ID и файл, из которого трек играет.
pub fn visible(db: &Database) -> Vec<(TrackId, Option<String>)> {
    db.track_list(TrackSort::Title, None).unwrap().into_iter().map(|row| (row.id, row.uri)).collect()
}

/// Лайк в проекции журнала — то, что видит скан в базе.
pub fn like(db: &Database, track: TrackId) {
    db.save_user_data(&TrackUserData { liked: true, ..TrackUserData::empty(track) }).unwrap();
}

/// Оценка вместо лайка: тоже «данные пользователя».
pub fn rate(db: &Database, track: TrackId) {
    db.save_user_data(&TrackUserData { rating: Some(Rating::new(4).unwrap()), ..TrackUserData::empty(track) }).unwrap();
}

pub fn liked(db: &Database, track: TrackId) -> bool {
    db.user_data(track).unwrap().liked
}

/// Между двумя заведёнными треками проходит миллисекунда: UUIDv7 упорядочен по
/// времени с точностью до неё, а «старше» в тестах значит «меньший ID».
pub fn tick() {
    std::thread::sleep(Duration::from_millis(5));
}
