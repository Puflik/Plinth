//! Р9б (`docs/work/r9b.md`, находка 4 ревью R1, вторая половина): открытие
//! журнала не требует места. Испорченный хвост откладывается копией рядом и
//! тут же свёртывается в снимок; обе записи нуждаются в месте. Раньше ошибка любой
//! из них прерывала `Journal::open`, и ядро не открывалось при каждом запуске,
//! пока человек не освободит место: приложение, которое просит места, чтобы
//! показать лайки, — худший вид отказа.
//!
//! Отказ записи имитирован каталогом на месте `snapshot.tmp`: снимок не записать.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r9b_support;

use std::fs;

use plinth_sync::journal::{Journal, Op};
use plinth_types::{DeviceId, TrackId};
use r9b_support::{FRAME_HEADER, Pristine, kept_aside, liked, sorted, spans};

const FRAMES: usize = 6;

/// Хвост из шести лайков, у третьего кадра испорчены данные: сумма не сходится.
fn damaged_tail(pristine: &Pristine) -> Vec<u8> {
    let mut bytes = pristine.tail.clone();
    bytes[spans(&pristine.tail)[2].0 + FRAME_HEADER] ^= 0xff;
    bytes
}

/// Испорченный хвост при записи, которая не удаётся: журнал открывается, читается
/// всё до порчи, улика на месте; когда место появилось, первая же запись
/// чинит всё — копия рядом, хвост начат заново.
#[test]
fn a_damaged_tail_opens_even_when_nothing_can_be_written() {
    let pristine = Pristine::new(FRAMES);
    let damaged = damaged_tail(&pristine);
    let dir = pristine.dir_with(&damaged);
    fs::create_dir(dir.journal().join("snapshot.tmp")).unwrap();

    let opened = Journal::open(&dir.journal(), DeviceId::new());

    let mut journal = opened.expect("открытие не должно зависеть от места: данные до порчи целы");
    assert_eq!(sorted(journal.state().likes), pristine.first(2), "читается всё до порчи");
    let evidence = fs::read(dir.journal().join("tail")).unwrap() == damaged
        || kept_aside(&dir).iter().any(|copy| fs::read(copy).unwrap() == damaged);
    assert!(evidence, "ни хвоста как он был, ни его копии: порчу нельзя разобрать");

    fs::remove_dir(dir.journal().join("snapshot.tmp")).unwrap();
    let fresh = TrackId::new();
    journal.record(&Op::Like { track: fresh }).unwrap();
    drop(journal);

    assert_eq!(liked(&dir), sorted([pristine.first(2), vec![fresh]].concat()), "память догнала диск");
    let copies = kept_aside(&dir);
    assert_eq!(copies.len(), 1, "копия рядом — одна");
    assert_eq!(fs::read(&copies[0]).unwrap(), damaged, "копия — хвост как он был");
}

/// Пока записать нельзя, журнал не делает вид, что получилось: запись — ошибка, а
/// не молчаливый отказ. Повторное открытие в тех же условиях даёт то же самое,
/// и копии рядом не копятся с каждым запуском.
#[test]
fn reopening_without_space_changes_nothing_and_piles_up_nothing() {
    let pristine = Pristine::new(FRAMES);
    let damaged = damaged_tail(&pristine);
    let dir = pristine.dir_with(&damaged);
    fs::create_dir(dir.journal().join("snapshot.tmp")).unwrap();

    for attempt in 0..3 {
        let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
        assert_eq!(sorted(journal.state().likes), pristine.first(2), "попытка {attempt}");
        // Запись невозможна, и журнал не делает вид, что получилось.
        assert!(journal.record(&Op::Like { track: TrackId::new() }).is_err(), "попытка {attempt}");
    }

    assert!(kept_aside(&dir).len() <= 1, "копии не множатся при каждом запуске");
    let evidence = fs::read(dir.journal().join("tail")).unwrap() == damaged
        || kept_aside(&dir).iter().any(|copy| fs::read(copy).unwrap() == damaged);
    assert!(evidence);
}
