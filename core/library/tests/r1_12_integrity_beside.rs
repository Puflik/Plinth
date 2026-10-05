//! Р1.12 (`docs/work/r1-12.md`, ревью FFI и сканера, находка 13): плановая
//! проверка целостности — не на пути открытия. Открытие только отмечает, что
//! пришла её очередь; полная проверка идёт рядом, на своём соединении только
//! для чтения. Найденная порча — метка `<база>.damaged`: следующее открытие
//! откладывает файл и создаёт базу заново.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::io::{Seek, SeekFrom, Write};
use std::path::PathBuf;

use plinth_library::db::query::TrackSort;
use plinth_library::db::{Checked, Database, IntegrityCheck, Opened};
use plinth_library::scan::{FoundFile, ScannedFile, Tags, write};
use plinth_types::{CoreError, Format, Timestamp};
use rusqlite::Connection;

/// Каталог теста; стирается в `Drop`.
struct Dir(PathBuf);

impl Dir {
    fn new(name: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("plinth-r1-12-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        Self(dir)
    }

    /// Каталог с пустой базой последней версии; соединение закрыто.
    fn with_db(name: &str) -> Self {
        let dir = Self::new(name);
        drop(dir.open(IntegrityCheck::Skip));
        dir
    }

    fn db(&self) -> PathBuf {
        self.0.join("plinth.db")
    }

    fn mark(&self) -> PathBuf {
        self.0.join("plinth.db.damaged")
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

    fn open(&self, check: IntegrityCheck) -> Opened {
        let opened = Database::open(&self.db(), check);
        assert!(opened.is_ok(), "the database did not open: {:?}", opened.as_ref().err());
        opened.unwrap()
    }

    /// 2 000 строк в `setting` — последние страницы файла; открытие их не читает.
    fn fill(&self) {
        let conn = Connection::open(self.db()).unwrap();
        let tx = conn.unchecked_transaction().unwrap();
        for i in 0..2_000 {
            tx.execute("INSERT INTO setting(key, value) VALUES (?1, ?2)", (format!("k{i}"), "v".repeat(40))).unwrap();
        }
        tx.commit().unwrap();
    }

    /// Следующее открытие — двадцатый запуск, очередь плановой проверки.
    fn before_the_twentieth_launch(&self) {
        let conn = Connection::open(self.db()).unwrap();
        conn.execute("UPDATE meta SET value = 19 WHERE key = 'launches'", []).unwrap();
    }

    /// Три страницы ближе к концу файла — мусор. Открытие их не читает, порчу
    /// находит только полная проверка (как в `damaged_data_pages_are_caught_by_the_check`).
    fn damage(&self) {
        assert!(!self.0.join("plinth.db-wal").exists(), "the file must hold every page");
        let length = fs::metadata(self.db()).unwrap().len();
        let mut file = fs::OpenOptions::new().write(true).open(self.db()).unwrap();
        file.seek(SeekFrom::Start(length - 4096 * 6)).unwrap();
        file.write_all(&[0xFF; 4096 * 3]).unwrap();
    }
}

impl Drop for Dir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn scanned() -> ScannedFile {
    ScannedFile {
        file: FoundFile {
            uri: "/storage/emulated/0/Music/Radiohead/Creep.mp3".to_owned(),
            folder: "Music/Radiohead/".to_owned(),
            format: Format::Mp3,
            modified_at: Timestamp::from_millis(1_700_000_000_000),
            size: 4_000_000,
        },
        known: None,
        tags: Tags { title: Some("Creep".to_owned()), ..Tags::default() },
        readable: true,
    }
}

/// Двадцатый запуск — очередь плановой проверки, но открытие её не делает и
/// порчу, которую само не читает, не находит. Порча настоящая: `Now` находит.
#[test]
fn opening_does_not_run_the_scheduled_check() {
    let dir = Dir::with_db("open");
    dir.fill();
    dir.before_the_twentieth_launch();
    dir.damage();

    let opened = dir.open(IntegrityCheck::Scheduled);

    assert!(opened.recovery.is_none(), "the scheduled check ran on the open path");
    drop(opened);
    assert!(dir.starting("plinth.db.corrupt-").is_empty(), "{:?}", dir.names());
    assert!(dir.open(IntegrityCheck::Now).recovery.is_some(), "the damage is real");
}

/// Метку оставила проверка прошлого запуска: открытие откладывает файл, как
/// порчу, найденную на открытии, и снимает метку.
#[test]
fn a_mark_sets_the_database_aside_on_the_next_open() {
    let dir = Dir::with_db("mark");
    fs::write(dir.mark(), "found by an earlier check").unwrap();

    let opened = dir.open(IntegrityCheck::Skip);

    assert!(opened.recovery.is_some(), "the marked file must be set aside");
    let recovery = opened.recovery.as_ref().unwrap();
    assert!(recovery.reason.contains("found by an earlier check"), "{}", recovery.reason);
    let name = recovery.quarantined.file_name().unwrap().to_string_lossy().into_owned();
    assert!(name.starts_with("plinth.db.corrupt-"), "{name}");
    assert!(!dir.mark().exists());
    drop(opened);
    assert!(dir.open(IntegrityCheck::Skip).recovery.is_none());
}

/// Метка без базы отложить нечего: открытие её снимает и создаёт базу как обычно.
#[test]
fn a_mark_without_a_database_is_dropped() {
    let dir = Dir::new("orphan-mark");
    fs::write(dir.mark(), "found by an earlier check").unwrap();

    let opened = dir.open(IntegrityCheck::Skip);

    assert!(opened.recovery.is_none());
    assert!(!dir.mark().exists(), "a mark without its database is dropped");
}

/// Очередь плановой проверки — каждый двадцатый запуск, и открытие только
/// отмечает её. `Now` проверяет на самом открытии, `Skip` — никогда.
#[test]
fn the_check_is_due_on_every_twentieth_launch() {
    let dir = Dir::with_db("due");
    dir.before_the_twentieth_launch();

    let twentieth = dir.open(IntegrityCheck::Scheduled);
    let due = twentieth.integrity_due;
    drop(twentieth);
    let next = dir.open(IntegrityCheck::Scheduled).integrity_due;

    assert!(due);
    assert!(!next);
    dir.before_the_twentieth_launch();
    assert!(!dir.open(IntegrityCheck::Now).integrity_due, "Now checks on the open path itself");
    dir.before_the_twentieth_launch();
    assert!(!dir.open(IntegrityCheck::Skip).integrity_due);
}

/// Проверка рядом нашла порчу: сама ничего не откладывает, оставляет метку с
/// причиной; следующее открытие откладывает файл и создаёт базу заново.
#[test]
fn damage_found_beside_is_set_aside_on_the_next_open() {
    let dir = Dir::with_db("found");
    dir.fill();
    dir.damage();

    let reason = match Database::check_file(&dir.db()) {
        Ok(Checked::Damaged { reason }) => reason,
        other => panic!("the damage must be found: {other:?}"),
    };

    assert!(!reason.is_empty());
    assert!(dir.mark().exists());
    assert!(dir.starting("plinth.db.corrupt-").is_empty(), "the check sets nothing aside itself");
    let opened = dir.open(IntegrityCheck::Skip);
    let recovery = opened.recovery.as_ref().expect("set aside on the next open");
    assert!(recovery.reason.contains(&reason), "{}", recovery.reason);
    assert!(!dir.mark().exists());
    assert!(opened.db.integrity_problems().unwrap().is_empty());
    assert_eq!(opened.db.launches().unwrap(), 1, "a new database");
    drop(opened);
    let before = dir.names();
    assert!(dir.open(IntegrityCheck::Skip).recovery.is_none());
    assert_eq!(dir.names(), before, "the open after the repair is an ordinary one");
}

/// Файл цел — метки нет, открытие обычное.
#[test]
fn a_healthy_file_is_left_without_a_mark() {
    let dir = Dir::with_db("healthy");
    dir.fill();

    let checked = Database::check_file(&dir.db());

    assert!(matches!(checked, Ok(Checked::Healthy)), "{checked:?}");
    assert!(!dir.mark().exists());
    assert!(dir.open(IntegrityCheck::Skip).recovery.is_none());
}

/// Проверка идёт на своём соединении только для чтения: основное тем временем
/// держит открытую запись (WAL), и они друг друга не ждут.
#[test]
fn the_check_reads_beside_an_open_writer() {
    let dir = Dir::with_db("writer");
    let opened = dir.open(IntegrityCheck::Skip);

    let checked = opened.db.in_transaction(|db| {
        write(db, &[scanned()], Timestamp::from_millis(1_700_000_000_000))?;
        Ok(Database::check_file(&dir.db()))
    });

    assert!(matches!(checked, Ok(Ok(Checked::Healthy))), "{checked:?}");
    assert_eq!(opened.db.track_list(TrackSort::Title, None).unwrap().len(), 1, "the write went through");
}

/// Файл не открылся (нет его, занят, ввод-вывод) — это ошибка, а не порча:
/// метки нет, и сам файл не создан.
#[test]
fn a_file_that_cannot_be_read_is_an_error_not_damage() {
    let dir = Dir::new("missing");

    let checked = Database::check_file(&dir.0.join("missing.db"));

    assert!(matches!(checked, Err(CoreError::Storage { .. })), "{checked:?}");
    assert!(dir.names().is_empty(), "{:?}", dir.names());
}
