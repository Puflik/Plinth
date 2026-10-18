//! Р9е (`docs/work/r9f.md`, ревью R1 №8): журнал начат заново пустым — лайков и плейлистов
//! в нём нет, вернуть их может только копия в папке. Ядро оставляет пометку
//! `restore-pending` рядом с `device` (не в `journal/`: Auto Backup её не уносит), и она
//! живёт, пока данные не вернулись или человек не отказался: поворот экрана и перезапуск
//! приложения её не снимают. Пометка — подсказка экрану, не условие работы: записать её не
//! вышло — ядро всё равно открывается.

use std::fs;

use plinth_sync::journal::Journal;
use plinth_types::{DeviceId, TrackId};

use super::mirror_api::MirrorFile;
use crate::session::{Core, DATABASE, DEVICE, JOURNAL};
use crate::testing::Scratch;

/// Имя пометки в каталоге данных.
const MARK: &str = "restore-pending";

/// Сворачивает журнал ядра в снимок: всё пользовательское — в файле `snapshot`.
fn compact(dir: &Scratch) {
    let device: DeviceId = fs::read_to_string(dir.0.join(DEVICE)).unwrap().trim().parse().unwrap();
    Journal::open(&dir.0.join(JOURNAL), device).unwrap().compact().unwrap();
}

/// Последний байт снимка другой — сумма не сходится.
fn damage(dir: &Scratch) {
    let path = dir.0.join(JOURNAL).join("snapshot");
    let mut bytes = fs::read(&path).unwrap();
    let last = bytes.len() - 1;
    bytes[last] ^= 0xFF;
    fs::write(&path, &bytes).unwrap();
}

fn remove_database(dir: &Scratch) {
    for suffix in ["", "-wal", "-shm"] {
        let _ = fs::remove_file(dir.0.join(format!("{DATABASE}{suffix}")));
    }
}

/// Установка с лайком и копией её журнала «в папке»; снимок журнала испорчен, а базы
/// нет — переносить в новый журнал нечего. Возвращает копию.
fn lost_journal(dir: &Scratch) -> MirrorFile {
    let copy = {
        let core = Core::open(dir.path()).unwrap();
        core.like(TrackId::new()).unwrap();
        core.mirror_copy().unwrap()
    };
    compact(dir);
    damage(dir);
    remove_database(dir);
    copy
}

#[test]
fn a_fresh_installation_has_nothing_pending() {
    let dir = Scratch::new();

    let core = Core::open(dir.path()).unwrap();

    assert!(!core.restore_pending().unwrap());
}

/// Пометка переживает перезапуск; вернули данные из копии — снимается, и это тоже
/// переживает перезапуск.
#[test]
fn a_journal_started_over_with_nothing_carried_stays_pending_until_restored() {
    let dir = Scratch::new();
    let copy = lost_journal(&dir);

    let core = Core::open(dir.path()).unwrap();

    assert!(core.startup_report().unwrap().journal_started_over);
    assert!(core.restore_pending().unwrap());
    drop(core);
    let again = Core::open(dir.path()).unwrap();
    assert!(!again.startup_report().unwrap().journal_started_over, "второй запуск обычный");
    assert!(again.restore_pending().unwrap(), "пометка пережила перезапуск");

    let restored = again.restore_mirror(vec![copy]).unwrap();

    assert_eq!(restored.merged, 1);
    assert!(!again.restore_pending().unwrap(), "данные вернулись — напоминать не о чем");
    drop(again);
    assert!(!Core::open(dir.path()).unwrap().restore_pending().unwrap(), "и после перезапуска");
}

/// Журнал начат заново из базы: лайки и плейлисты переехали, возвращать нечего.
#[test]
fn a_journal_started_over_from_the_database_is_not_pending() {
    let dir = Scratch::new();
    {
        let core = Core::open(dir.path()).unwrap();
        core.like(TrackId::new()).unwrap();
    }
    compact(&dir);
    damage(&dir);

    let core = Core::open(dir.path()).unwrap();

    assert!(core.startup_report().unwrap().journal_started_over);
    assert!(!core.restore_pending().unwrap());
}

/// Осмотр папки и пустое восстановление пометку не снимают: данные не вернулись.
#[test]
fn restoring_nothing_keeps_the_mark() {
    let dir = Scratch::new();
    let copy = lost_journal(&dir);
    let core = Core::open(dir.path()).unwrap();

    let found = core.inspect_mirror(vec![MirrorFile { name: copy.name.clone(), content: copy.content.clone() }]).unwrap();
    let nothing = core.restore_mirror(Vec::new()).unwrap();
    let junk = core.restore_mirror(vec![MirrorFile { name: "notes.txt".to_owned(), content: b"hello".to_vec() }]).unwrap();

    assert!(found.news);
    assert_eq!((nothing.merged, junk.merged, junk.unreadable), (0, 0, 1));
    assert!(core.restore_pending().unwrap());
}

/// Человек отказался от восстановления или закрыл напоминание: пометка снимается, а
/// повторный отказ и отказ без пометки — не ошибка.
#[test]
fn dismissing_clears_the_mark_and_may_be_repeated() {
    let dir = Scratch::new();
    lost_journal(&dir);
    let core = Core::open(dir.path()).unwrap();
    assert!(core.restore_pending().unwrap());

    core.dismiss_restore_pending().unwrap();
    core.dismiss_restore_pending().unwrap();

    assert!(!core.restore_pending().unwrap());
    drop(core);
    assert!(!Core::open(dir.path()).unwrap().restore_pending().unwrap(), "и после перезапуска");
}

/// Пометка лежит рядом с `device`, а не в `journal/`: правила Auto Backup берут журнал, и
/// новый телефон не должен получить напоминание о чужой порче.
#[test]
fn the_mark_stays_out_of_the_journal_folder() {
    let dir = Scratch::new();
    lost_journal(&dir);

    drop(Core::open(dir.path()).unwrap());

    assert!(dir.0.join(MARK).is_file());
    let inside: Vec<String> = fs::read_dir(dir.0.join(JOURNAL))
        .unwrap()
        .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
        .filter(|name| name.contains("pending"))
        .collect();
    assert!(inside.is_empty(), "{inside:?}");
}

/// Пометку записать нельзя (здесь на её месте каталог): это не причина не открывать ядро —
/// отчёт об открытии честный, а пометки просто нет.
#[test]
fn a_mark_that_cannot_be_written_does_not_stop_the_core() {
    let dir = Scratch::new();
    lost_journal(&dir);
    fs::create_dir(dir.0.join(MARK)).unwrap();

    let opened = Core::open(dir.path());

    assert!(opened.is_ok(), "{:?}", opened.as_ref().err());
    let core = opened.unwrap();
    assert!(core.startup_report().unwrap().journal_started_over);
    assert!(!core.restore_pending().unwrap());
}
