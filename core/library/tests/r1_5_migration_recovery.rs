//! Р1.5 (`docs/work/r1-5.md`, ревью v0.2, находка 5): копия перед обновлением
//! и провал миграции не запирают базу. Одно обновление — одна законченная
//! копия; порча, встреченная копией, — карантин; миграция, упавшая на данных,
//! — файл в сторону и база заново. База в каждом тесте — копия эталона
//! `tests/fixtures/db/v1.db`.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::io::{Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use plinth_library::db::query::TrackSort;
use plinth_library::db::{Database, IntegrityCheck, Opened};
use rusqlite::Connection;

/// Каталог теста с копией эталона v1 под именем `plinth.db`; стирается в `Drop`.
struct Dir(PathBuf);

impl Dir {
    fn with_v1(name: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("plinth-r1-5-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        fs::copy(Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/db/v1.db"), dir.join("plinth.db")).unwrap();
        Self(dir)
    }

    fn db(&self) -> PathBuf {
        self.0.join("plinth.db")
    }

    /// Имена файлов каталога по порядку.
    fn names(&self) -> Vec<String> {
        let mut names: Vec<String> =
            fs::read_dir(&self.0).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect();
        names.sort();
        names
    }

    fn starting(&self, prefix: &str) -> Vec<String> {
        self.names().into_iter().filter(|n| n.starts_with(prefix)).collect()
    }

    fn open(&self) -> Opened {
        let opened = Database::open(&self.db(), IntegrityCheck::Skip);
        assert!(opened.is_ok(), "the database is locked out: {:?}", opened.as_ref().err());
        opened.unwrap()
    }
}

impl Drop for Dir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// Версия схемы и число треков в файле. Соединение обычное: закрываясь, оно
/// убирает за собой `-wal` и `-shm`, а только для чтения — оставляет.
fn peek(file: &Path) -> (i32, i64) {
    let conn = Connection::open(file).unwrap();
    let version = conn.query_row("PRAGMA user_version", [], |r| r.get(0)).unwrap();
    let tracks = conn.query_row("SELECT count(*) FROM track", [], |r| r.get(0)).unwrap();
    (version, tracks)
}

fn latest() -> i32 {
    Database::open_in_memory().unwrap().schema_version().unwrap()
}

fn tracks(opened: &Opened) -> usize {
    opened.db.track_list(TrackSort::Title, None).unwrap().len()
}

/// Обновление с v1 до последней версии оставляет одну копию — с версии при
/// открытии. Оборванная прошлая копия (`.bak.tmp`) копией не считается и
/// убирается: `VACUUM INTO` в непустой файл не пишет.
#[test]
fn an_upgrade_leaves_one_complete_backup() {
    let dir = Dir::with_v1("one-backup");
    let unfinished = dir.0.join("plinth.db.bak.tmp");
    fs::write(&unfinished, vec![0xA5_u8; 4096]).unwrap();

    let opened = dir.open();

    assert!(opened.recovery.is_none());
    drop(opened);
    let backups = dir.starting("plinth.db.bak-");
    assert_eq!(backups.len(), 1, "{backups:?}");
    assert!(backups[0].ends_with("-v1"), "{backups:?}");
    assert_eq!(peek(&dir.0.join(&backups[0])), (1, 3), "the copy is the database as it was");
    assert!(!unfinished.exists());
    drop(dir.open());
    assert_eq!(dir.starting("plinth.db.bak-"), backups, "an open with nothing to migrate takes no copy");
}

/// Порча, которую встретила копия, — та же порча: карантин и база заново, а
/// не отказ на каждом запуске. Испорчены страницы 3–4: схема и заголовок
/// целы, первым их читает `VACUUM INTO`.
#[test]
fn damage_met_by_the_backup_is_quarantined() {
    let dir = Dir::with_v1("backup-damage");
    let mut file = fs::OpenOptions::new().write(true).open(dir.db()).unwrap();
    file.seek(SeekFrom::Start(8192)).unwrap();
    file.write_all(&[0xFF; 8192]).unwrap();
    drop(file);

    let opened = dir.open();

    assert!(opened.recovery.is_some(), "the damaged file is set aside");
    assert_eq!(opened.db.schema_version().unwrap(), latest());
    assert_eq!(tracks(&opened), 0);
    drop(opened);
    assert_eq!(dir.starting("plinth.db.corrupt-").len(), 1, "{:?}", dir.names());
    assert!(dir.names().iter().all(|n| !n.contains(".bak")), "{:?}", dir.names());
    assert!(dir.open().recovery.is_none());
}

/// Миграция упала на данных (её таблица уже есть): файл целым уходит в
/// сторону, база создаётся заново — пользовательское вернёт журнал, каталог —
/// скан. Отложенный файл — база до упавшего шага.
#[test]
fn a_migration_failing_on_the_data_sets_the_file_aside() {
    let dir = Dir::with_v1("migration-failed");
    let conn = Connection::open(dir.db()).unwrap();
    conn.execute_batch("CREATE TABLE scan_file (x)").unwrap();
    drop(conn);

    let opened = dir.open();

    let recovery = opened.recovery.as_ref().expect("the file is set aside");
    assert!(recovery.reason.contains("migration 2 (scan_file)"), "{}", recovery.reason);
    let aside = recovery.quarantined.clone();
    let name = aside.file_name().unwrap().to_string_lossy().into_owned();
    assert!(name.starts_with("plinth.db.migration-failed-"), "{name}");
    assert_eq!(opened.db.schema_version().unwrap(), latest());
    assert_eq!(tracks(&opened), 0);
    drop(opened);
    assert_eq!(peek(&aside), (1, 3), "set aside as it was before the failed step");
    let backups = dir.starting("plinth.db.bak-");
    assert!(backups.len() == 1 && backups[0].ends_with("-v1"), "{backups:?}");
    let before = dir.names();
    assert!(dir.open().recovery.is_none());
    assert_eq!(dir.names(), before, "the next open is an ordinary one");
}
