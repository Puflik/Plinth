//! Общее для тестов Р9г (`docs/work/r9d.md`): установка приложения — папка журнала
//! и каталог в памяти, — копия её журнала и восстановление из копии, как это
//! делает человек. Установки создаются одна за другой, поэтому ID треков у более
//! поздней новее: ID — UUIDv7.

#![allow(dead_code, reason = "каждый тестовый файл берёт свою часть")]
#![allow(clippy::unwrap_used, reason = "помощники тестов: упавший unwrap и есть упавший тест")]

use plinth_library::db::Database;
use plinth_library::model::{Playlist, PlaylistEntry, Rating};
use plinth_sync::journal::{Journal, Op, record_and_project, relink};
use plinth_sync::mirror::{Restored, copy_of, restore};
use plinth_types::{Availability, DeviceId, PlaylistEntryId, Position, Timestamp, TrackId};

use crate::common::{Scratch, Song, memory_db, play, playlist, scan, track_at};

/// Когда записана копия: на ход дела не влияет.
pub const AT: Timestamp = Timestamp::from_millis(1_790_307_000_000);

/// Начало отсчёта прослушиваний: n-е начато через n секунд после него.
pub const PLAYED: i64 = 1_790_000_000_000;

pub const SONG: Song = Song {
    path: "/storage/emulated/0/Music/Band/Song.mp3",
    title: "Song",
    artist: "Band",
    album: Some("Album"),
    duration_ms: Some(180_000),
};

pub const OTHER: Song = Song {
    path: "/storage/emulated/0/Music/Band/Other.mp3",
    title: "Other",
    artist: "Band",
    album: Some("Album"),
    duration_ms: Some(200_000),
};

/// Та же песня другим файлом: теги те же, путь другой.
pub const SONG_COPY: Song = Song { path: "/storage/emulated/0/Backup/Band/Song.mp3", ..SONG };

pub fn stars(count: u8) -> Rating {
    Rating::new(count).unwrap()
}

pub fn entry(playlist: &Playlist, track: TrackId) -> PlaylistEntry {
    PlaylistEntry {
        id: PlaylistEntryId::new(),
        playlist: playlist.id,
        track,
        position: Position::first(),
        added_at: Timestamp::now(),
    }
}

/// Одна установка приложения: журнал в своей папке и свой каталог.
pub struct Installation {
    _dir: Scratch,
    pub journal: Journal,
    pub db: Database,
}

impl Installation {
    pub fn new() -> Self {
        let dir = Scratch::new();
        let journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
        Self { _dir: dir, journal, db: memory_db() }
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

    /// Файл песни пропал: источник недоступен, трек скрыт из списков.
    pub fn make_unavailable(&self, song: Song) {
        self.set_availability(song, Availability::Unavailable);
    }

    /// Итог проверки файла `song`, как её делает скан.
    pub fn set_availability(&self, song: Song, availability: Availability) {
        let source = self.db.known_local_files().unwrap()[song.path].source;
        self.db.set_availability(source, availability, Timestamp::now()).unwrap();
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

    /// Прослушивание, начатое через `n` секунд после `PLAYED`; засчитывается.
    pub fn play(&mut self, track: TrackId, n: i64) {
        self.record(&Op::Play(play(track, PLAYED + n * 1_000, 300, Some(180))));
    }

    /// Новый плейлист с одной записью — `track`.
    pub fn list(&mut self, name: &str, track: TrackId) -> Playlist {
        let list = playlist(name);
        self.record(&Op::CreatePlaylist(list.clone()));
        self.record(&Op::AddEntry(entry(&list, track)));
        list
    }

    /// Копия журнала, как её кладёт в папку человека.
    pub fn copy(&self) -> Vec<u8> {
        copy_of(&self.journal, AT)
    }

    /// «Восстановить» из копии: слияние, пересборка проекции, перепривязка.
    pub fn restore(&mut self, copy: &[u8]) -> Restored {
        restore(&mut self.journal, &self.db, &[copy]).unwrap()
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

    /// Сколько прослушиваний в истории базы у трека (любых, не только засчитанных).
    pub fn plays_of(&self, track: TrackId) -> usize {
        self.db.recent_plays(u32::MAX).unwrap().iter().filter(|event| event.track == track).count()
    }

    /// Треки записей плейлиста `name`, по одному на запись.
    pub fn tracks_in(&self, name: &str) -> Vec<TrackId> {
        let list = self.db.playlists().unwrap().into_iter().find(|list| list.name == name).unwrap();
        self.db.entries(list.id).unwrap().into_iter().map(|entry| entry.track).collect()
    }
}
