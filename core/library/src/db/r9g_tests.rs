//! Р9ж (`docs/work/r9g.md`, ревью R1, находка 26): копия перед миграцией не отнимает у неё место.
//! Миграция, упавшая с «нет места» (`SQLITE_FULL`) при копии этого же открытия, стирает эту копию и
//! идёт дальше один раз без неё. Настоящий полный диск здесь не воспроизвести (в находке сказано
//! то же): «нет места» изображает миграция теста — она падает с `SQLITE_FULL`, пока рядом с базой
//! лежит достаточно копий. Файл — тесты, править его исполнитель не вправе.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicI32, AtomicUsize, Ordering};

use rusqlite::{Connection, ErrorCode, Transaction, ffi};

use super::backup::backups;
use super::migrations::{Migration, MigrationError, migrate};

/// Каталог теста; стирается в `Drop` — после соединения: оно объявляется позже и закрывается раньше.
struct Dir(PathBuf);

impl Dir {
    fn new(name: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("plinth-r9g-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        Self(dir)
    }

    fn db(&self) -> PathBuf {
        self.0.join("plinth.db")
    }
}

impl Drop for Dir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn version(conn: &Connection) -> i32 {
    conn.query_row("PRAGMA user_version", [], |r| r.get(0)).unwrap()
}

fn has_table(conn: &Connection, name: &str) -> bool {
    conn.query_row("SELECT count(*) FROM sqlite_schema WHERE type = 'table' AND name = ?1", [name], |r| {
        r.get::<_, i64>(0)
    })
    .unwrap()
        == 1
}

/// Единственная строка таблицы `a`, которую миграции трогать не должны.
fn kept_row(conn: &Connection) -> i64 {
    conn.query_row("SELECT x FROM a", [], |r| r.get(0)).unwrap()
}

fn sqlite(code: i32) -> rusqlite::Error {
    rusqlite::Error::SqliteFailure(ffi::Error::new(code), None)
}

/// «Места нет», пока рядом с базой лежит не меньше `copies` копий; иначе — `sql`.
fn needs_room(tx: &Transaction<'_>, copies: usize, sql: &str) -> rusqlite::Result<()> {
    let path = Path::new(tx.path().expect("the test database is a file"));
    if backups(path).unwrap().len() >= copies { Err(sqlite(ffi::SQLITE_FULL)) } else { tx.execute_batch(sql) }
}

/// База версии 1 с одной строкой `a.x = 42`; соединение открыто.
fn old_database(dir: &Dir) -> Connection {
    let mut conn = Connection::open(dir.db()).unwrap();
    migrate(&mut conn, &[ONE], None).unwrap();
    conn.execute("INSERT INTO a VALUES (42)", []).unwrap();
    conn
}

const ONE: Migration = Migration { version: 1, name: "one", apply: |tx| tx.execute_batch("CREATE TABLE a (x)") };

/// Для каждого теста свой счётчик вызовов `apply` (`fn` не несёт состояния) и своя миграция.
static EATEN_CALLS: AtomicUsize = AtomicUsize::new(0);
/// Падает «нет места», пока на диске лежит копия, — копия съела место.
const TWO_EATEN: Migration = Migration {
    version: 2,
    name: "eaten",
    apply: |tx| {
        EATEN_CALLS.fetch_add(1, Ordering::SeqCst);
        needs_room(tx, 1, "CREATE TABLE b (x)")
    },
};

/// Копия, снятая для этого открытия, стирается, миграция повторяется без неё и проходит: открытие не
/// отказывает из-за места, которое заняла своя же копия. Данные на месте.
#[test]
fn a_copy_that_eats_the_room_is_dropped_and_the_migration_goes_on() {
    let dir = Dir::new("eaten");
    let mut conn = old_database(&dir);

    let result = migrate(&mut conn, &[ONE, TWO_EATEN], Some(&dir.db()));

    assert!(matches!(result.as_deref(), Ok([2])), "{result:?}");
    assert_eq!(version(&conn), 2);
    assert!(has_table(&conn, "b"));
    assert_eq!(kept_row(&conn), 42);
    assert!(backups(&dir.db()).unwrap().is_empty(), "the copy that took the room is gone");
    assert_eq!(EATEN_CALLS.load(Ordering::SeqCst), 2, "once with the copy, once without it");
}

static CROWDED_CALLS: AtomicUsize = AtomicUsize::new(0);
/// Падает «нет места», когда на диске три копии: две прежние и новая.
const TWO_CROWDED: Migration = Migration {
    version: 2,
    name: "crowded",
    apply: |tx| {
        CROWDED_CALLS.fetch_add(1, Ordering::SeqCst);
        needs_room(tx, 3, "CREATE TABLE b (x)")
    },
};

/// Стирается копия этого открытия — та, чей путь отдал `backup`, а не «новейшая по имени». Прежние
/// копии (здесь с метками «из будущего»: часы ушли назад, по имени они новее) остаются как были.
#[test]
fn only_the_copy_of_this_open_is_dropped() {
    let dir = Dir::new("crowded");
    let mut conn = old_database(&dir);
    let older: Vec<PathBuf> = ["9999999999991", "9999999999992"]
        .iter()
        .map(|stamp| {
            let copy = dir.0.join(format!("plinth.db.bak-{stamp}-v1"));
            fs::write(&copy, b"an older copy").unwrap();
            copy
        })
        .collect();

    let result = migrate(&mut conn, &[ONE, TWO_CROWDED], Some(&dir.db()));

    assert!(matches!(result.as_deref(), Ok([2])), "{result:?}");
    assert_eq!(CROWDED_CALLS.load(Ordering::SeqCst), 2);
    assert_eq!(backups(&dir.db()).unwrap(), older, "the older copies are where they were");
    for copy in &older {
        assert_eq!(fs::read(copy).unwrap(), b"an older copy", "{copy:?} is as it was");
    }
}

static NEVER_CALLS: AtomicUsize = AtomicUsize::new(0);
/// Места не хватает и без копии. С шестого вызова — другая ошибка: цикл повторов, если исполнитель
/// его напишет, остановится на ней, а тест упадёт утверждением, а не повиснет.
const TWO_NEVER: Migration = Migration {
    version: 2,
    name: "never",
    apply: |_| {
        if NEVER_CALLS.fetch_add(1, Ordering::SeqCst) >= 5 {
            Err(sqlite(ffi::SQLITE_ERROR))
        } else {
            Err(sqlite(ffi::SQLITE_FULL))
        }
    },
};

/// Места нет и без копии: вторая попытка тоже падает, и это прежняя ошибка открытия (сбой среды —
/// файл не тронут, следующий запуск повторит). Попытка одна лишняя, не больше; копия не остаётся
/// занимать место, которого и так нет; версия и данные на месте.
#[test]
fn no_room_even_without_the_copy_fails_once_more_and_leaves_no_copy() {
    let dir = Dir::new("never");
    let mut conn = old_database(&dir);

    let result = migrate(&mut conn, &[ONE, TWO_NEVER], Some(&dir.db()));

    match &result {
        Err(MigrationError::Failed { version: 2, existed: true, error, .. }) => {
            assert_eq!(error.sqlite_error_code(), Some(ErrorCode::DiskFull), "{error:?}");
        }
        other => panic!("the environment error must come back as it did: {other:?}"),
    }
    assert_eq!(NEVER_CALLS.load(Ordering::SeqCst), 2, "one retry, not a loop");
    assert_eq!(version(&conn), 1);
    assert_eq!(kept_row(&conn), 42);
    assert!(backups(&dir.db()).unwrap().is_empty(), "a copy must not keep the room that is not there");
}

const TWO_PLAIN: Migration = Migration { version: 2, name: "two", apply: |tx| tx.execute_batch("CREATE TABLE b (x)") };
/// Третья миграция упирается в копию, вторая — нет.
const THREE_EATEN: Migration =
    Migration { version: 3, name: "eaten-late", apply: |tx| needs_room(tx, 1, "CREATE TABLE c (x)") };

/// Упала не первая из ожидающих: повтор продолжает с упавшей, прошедшие не гонит заново (вторая
/// миграция упала бы на уже созданной таблице), а итог перечисляет применённые обеими попытками.
#[test]
fn the_retry_goes_on_with_the_migrations_that_are_left() {
    let dir = Dir::new("late");
    let mut conn = old_database(&dir);

    let result = migrate(&mut conn, &[ONE, TWO_PLAIN, THREE_EATEN], Some(&dir.db()));

    assert!(matches!(result.as_deref(), Ok([2, 3])), "{result:?}");
    assert_eq!(version(&conn), 3);
    assert!(has_table(&conn, "b") && has_table(&conn, "c"));
    assert_eq!(kept_row(&conn), 42);
    assert!(backups(&dir.db()).unwrap().is_empty());
}

static CODE: AtomicI32 = AtomicI32::new(0);
static OTHER_CALLS: AtomicUsize = AtomicUsize::new(0);
/// Падает с кодом, который тест кладёт в `CODE`.
const TWO_OTHER: Migration = Migration {
    version: 2,
    name: "other",
    apply: |_| {
        OTHER_CALLS.fetch_add(1, Ordering::SeqCst);
        Err(sqlite(CODE.load(Ordering::SeqCst)))
    },
};

/// Охранный (на `main` проходит): копию стирает только «нет места». Ошибка на данных — копия остаётся
/// (это «база до обновления» из ADR 0011); прочие сбои среды — тоже: повтор без копии им не поможет.
/// Второй попытки нет ни в одном случае.
#[test]
fn any_other_failure_keeps_the_copy_and_does_not_retry() {
    for code in
        [ffi::SQLITE_ERROR, ffi::SQLITE_CONSTRAINT, ffi::SQLITE_BUSY, ffi::SQLITE_IOERR_WRITE, ffi::SQLITE_CANTOPEN]
    {
        let dir = Dir::new(&format!("other-{code}"));
        let mut conn = old_database(&dir);
        CODE.store(code, Ordering::SeqCst);
        OTHER_CALLS.store(0, Ordering::SeqCst);

        let result = migrate(&mut conn, &[ONE, TWO_OTHER], Some(&dir.db()));

        assert!(matches!(result, Err(MigrationError::Failed { version: 2, existed: true, .. })), "{code}: {result:?}");
        assert_eq!(backups(&dir.db()).unwrap().len(), 1, "{code}: the copy stays");
        assert_eq!(OTHER_CALLS.load(Ordering::SeqCst), 1, "{code}: no second attempt");
        assert_eq!(version(&conn), 1);
    }
}

static GONE_CALLS: AtomicUsize = AtomicUsize::new(0);
/// К тому времени, как миграция упала, копии этого открытия уже нет (её убрала ротация, человек, чистилка
/// файлов): миграция сама стирает все копии и падает «нет места».
const TWO_GONE: Migration = Migration {
    version: 2,
    name: "gone",
    apply: |tx| {
        GONE_CALLS.fetch_add(1, Ordering::SeqCst);
        for copy in backups(Path::new(tx.path().expect("the test database is a file"))).unwrap() {
            fs::remove_file(copy).unwrap();
        }
        Err(sqlite(ffi::SQLITE_FULL))
    },
};

/// Охранный (на `main` проходит): копии уже нет — стирать нечего, места не прибавилось, повторять нечего.
/// Повтор бывает только после копии, которую удалось стереть.
#[test]
fn a_copy_that_is_already_gone_frees_nothing_and_is_not_retried() {
    let dir = Dir::new("gone");
    let mut conn = old_database(&dir);

    let result = migrate(&mut conn, &[ONE, TWO_GONE], Some(&dir.db()));

    assert!(matches!(result, Err(MigrationError::Failed { version: 2, existed: true, .. })), "{result:?}");
    assert_eq!(GONE_CALLS.load(Ordering::SeqCst), 1, "nothing was freed, so there is nothing to retry");
    assert_eq!(version(&conn), 1);
    assert_eq!(kept_row(&conn), 42);
}

static BARE_CALLS: AtomicUsize = AtomicUsize::new(0);
/// Всегда «нет места»; версия 2.
const TWO_BARE: Migration = Migration {
    version: 2,
    name: "bare",
    apply: |_| {
        BARE_CALLS.fetch_add(1, Ordering::SeqCst);
        Err(sqlite(ffi::SQLITE_FULL))
    },
};
static FRESH_CALLS: AtomicUsize = AtomicUsize::new(0);
/// Всегда «нет места»; версия 1 — миграция новой базы.
const ONE_BARE: Migration = Migration {
    version: 1,
    name: "bare-first",
    apply: |_| {
        FRESH_CALLS.fetch_add(1, Ordering::SeqCst);
        Err(sqlite(ffi::SQLITE_FULL))
    },
};

/// Охранный (на `main` проходит): нечего стирать — нечего и повторять. Копия не снялась (каталога под
/// неё нет) — ничего не освободилось, вторая попытка пустая; база создана этим же открытием — копии
/// ей не снимают вовсе.
#[test]
fn without_a_copy_there_is_nothing_to_drop_and_nothing_to_retry() {
    let missing = std::env::temp_dir().join(format!("plinth-r9g-missing-{}", std::process::id()));
    let _ = fs::remove_dir_all(&missing);
    let mut old = Connection::open_in_memory().unwrap();
    migrate(&mut old, &[ONE], None).unwrap();

    let no_copy = migrate(&mut old, &[ONE, TWO_BARE], Some(&missing.join("plinth.db")));

    assert!(matches!(no_copy, Err(MigrationError::Failed { version: 2, existed: true, .. })), "{no_copy:?}");
    assert_eq!(BARE_CALLS.load(Ordering::SeqCst), 1, "nothing was freed, so there is nothing to retry");
    assert!(!missing.exists(), "a copy does not make directories");

    let dir = Dir::new("fresh");
    let mut fresh = Connection::open(dir.db()).unwrap();

    let new_database = migrate(&mut fresh, &[ONE_BARE], Some(&dir.db()));

    assert!(matches!(new_database, Err(MigrationError::Failed { version: 1, existed: false, .. })), "{new_database:?}");
    assert_eq!(FRESH_CALLS.load(Ordering::SeqCst), 1);
    assert!(backups(&dir.db()).unwrap().is_empty());
}
