//! Р1.15 (находка 5 ревью FFI/сканера): карта после загрузки или вставки ещё
//! проверяется — система о ней знает, но тома не отдаёт. Kotlin отдаёт такой
//! том как `pending`: скан его не обходит и о его файлах ничего не решает —
//! они не пропали и не вернулись. Вынутая карта (её нет ни в `ready`, ни в
//! `pending`) по-прежнему недоступна; сведение перенесённых (Р1.2) не берёт
//! файлы непроверенной карты за пропавшие.
//!
//! Новый вход — `Volumes` и `scan_volumes` (сигнатуры — в `docs/work/r1-15.md`):
//! пока их нет, файл не собирается.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod r1_15_volumes;

use std::path::{MAIN_SEPARATOR, PathBuf};

use plinth_library::db::Database;
use plinth_library::scan::{FileNameOnly, FolderConfig, ScanReport, TagReader, Volumes, scan_volumes};
use plinth_types::Timestamp;

use r1_15_volumes::{Base, ByName, FIRST_SCAN, LATER, Volume, available, song, titles, track_at, visible};

/// Скан новым входом: `ready` обходится, `pending` скан только знает.
fn run_pending(db: &Database, ready: &[&Volume], pending: &[&Volume], reader: &dyn TagReader, at: i64) -> ScanReport {
    let volumes = Volumes {
        ready: ready.iter().map(|volume| volume.root.clone()).collect(),
        pending: pending.iter().map(|volume| volume.root.clone()).collect(),
    };
    scan_volumes(db, &volumes, &FolderConfig::default(), reader, &mut |_| true, Timestamp::from_millis(at)).unwrap()
}

/// Сценарий ревью: телефон загрузился, карту проверяют. Её треки остаются в
/// списках и на своих источниках, теги не перечитываются; карта смонтировалась —
/// следующий скан находит всё нетронутым. Проверяющаяся карта бывает без точки
/// монтирования вовсе и с пустой точкой — тест гоняет оба вида.
#[test]
fn a_card_that_is_being_checked_keeps_its_tracks() {
    let (phone, card) = (Volume::new(), Volume::new());
    phone.put("Music/Local.mp3");
    card.put("Music/One.mp3");
    card.put("Music/Two.mp3");
    let db = Database::open_in_memory().unwrap();
    run_pending(&db, &[&phone, &card], &[], &FileNameOnly, FIRST_SCAN);
    let before = visible(&db);
    assert_eq!(before.len(), 3);

    let unmounts: [fn(&Volume); 2] = [Volume::unmount, Volume::unmount_leaving_mount_point];
    let mut at = LATER;
    for unmount in unmounts {
        unmount(&card);
        let checking = run_pending(&db, &[&phone], &[&card], &FileNameOnly, at);
        assert_eq!((checking.found, checking.added, checking.changed), (1, 0, 0));
        assert_eq!((checking.returned, checking.missing, checking.moved), (0, 0, 0));
        assert_eq!(visible(&db), before, "пока карту проверяют, её треки на месте");
        assert!(available(&db, &card.uri("Music/One.mp3")));

        card.mount();
        at += 1;
        let mounted = run_pending(&db, &[&phone, &card], &[], &FileNameOnly, at);
        assert_eq!((mounted.found, mounted.added, mounted.changed), (3, 0, 0));
        assert_eq!((mounted.returned, mounted.missing), (0, 0));
        assert_eq!(visible(&db), before, "карта смонтировалась: те же треки с теми же ID");
        at += 1;
    }
}

/// Две карты: одну проверяют, другую вынули. Первая не трогается, вторая
/// недоступна — «вынутая» определяется отсутствием в обоих списках, а не
/// тем, что в системе вообще есть другая карта.
#[test]
fn one_card_is_being_checked_while_another_was_ejected() {
    let (phone, checked, ejected) = (Volume::new(), Volume::new(), Volume::new());
    phone.put("Music/Local.mp3");
    checked.put("Music/Checked.mp3");
    ejected.put("Music/Ejected.mp3");
    let db = Database::open_in_memory().unwrap();
    run_pending(&db, &[&phone, &checked, &ejected], &[], &FileNameOnly, FIRST_SCAN);

    checked.unmount();
    ejected.unmount();
    let report = run_pending(&db, &[&phone], &[&checked], &FileNameOnly, LATER);

    assert_eq!((report.found, report.missing), (1, 1));
    assert_eq!(titles(&db), ["Checked", "Local"]);
    assert!(!available(&db, &ejected.uri("Music/Ejected.mp3")));

    checked.mount();
    ejected.mount();
    let back = run_pending(&db, &[&phone, &checked, &ejected], &[], &FileNameOnly, LATER + 1);
    assert_eq!((back.added, back.returned, back.missing), (0, 1, 0));
    assert_eq!(titles(&db), ["Checked", "Ejected", "Local"]);
}

/// Вынутая карта скрыта, и её вставили: пока том не отдан, его треки не
/// оживают — файлов никто не видел, — а когда отдан, оживают.
#[test]
fn a_card_that_was_ejected_comes_back_only_when_it_is_ready() {
    let (phone, card) = (Volume::new(), Volume::new());
    phone.put("Music/Local.mp3");
    card.put("Music/One.mp3");
    let db = Database::open_in_memory().unwrap();
    run_pending(&db, &[&phone, &card], &[], &FileNameOnly, FIRST_SCAN);
    card.unmount();
    let ejected = run_pending(&db, &[&phone], &[], &FileNameOnly, LATER);
    assert_eq!(ejected.missing, 1);

    let checking = run_pending(&db, &[&phone], &[&card], &FileNameOnly, LATER + 1);
    assert_eq!((checking.returned, checking.missing), (0, 0));
    assert_eq!(titles(&db), ["Local"], "пока том не отдан, его треки не оживают");

    card.mount();
    let mounted = run_pending(&db, &[&phone, &card], &[], &FileNameOnly, LATER + 2);
    assert_eq!(mounted.returned, 1);
    assert_eq!(titles(&db), ["Local", "One"]);
}

/// Р1.2 и непроверенная карта. Копию песни с карты положили на телефон, пока
/// карту проверяют: оригинал никуда не делся, поэтому копия — новый трек, а не
/// «новое место» трека карты. Когда карта смонтировалась, оба трека на месте.
#[test]
fn a_copy_of_a_file_on_a_card_that_is_being_checked_is_a_new_track() {
    let (phone, card) = (Volume::new(), Volume::new());
    card.put("Music/Song.mp3");
    let reader = ByName(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    let db = Database::open_in_memory().unwrap();
    run_pending(&db, &[&phone, &card], &[], &reader, FIRST_SCAN);
    let on_card = track_at(&db, &card, "Music/Song.mp3").unwrap();
    let source = db.known_local_files().unwrap()[&card.uri("Music/Song.mp3")].source;

    card.unmount();
    phone.put("Music/Song.mp3");
    let report = run_pending(&db, &[&phone], &[&card], &reader, LATER);

    assert_eq!((report.added, report.moved, report.missing), (1, 0, 0));
    let copy = track_at(&db, &phone, "Music/Song.mp3").unwrap();
    assert_ne!(copy, on_card, "оригинал на карте цел: копия — новый трек");
    assert_eq!(track_at(&db, &card, "Music/Song.mp3"), Some(on_card));
    assert_eq!(db.known_local_files().unwrap()[&card.uri("Music/Song.mp3")].source, source);
    assert_eq!(visible(&db).len(), 2);

    card.mount();
    let mounted = run_pending(&db, &[&phone, &card], &[], &reader, LATER + 1);
    assert_eq!((mounted.added, mounted.moved, mounted.missing, mounted.returned), (0, 0, 0, 0));
    assert_eq!(visible(&db).len(), 2);
}

/// Корень `pending` закрывает свою папку целиком и ничего рядом: `Card2` не
/// «под» `Card`, хотя имя начинается так же. Корень с разделителем в конце —
/// как его мог отдать Kotlin — тот же том.
#[test]
fn a_pending_root_covers_its_own_folder_and_nothing_next_to_it() {
    let base = Base::new();
    let (card, card_two) = (Volume::inside(&base, "Card"), Volume::inside(&base, "Card2"));
    card.put("Music/One.mp3");
    card_two.put("Music/Two.mp3");
    let db = Database::open_in_memory().unwrap();
    run_pending(&db, &[&card, &card_two], &[], &FileNameOnly, FIRST_SCAN);

    card.unmount();
    card_two.unmount();
    let with_separator = PathBuf::from(format!("{}{MAIN_SEPARATOR}", card.root.display()));
    let volumes = Volumes { ready: Vec::new(), pending: vec![with_separator] };
    let report = scan_volumes(
        &db,
        &volumes,
        &FolderConfig::default(),
        &FileNameOnly,
        &mut |_| true,
        Timestamp::from_millis(LATER),
    )
    .unwrap();

    assert_eq!(report.missing, 1, "Card2 не под защитой Card");
    assert_eq!(titles(&db), ["One"]);
}

/// Том в обоих списках обходится как готовый: его пропавшие файлы пропадают.
/// Защита `pending` не должна прятать настоящую пропажу на томе, который на
/// самом деле виден.
#[test]
fn a_volume_listed_both_ways_is_walked_as_ready() {
    let card = Volume::new();
    card.put("Music/One.mp3");
    card.put("Music/Two.mp3");
    let db = Database::open_in_memory().unwrap();
    run_pending(&db, &[&card], &[], &FileNameOnly, FIRST_SCAN);

    card.remove("Music/Two.mp3");
    let report = run_pending(&db, &[&card], &[&card], &FileNameOnly, LATER);

    assert_eq!((report.found, report.missing), (1, 1));
    assert_eq!(titles(&db), ["One"]);
}

/// Без `pending` новый вход — прежний `scan`: те же отчёты, тот же каталог.
#[test]
fn without_pending_volumes_scan_volumes_is_scan() {
    let phone = Volume::new();
    phone.put("Music/A.mp3");
    phone.put("Music/B.mp3");
    phone.put("Download/C.mp3");
    let (old, new) = (Database::open_in_memory().unwrap(), Database::open_in_memory().unwrap());

    let first = (
        r1_15_volumes::run_plain(&old, &[&phone], FIRST_SCAN),
        run_pending(&new, &[&phone], &[], &FileNameOnly, FIRST_SCAN),
    );
    phone.remove("Music/B.mp3");
    phone.put("Music/D.mp3");
    let second =
        (r1_15_volumes::run_plain(&old, &[&phone], LATER), run_pending(&new, &[&phone], &[], &FileNameOnly, LATER));

    assert_eq!(first.0, first.1);
    assert_eq!(second.0, second.1);
    assert_eq!((second.1.added, second.1.missing), (1, 1));
    assert_eq!(titles(&old), titles(&new));
}
