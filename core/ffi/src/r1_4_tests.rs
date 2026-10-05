//! Р1.4 (`docs/work/r1-4.md`, ревью v0.2, находка 4): снимок журнала, который
//! не читается, больше не запирает ядро. Он ложится рядом копией, журнал
//! начинается заново с тем пользовательским, что показывала база, а у
//! установки новое имя: копия в папке, записанная до порчи, становится чужой —
//! её не перезапишут, и из неё можно восстановиться.

use std::fs;
use std::path::PathBuf;

use plinth_sync::journal::Journal;
use plinth_types::{CoreError, DeviceId, TrackId};

use crate::session::{Core, DATABASE, DEVICE, JOURNAL};
use crate::testing::Scratch;

fn snapshot(dir: &Scratch) -> PathBuf {
    dir.0.join(JOURNAL).join("snapshot")
}

/// Сворачивает журнал ядра в снимок: всё пользовательское — в файле
/// `snapshot`, хвост пуст. Так выглядит журнал, проживший не один сеанс.
fn compact(dir: &Scratch) {
    let device: DeviceId = fs::read_to_string(dir.0.join(DEVICE)).unwrap().trim().parse().unwrap();
    Journal::open(&dir.0.join(JOURNAL), device).unwrap().compact().unwrap();
}

/// Последний байт снимка другой — сумма не сходится. Испорченные байты.
fn damage(dir: &Scratch) -> Vec<u8> {
    let path = snapshot(dir);
    let mut bytes = fs::read(&path).unwrap();
    let last = bytes.len() - 1;
    bytes[last] ^= 0xFF;
    fs::write(&path, &bytes).unwrap();
    bytes
}

/// Отложенные в сторону снимки.
fn aside(dir: &Scratch) -> Vec<String> {
    fs::read_dir(dir.0.join(JOURNAL))
        .unwrap()
        .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
        .filter(|name| name.starts_with("snapshot.damaged-"))
        .collect()
}

fn remove_database(dir: &Scratch) {
    for suffix in ["", "-wal", "-shm"] {
        let _ = fs::remove_file(dir.0.join(format!("{DATABASE}{suffix}")));
    }
}

/// Порча снимка не запирает ядро: лайк и плейлист переходят в новый журнал из
/// базы, испорченный файл лежит рядом байт в байт.
#[test]
fn a_damaged_journal_snapshot_no_longer_locks_the_core() {
    let dir = Scratch::new();
    let track = TrackId::new();
    let road = {
        let core = Core::open(dir.path()).unwrap();
        core.like(track).unwrap();
        let road = core.create_playlist("Road".to_owned()).unwrap();
        core.add_to_playlist(road.id, track, None).unwrap();
        road
    };
    compact(&dir);
    let damaged = damage(&dir);

    let opened = Core::open(dir.path());

    assert!(opened.is_ok(), "the core is locked out: {:?}", opened.as_ref().err());
    let core = opened.unwrap();
    assert!(core.user_data(track).unwrap().liked, "the like came over from the database");
    assert_eq!(core.playlists().unwrap(), vec![road.clone()]);
    assert_eq!(core.playlist_items(road.id).unwrap().len(), 1);
    let kept = aside(&dir);
    assert_eq!(kept.len(), 1, "{kept:?}");
    assert_eq!(fs::read(dir.0.join(JOURNAL).join(&kept[0])).unwrap(), damaged);
}

/// Копия в папке, записанная до порчи, этой установке больше не своя: новые
/// копии идут под новым именем, а старая читается как чужая — её не
/// перезапишут, и из неё можно вернуть недостающее.
#[test]
fn the_folder_copy_made_before_the_damage_is_read_and_not_overwritten() {
    let dir = Scratch::new();
    let before = {
        let core = Core::open(dir.path()).unwrap();
        core.like(TrackId::new()).unwrap();
        core.mirror_copy().unwrap()
    };
    compact(&dir);
    damage(&dir);

    let opened = Core::open(dir.path());

    assert!(opened.is_ok(), "the core is locked out: {:?}", opened.as_ref().err());
    let core = opened.unwrap();
    assert_ne!(core.mirror_copy().unwrap().name, before.name, "new copies go to a new file");
    let found = core.inspect_mirror(vec![before]).unwrap();
    assert_eq!(found.likes, 1, "the copy made before the damage is read, not skipped as this installation's own");
}

/// Пропала и база — переносить нечего, журнал начат пустым. Всё возвращает
/// копия из папки.
#[test]
fn with_the_database_gone_too_the_folder_copy_brings_everything_back() {
    let dir = Scratch::new();
    let track = TrackId::new();
    let copy = {
        let core = Core::open(dir.path()).unwrap();
        core.like(track).unwrap();
        let road = core.create_playlist("Road".to_owned()).unwrap();
        core.add_to_playlist(road.id, track, None).unwrap();
        core.mirror_copy().unwrap()
    };
    compact(&dir);
    damage(&dir);
    remove_database(&dir);

    let opened = Core::open(dir.path());

    assert!(opened.is_ok(), "the core is locked out: {:?}", opened.as_ref().err());
    let core = opened.unwrap();
    assert!(!core.user_data(track).unwrap().liked, "nothing to carry over: the database was gone");
    let restored = core.restore_mirror(vec![copy]).unwrap();
    assert_eq!(restored.merged, 1);
    assert!(core.user_data(track).unwrap().liked);
    assert_eq!(core.playlists().unwrap().len(), 1);
}

/// Страж: журнал более новой версии приложения не испорчен. Ядро, как и
/// прежде, не открывается и ничего не трогает — ни снимок, ни имя установки.
#[test]
fn a_journal_from_a_newer_app_still_stops_the_core_and_stays_untouched() {
    let dir = Scratch::new();
    Core::open(dir.path()).unwrap().like(TrackId::new()).unwrap();
    compact(&dir);
    let path = snapshot(&dir);
    let mut bytes = fs::read(&path).unwrap();
    bytes[4] += 1;
    fs::write(&path, &bytes).unwrap();
    let device = fs::read_to_string(dir.0.join(DEVICE)).unwrap();

    let opened = Core::open(dir.path());

    assert!(matches!(opened, Err(CoreError::Storage { .. })), "{:?}", opened.as_ref().err());
    assert_eq!(fs::read(&path).unwrap(), bytes);
    assert_eq!(fs::read_to_string(dir.0.join(DEVICE)).unwrap(), device);
    assert!(aside(&dir).is_empty());
}

/// Отчёт о запуске говорит, что журнал начат заново и что из базы было что
/// перенести. Следующее открытие — обычное: ничего не восстанавливает и новых
/// копий не откладывает.
#[test]
fn after_starting_over_the_next_open_is_ordinary() {
    let dir = Scratch::new();
    let track = TrackId::new();
    Core::open(dir.path()).unwrap().like(track).unwrap();
    compact(&dir);
    damage(&dir);
    let report = Core::open(dir.path()).unwrap().startup_report().unwrap();
    assert!(report.journal_started_over);
    assert!(report.restored_from_journal, "the like came over from the database");
    assert!(!report.database_recovered);

    let core = Core::open(dir.path()).unwrap();

    let report = core.startup_report().unwrap();
    assert!(!report.journal_started_over);
    assert!(!report.restored_from_journal);
    assert!(core.user_data(track).unwrap().liked);
    assert_eq!(aside(&dir).len(), 1);
}

/// Базы не было — журнал начат заново пустым, и отчёт это различает: вернуть
/// данные может только копия в папке.
#[test]
fn a_journal_started_over_with_nothing_to_carry_says_so() {
    let dir = Scratch::new();
    Core::open(dir.path()).unwrap().like(TrackId::new()).unwrap();
    compact(&dir);
    damage(&dir);
    remove_database(&dir);

    let report = Core::open(dir.path()).unwrap().startup_report().unwrap();

    assert!(report.journal_started_over);
    assert!(!report.restored_from_journal, "nothing came over: only the folder copy can bring the data back");
}
