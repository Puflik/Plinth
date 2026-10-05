//! Р1.4 (`docs/work/r1-4.md`, ревью v0.2, находка 4): снимок журнала, который
//! не читается, откладывается рядом, и журнал начинается заново — с тем
//! пользовательским, что показывает база. Целый снимок и снимок более новой
//! версии приложения так не трогаются.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod common;

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use common::{Scratch, Workload, dump, memory_db};
use plinth_library::db::Database;
use plinth_sync::journal::{
    CatchUp, Journal, JournalState, Op, catch_up, damaged_snapshot, record_and_project, start_over,
};
use plinth_types::{CoreError, DeviceId, TrackId};

fn snapshot(dir: &Scratch) -> PathBuf {
    dir.journal().join("snapshot")
}

/// Журнал из 150 осмысленных операций, каждая отражена в базе; свёрнут в
/// снимок, хвост пуст.
fn filled(dir: &Scratch, db: &Database) -> Journal {
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    let mut workload = Workload::new(4);
    for _ in 0..150 {
        let op = workload.op(&journal);
        record_and_project(&mut journal, db, &op).unwrap();
    }
    journal.compact().unwrap();
    journal
}

/// Правит байты снимка; отдаёт испорченные.
fn spoil(dir: &Scratch, change: impl FnOnce(&mut Vec<u8>)) -> Vec<u8> {
    let path = snapshot(dir);
    let mut bytes = fs::read(&path).unwrap();
    change(&mut bytes);
    fs::write(&path, &bytes).unwrap();
    bytes
}

fn flip_last(bytes: &mut [u8]) {
    let last = bytes.len() - 1;
    bytes[last] ^= 0xFF;
}

/// Порча снимка для таблицы случаев.
type Spoiler = fn(&mut Vec<u8>);

/// Имена файлов каталога по порядку.
fn listing(dir: &Path) -> Vec<String> {
    let mut names: Vec<String> =
        fs::read_dir(dir).unwrap().map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned()).collect();
    names.sort();
    names
}

fn starting_with(dir: &Path, prefix: &str) -> Vec<String> {
    listing(dir).into_iter().filter(|name| name.starts_with(prefix)).collect()
}

/// Целый снимок и снимок, которого нет, — не порча. Каталога журнала, которого
/// нет, проверка не создаёт.
#[test]
fn an_intact_or_missing_snapshot_is_not_damaged() {
    let dir = Scratch::new();
    assert_eq!(damaged_snapshot(&dir.journal()).unwrap(), None);
    assert!(!dir.journal().exists(), "the check creates nothing");

    drop(filled(&dir, &memory_db()));

    assert_eq!(damaged_snapshot(&dir.journal()).unwrap(), None);
}

/// Сумма не сошлась, файл обрезан, пуст или это вовсе не снимок — порча с
/// причиной. Проверка ничего не меняет в каталоге, а `Journal::open`, как и
/// прежде, такой журнал не открывает.
#[test]
fn a_snapshot_that_does_not_read_is_damaged_and_left_as_it_is() {
    let spoilers: [(&str, Spoiler); 5] = [
        ("checksum", |bytes| flip_last(bytes)),
        ("cut inside the header", |bytes| bytes.truncate(10)),
        ("cut after the header", |bytes| bytes.truncate(24)),
        ("empty", Vec::clear),
        ("not a snapshot", |bytes| *bytes = b"#EXTM3U\n".to_vec()),
    ];
    for (case, change) in spoilers {
        let dir = Scratch::new();
        drop(filled(&dir, &memory_db()));
        let damaged = spoil(&dir, change);
        let files = listing(&dir.journal());

        let reason = damaged_snapshot(&dir.journal()).unwrap();

        assert!(reason.as_deref().is_some_and(|reason| !reason.is_empty()), "{case}: {reason:?}");
        assert_eq!(fs::read(snapshot(&dir)).unwrap(), damaged, "{case}");
        assert_eq!(listing(&dir.journal()), files, "{case}");
        assert!(Journal::open(&dir.journal(), DeviceId::new()).is_err(), "{case}");
    }
}

/// Сумма сошлась, а `yrs` байты не разбирает — тоже порча. На таком мусоре
/// `yrs` не отдаёт ошибку, а паникует (здесь — переполнение сдвига в разборе
/// числа): и `Journal::open`, и проверка отвечают ошибкой и причиной, а не
/// паникой.
#[test]
fn a_snapshot_yrs_cannot_read_is_damaged_not_a_panic() {
    let dir = Scratch::new();
    drop(filled(&dir, &memory_db()));
    spoil(&dir, |bytes| {
        // Заголовок снимка — 22 байта (`store.rs`), за ним CRC32 данных и сами данные.
        let payload = [0xFF_u8; 64];
        bytes.truncate(22);
        bytes.extend_from_slice(&crc32fast::hash(&payload).to_le_bytes());
        bytes.extend_from_slice(&payload);
    });

    let opened = std::panic::catch_unwind(|| Journal::open(&dir.journal(), DeviceId::new()).is_err());
    let checked = std::panic::catch_unwind(|| damaged_snapshot(&dir.journal()));

    assert_eq!(opened.ok(), Some(true), "Journal::open must answer with an error, not a panic");
    let reason = checked.expect("the check must not panic").unwrap();
    assert!(reason.is_some_and(|reason| !reason.is_empty()));
}

/// Кадр хвоста с верной суммой, который `yrs` не разбирает, пропускается, а не
/// роняет открытие паникой: остальное читается.
#[test]
fn a_tail_frame_yrs_cannot_read_is_skipped_not_a_panic() {
    let dir = Scratch::new();
    let db = memory_db();
    let track = TrackId::new();
    let mut journal = filled(&dir, &db);
    record_and_project(&mut journal, &db, &Op::Like { track }).unwrap();
    drop(journal);
    // Кадр — длина и CRC32 данных, по 4 байта little-endian, затем данные (`store.rs`).
    let payload = [0xFF_u8; 64];
    let mut frame = u32::try_from(payload.len()).unwrap().to_le_bytes().to_vec();
    frame.extend_from_slice(&crc32fast::hash(&payload).to_le_bytes());
    frame.extend_from_slice(&payload);
    fs::OpenOptions::new().append(true).open(dir.journal().join("tail")).unwrap().write_all(&frame).unwrap();

    let opened = std::panic::catch_unwind(|| {
        Journal::open(&dir.journal(), DeviceId::new()).map(|journal| journal.state().likes.contains(&track))
    });

    assert_eq!(opened.ok().and_then(Result::ok), Some(true), "the frame is skipped, the rest reads");
}

/// Снимок более новой версии приложения не испорчен: это не порча, и начать
/// журнал заново поверх него нельзя — файлы остаются как были.
#[test]
fn a_snapshot_from_a_newer_app_is_not_damaged_and_not_started_over() {
    let dir = Scratch::new();
    let db = memory_db();
    drop(filled(&dir, &db));
    let newer = spoil(&dir, |bytes| bytes[4] += 1);
    let files = listing(&dir.journal());

    assert_eq!(damaged_snapshot(&dir.journal()).unwrap(), None);
    let started = start_over(&dir.journal(), DeviceId::new(), &db);

    assert!(matches!(started, Err(CoreError::Storage { .. })), "{started:?}");
    assert_eq!(fs::read(snapshot(&dir)).unwrap(), newer);
    assert_eq!(listing(&dir.journal()), files);
}

/// Снимок читается — начинать заново нечего: отказ, файлы и журнал прежние.
#[test]
fn a_readable_snapshot_is_never_started_over() {
    let dir = Scratch::new();
    let db = memory_db();
    let state = filled(&dir, &db).state();
    let files = listing(&dir.journal());
    let bytes = fs::read(snapshot(&dir)).unwrap();

    let started = start_over(&dir.journal(), DeviceId::new(), &db);

    assert!(matches!(started, Err(CoreError::Storage { .. })), "{started:?}");
    assert_eq!(listing(&dir.journal()), files);
    assert_eq!(fs::read(snapshot(&dir)).unwrap(), bytes);
    assert_eq!(Journal::open(&dir.journal(), DeviceId::new()).unwrap().state(), state);
}

/// Журнал начинается заново со всем, что показывает база: лайками, оценками,
/// прослушиваниями, плейлистами с записями, решениями о склейке, подписками,
/// чёрным списком и настройками. Проекция, собранная из нового журнала, — та
/// же, что была. Журнал новый, автор записей — новая установка, испорченный
/// снимок лежит рядом байт в байт.
#[test]
fn starting_over_carries_everything_the_database_shows() {
    let dir = Scratch::new();
    let db = memory_db();
    let old = filled(&dir, &db).mark();
    let before = dump(&db);
    let damaged = spoil(&dir, |bytes| flip_last(bytes));
    let device = DeviceId::new();

    let carried = start_over(&dir.journal(), device, &db).unwrap();
    let journal = Journal::open(&dir.journal(), device).unwrap();

    assert!(carried > 0);
    assert_ne!(journal.mark().journal, old.journal, "a new journal");
    assert!(journal.mark().seq > 0);
    assert_eq!(journal.device(), device);
    assert_eq!(catch_up(&journal, &db).unwrap(), CatchUp::Rebuilt);
    assert_eq!(dump(&db), before);
    let aside = starting_with(&dir.journal(), "snapshot.damaged-");
    assert_eq!(aside.len(), 1, "{aside:?}");
    assert_eq!(fs::read(dir.journal().join(&aside[0])).unwrap(), damaged);
}

/// В базе пусто — переносить нечего: журнал начинается пустым, как новый.
#[test]
fn with_nothing_in_the_database_the_journal_starts_empty() {
    let dir = Scratch::new();
    drop(filled(&dir, &memory_db()));
    spoil(&dir, |bytes| flip_last(bytes));
    let device = DeviceId::new();

    let carried = start_over(&dir.journal(), device, &memory_db()).unwrap();
    let journal = Journal::open(&dir.journal(), device).unwrap();

    assert_eq!(carried, 0);
    assert_eq!(journal.state(), JournalState::default());
    assert_eq!(journal.mark().seq, 0);
}

/// Хвост прежнего журнала — правки поверх испорченного снимка — в новый не
/// попадает: он ложится рядом, а новый журнал от открытия к открытию тот же.
/// Правки из хвоста не пропали — их перенесла база.
#[test]
fn the_old_tail_does_not_leak_into_the_new_journal() {
    let dir = Scratch::new();
    let db = memory_db();
    let mut journal = filled(&dir, &db);
    for _ in 0..3 {
        record_and_project(&mut journal, &db, &Op::Like { track: TrackId::new() }).unwrap();
    }
    drop(journal);
    let before = dump(&db);
    spoil(&dir, |bytes| flip_last(bytes));
    let device = DeviceId::new();
    start_over(&dir.journal(), device, &db).unwrap();

    let journal = Journal::open(&dir.journal(), device).unwrap();
    assert_eq!(catch_up(&journal, &db).unwrap(), CatchUp::Rebuilt);
    drop(journal);
    let reopened = Journal::open(&dir.journal(), device).unwrap();

    assert_eq!(catch_up(&reopened, &db).unwrap(), CatchUp::UpToDate, "nothing new came from the old tail");
    assert_eq!(dump(&db), before);
    assert!(
        !starting_with(&dir.journal(), "tail.").is_empty(),
        "the old tail is kept aside: {:?}",
        listing(&dir.journal())
    );
}

/// Начатый заново журнал — обычный журнал: правка ложится, переживает
/// открытие, база с ним в ладу.
#[test]
fn the_journal_started_over_keeps_working() {
    let dir = Scratch::new();
    let db = memory_db();
    drop(filled(&dir, &db));
    spoil(&dir, |bytes| flip_last(bytes));
    let device = DeviceId::new();
    start_over(&dir.journal(), device, &db).unwrap();
    let mut journal = Journal::open(&dir.journal(), device).unwrap();
    catch_up(&journal, &db).unwrap();
    let track = TrackId::new();

    record_and_project(&mut journal, &db, &Op::Like { track }).unwrap();
    let mark = journal.mark();
    drop(journal);
    let journal = Journal::open(&dir.journal(), device).unwrap();

    assert!(journal.state().likes.contains(&track));
    assert_eq!(journal.mark(), mark);
    assert_eq!(catch_up(&journal, &db).unwrap(), CatchUp::UpToDate);
    assert!(db.user_data(track).unwrap().liked);
}
