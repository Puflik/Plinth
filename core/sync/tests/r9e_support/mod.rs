//! Общее для тестов Р9д (`docs/work/r9e.md`): установка приложения — папка журнала и каталог в
//! памяти, — копия её журнала под именем `<установка>.journal`, «Ядро открылось, а снимок не читался»
//! (`started_over`: то же, что `Core::open` делает в `session.rs`) и «Восстановить» из копий с именами,
//! как их отдаёт папка человека.

#![allow(dead_code, reason = "каждый тестовый файл берёт свою часть")]
#![allow(clippy::unwrap_used, reason = "помощники тестов: упавший unwrap и есть упавший тест")]

use std::fs;

use plinth_library::db::Database;
use plinth_library::model::{Playlist, PlaylistEntry, Rating};
use plinth_sync::journal::{Journal, Op, catch_up, describe_missing, record_and_project, relink, start_over};
use plinth_sync::mirror::{Candidate, Found, Restored, copy_of, inspect_named, restore_named};
use plinth_types::{DeviceId, PlaylistEntryId, Position, Timestamp, TrackId};

use crate::common::{Scratch, Song, memory_db, play, playlist, scan, track_at};

/// Когда записана копия: на ход дела не влияет.
pub const AT: Timestamp = Timestamp::from_millis(1_790_307_000_000);

/// Начало отсчёта прослушиваний.
pub const PLAYED: i64 = 1_790_000_000_000;

const fn song(path: &'static str, title: &'static str, duration_ms: u64) -> Song {
    Song { path, title, artist: "Band", album: Some("Album"), duration_ms: Some(duration_ms) }
}

pub const SONG: Song = song("/storage/emulated/0/Music/Band/Song.mp3", "Song", 180_000);
pub const OTHER: Song = song("/storage/emulated/0/Music/Band/Other.mp3", "Other", 200_000);
pub const THIRD: Song = song("/storage/emulated/0/Music/Band/Third.mp3", "Third", 220_000);
pub const FOURTH: Song = song("/storage/emulated/0/Music/Band/Fourth.mp3", "Fourth", 240_000);

/// Копия из папки: имя файла и содержимое.
pub type File<'a> = (&'a str, &'a [u8]);

pub fn stars(count: u8) -> Rating {
    Rating::new(count).unwrap()
}

/// Одна установка приложения: журнал в своей папке и свой каталог.
pub struct Installation {
    pub dir: Scratch,
    pub device: DeviceId,
    pub journal: Journal,
    pub db: Database,
}

impl Installation {
    pub fn new() -> Self {
        let dir = Scratch::new();
        let device = DeviceId::new();
        let journal = Journal::open(&dir.journal(), device).unwrap();
        Self { dir, device, journal, db: memory_db() }
    }

    /// Установка, просканировавшая `songs`.
    pub fn scanned(songs: &[Song]) -> Self {
        let install = Self::new();
        install.scan(songs);
        install
    }

    /// Скан находит ещё файлы: каждый — новый трек.
    pub fn scan(&self, songs: &[Song]) {
        scan(&self.db, songs);
    }

    pub fn track(&self, song: Song) -> TrackId {
        track_at(&self.db, song)
    }

    pub fn record(&mut self, op: &Op) {
        record_and_project(&mut self.journal, &self.db, op).unwrap();
    }

    pub fn like(&mut self, track: TrackId) {
        self.record(&Op::Like { track });
    }

    pub fn rate(&mut self, track: TrackId, count: u8) {
        self.record(&Op::Rate { track, rating: Some(stars(count)) });
    }

    /// Прослушивание, начатое через `n` секунд после `PLAYED`.
    pub fn play(&mut self, track: TrackId, n: i64) {
        self.record(&Op::Play(play(track, PLAYED + n * 1_000, 300, Some(180))));
    }

    /// Новый плейлист с одной записью — `track`.
    pub fn list(&mut self, name: &str, track: TrackId) -> Playlist {
        let list = playlist(name);
        self.record(&Op::CreatePlaylist(list.clone()));
        self.record(&Op::AddEntry(PlaylistEntry {
            id: PlaylistEntryId::new(),
            playlist: list.id,
            track,
            position: Position::first(),
            added_at: Timestamp::now(),
        }));
        list
    }

    /// Имя файла копии в папке: так его называет `Core::mirror_copy`.
    pub fn name(&self) -> String {
        format!("{}.journal", self.device)
    }

    /// Копия журнала, как её кладёт в папку человека.
    pub fn copy(&self) -> Vec<u8> {
        copy_of(&self.journal, AT)
    }

    /// Ядро открывается, а снимок журнала не читается (сумма не сошлась): установка получает новое имя,
    /// журнал начинается заново из базы (`start_over` с прежним именем), у треков каталога дописываются
    /// паспорта — так делает `Core::open`. База и каталог — те же.
    pub fn started_over(self) -> Self {
        let Self { dir, device, mut journal, db } = self;
        journal.compact().unwrap();
        drop(journal);
        let path = dir.journal().join("snapshot");
        let mut bytes = fs::read(&path).unwrap();
        let last = bytes.len() - 1;
        bytes[last] ^= 0xFF;
        fs::write(&path, &bytes).unwrap();
        let renewed = DeviceId::new();
        start_over(&dir.journal(), renewed, &db, device).unwrap();
        let mut journal = Journal::open(&dir.journal(), renewed).unwrap();
        catch_up(&journal, &db).unwrap();
        describe_missing(&mut journal, &db).unwrap();
        Self { dir, device: renewed, journal, db }
    }

    /// «Восстановить» из копий папки: слияние живых, паспорта прежних, пересборка проекции, перепривязка.
    pub fn restore(&mut self, files: &[File<'_>]) -> Restored {
        restore_named(&mut self.journal, &self.db, &candidates(files)).unwrap()
    }

    /// Осмотр копий папки — «Восстановить?».
    pub fn inspect(&self, files: &[File<'_>]) -> Found {
        inspect_named(&self.journal, &candidates(files)).unwrap()
    }

    /// Перепривязка после скана.
    pub fn relink(&mut self) -> usize {
        relink(&mut self.journal, &self.db).unwrap()
    }

    /// Названия лайкнутых треков, видимых в списке «Любимое», по алфавиту.
    pub fn liked_titles(&self) -> Vec<String> {
        let mut titles: Vec<String> = self.db.liked_tracks().unwrap().into_iter().map(|row| row.title).collect();
        titles.sort();
        titles
    }

    pub fn rating(&self, track: TrackId) -> Option<Rating> {
        self.db.user_data(track).unwrap().rating
    }

    pub fn playlist_names(&self) -> Vec<String> {
        self.db.playlists().unwrap().into_iter().map(|list| list.name).collect()
    }
}

fn candidates<'a>(files: &[File<'a>]) -> Vec<Candidate<'a>> {
    files.iter().map(|&(name, bytes)| Candidate { name: Some(name), bytes }).collect()
}
