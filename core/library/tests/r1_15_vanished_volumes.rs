//! Р1.15, стражи: то, что задача менять не должна. Карта, которой нет в списке
//! томов, — вынутая: её файлы становятся недоступными, а когда карта вернулась,
//! снова доступны. Файл, которого на деле нет, пропадает, как раньше. Тесты идут
//! прежним входом `scan` и зелёные до задачи — они держат исполнителя от «починки»,
//! которая спрятала бы настоящую пропажу вместе с ложной.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod r1_15_volumes;

use plinth_library::db::Database;

use r1_15_volumes::{FIRST_SCAN, LATER, Volume, available, run_plain, titles, visible};

/// Вынутая карта: Kotlin отдаёт только основной том. Треки карты недоступны,
/// в списках их нет; карту вернули — они снова доступны, тем же набором.
#[test]
fn an_ejected_card_is_marked_unavailable_and_comes_back() {
    let (phone, card) = (Volume::new(), Volume::new());
    phone.put("Music/Local.mp3");
    card.put("Music/One.mp3");
    card.put("Music/Two.mp3");
    let db = Database::open_in_memory().unwrap();
    let first = run_plain(&db, &[&phone, &card], FIRST_SCAN);
    assert_eq!((first.found, first.added), (3, 3));
    let before = visible(&db);

    card.unmount();
    let ejected = run_plain(&db, &[&phone], LATER);
    assert_eq!((ejected.found, ejected.missing), (1, 2));
    assert_eq!(titles(&db), ["Local"]);
    assert!(!available(&db, &card.uri("Music/One.mp3")));

    card.mount();
    let back = run_plain(&db, &[&phone, &card], LATER + 1);
    assert_eq!((back.found, back.added, back.returned, back.missing), (3, 0, 2, 0));
    assert_eq!(visible(&db), before, "те же треки с теми же ID");
}

/// Корень, которого нет на диске, ядру отдали, а он пропал, — тоже вынутая
/// карта (в бою Kotlin такого корня не отдаёт, но ядро обязано так читать).
#[test]
fn a_root_that_is_gone_from_the_disk_is_an_ejected_card() {
    let (phone, card) = (Volume::new(), Volume::new());
    phone.put("Music/Local.mp3");
    card.put("Music/One.mp3");
    let db = Database::open_in_memory().unwrap();
    run_plain(&db, &[&phone, &card], FIRST_SCAN);

    card.unmount();
    let report = run_plain(&db, &[&phone, &card], LATER);

    assert_eq!((report.missing_volumes, report.missing), (1, 1));
    assert_eq!(titles(&db), ["Local"]);
}

/// Файл, который удалили на деле, пропадает, а соседний — нет.
#[test]
fn a_file_deleted_for_real_is_missing_and_its_neighbour_is_not() {
    let phone = Volume::new();
    phone.put("Music/Kept.mp3");
    phone.put("Music/Deleted.mp3");
    let db = Database::open_in_memory().unwrap();
    run_plain(&db, &[&phone], FIRST_SCAN);

    phone.remove("Music/Deleted.mp3");
    let report = run_plain(&db, &[&phone], LATER);

    assert_eq!((report.found, report.missing), (1, 1));
    assert!(available(&db, &phone.uri("Music/Kept.mp3")));
    assert!(!available(&db, &phone.uri("Music/Deleted.mp3")));
    assert_eq!(titles(&db), ["Kept"]);
}
