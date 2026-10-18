//! Общее для тестов Р9б (`docs/work/r9b.md`): журнал с известным хвостом, разбор
//! байтов `tail`, копии рядом, сбой записи, имитированный правами файла.
//!
//! Заголовок файла журнала — 22 байта (`store.rs`): магия `PLNJ`, формат, вид,
//! идентификатор журнала (8, LE), номер операции (8, LE). Кадр хвоста — длина
//! (u32, LE), CRC32 данных и сами данные.

#![allow(dead_code, reason = "каждый тестовый файл берёт свою часть")]
#![allow(clippy::unwrap_used, reason = "помощники тестов: упавший unwrap и есть упавший тест")]

use std::fs::{self, OpenOptions};
use std::path::{Path, PathBuf};

use plinth_sync::journal::{Journal, Op};
use plinth_types::{DeviceId, TrackId};

use crate::common::Scratch;

/// Магия, формат, вид, идентификатор журнала, номер операции.
pub const HEADER: usize = 4 + 1 + 1 + 8 + 8;
pub const FRAME_HEADER: usize = 4 + 4;
/// `COMPACT_TAIL_BYTES` в `store.rs`: хвост длиннее сворачивается в снимок.
pub const COMPACT_TAIL_BYTES: u64 = 256 * 1024;

pub fn sorted(mut tracks: Vec<TrackId>) -> Vec<TrackId> {
    tracks.sort_by_key(ToString::to_string);
    tracks
}

pub fn open(dir: &Scratch) -> Journal {
    Journal::open(&dir.journal(), DeviceId::new()).unwrap()
}

pub fn liked(dir: &Scratch) -> Vec<TrackId> {
    sorted(open(dir).state().likes)
}

/// Кадр как он лежит в хвосте: длина, CRC32, данные.
pub fn frame(data: &[u8]) -> Vec<u8> {
    let mut bytes = u32::try_from(data.len()).unwrap().to_le_bytes().to_vec();
    bytes.extend_from_slice(&crc32fast::hash(data).to_le_bytes());
    bytes.extend_from_slice(data);
    bytes
}

/// (начало, конец) каждого кадра целого хвоста.
pub fn spans(tail: &[u8]) -> Vec<(usize, usize)> {
    let mut spans = Vec::new();
    let mut at = HEADER;
    while at < tail.len() {
        let length = u32::from_le_bytes(tail[at..at + 4].try_into().unwrap()) as usize;
        spans.push((at, at + FRAME_HEADER + length));
        at += FRAME_HEADER + length;
    }
    assert_eq!(at, tail.len(), "хвост должен состоять из целых кадров");
    spans
}

/// Файлы папки копий `journal/aside/` (Р9е), чьё имя начинается с `prefix`, по возрастанию
/// имени; нет папки — нет и копий.
pub fn beside(dir: &Scratch, prefix: &str) -> Vec<PathBuf> {
    let mut found: Vec<PathBuf> = fs::read_dir(dir.journal().join("aside"))
        .into_iter()
        .flatten()
        .map(|entry| entry.unwrap().path())
        .filter(|path| path.file_name().unwrap().to_string_lossy().starts_with(prefix))
        .collect();
    found.sort();
    found
}

/// Копии испорченного хвоста рядом с журналом.
pub fn kept_aside(dir: &Scratch) -> Vec<PathBuf> {
    beside(dir, "tail.damaged-")
}

/// Все файлы каталога журнала: имя и длина.
pub fn listing(dir: &Scratch) -> Vec<(String, u64)> {
    let mut found: Vec<(String, u64)> = fs::read_dir(dir.journal())
        .unwrap()
        .map(|entry| {
            let entry = entry.unwrap();
            (entry.file_name().to_string_lossy().into_owned(), entry.metadata().unwrap().len())
        })
        .collect();
    found.sort();
    found
}

pub fn tail_len(dir: &Scratch) -> u64 {
    fs::metadata(dir.journal().join("tail")).unwrap().len()
}

/// Журнал из `count` лайков, по кадру на лайк; хвост не свёрнут.
pub struct Pristine {
    pub snapshot: Vec<u8>,
    pub tail: Vec<u8>,
    pub tracks: Vec<TrackId>,
}

impl Pristine {
    pub fn new(count: usize) -> Self {
        let dir = Scratch::new();
        let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
        let tracks: Vec<TrackId> = (0..count).map(|_| TrackId::new()).collect();
        for track in &tracks {
            journal.record(&Op::Like { track: *track }).unwrap();
        }
        drop(journal);
        Self {
            snapshot: fs::read(dir.journal().join("snapshot")).unwrap(),
            tail: fs::read(dir.journal().join("tail")).unwrap(),
            tracks,
        }
    }

    /// Первые `count` лайков, в порядке сравнения.
    pub fn first(&self, count: usize) -> Vec<TrackId> {
        sorted(self.tracks[..count].to_vec())
    }

    pub fn all(&self) -> Vec<TrackId> {
        sorted(self.tracks.clone())
    }

    /// Каталог журнала с этим снимком и данным хвостом; файлы пишутся без `fsync`.
    pub fn dir_with(&self, tail: &[u8]) -> Scratch {
        dir_with(&self.snapshot, tail)
    }
}

/// Каталог журнала с данными снимком и хвостом.
pub fn dir_with(snapshot: &[u8], tail: &[u8]) -> Scratch {
    let dir = Scratch::new();
    fs::create_dir_all(dir.journal()).unwrap();
    fs::write(dir.journal().join("snapshot"), snapshot).unwrap();
    fs::write(dir.journal().join("tail"), tail).unwrap();
    dir
}

/// Лайки пачкой — один кадр на пачку.
pub fn like_many(journal: &mut Journal, count: usize) -> Vec<TrackId> {
    let tracks: Vec<TrackId> = (0..count).map(|_| TrackId::new()).collect();
    let ops: Vec<Op> = tracks.iter().map(|track| Op::Like { track: *track }).collect();
    journal.record_all(&ops).unwrap();
    tracks
}

/// Файл только для чтения: `File::create` поверх него отказывает (Windows —
/// атрибут, Unix — права), а дескриптор, открытый раньше, дописывает как
/// прежде. Так в тестах выглядит отказ записи, который ядро не может устроить
/// само. `false` — окружение права не соблюдает (запуск от root): сбоя не будет.
pub fn deny_writes(path: &Path) -> bool {
    let mut permissions = fs::metadata(path).unwrap().permissions();
    permissions.set_readonly(true);
    fs::set_permissions(path, permissions).unwrap();
    OpenOptions::new().write(true).open(path).is_err()
}

#[allow(
    clippy::permissions_set_readonly_false,
    reason = "файл во временном каталоге теста: запрет снимается, чтобы каталог стёрся"
)]
pub fn allow_writes(path: &Path) {
    if let Ok(metadata) = fs::metadata(path) {
        let mut permissions = metadata.permissions();
        permissions.set_readonly(false);
        let _ = fs::set_permissions(path, permissions);
    }
}

/// Снимает запрет записи при выходе из теста, в том числе упавшего, — иначе
/// `Scratch` не сотрёт каталог на Windows.
pub struct AllowWritesOnDrop(pub PathBuf);

impl Drop for AllowWritesOnDrop {
    fn drop(&mut self) {
        allow_writes(&self.0);
    }
}
