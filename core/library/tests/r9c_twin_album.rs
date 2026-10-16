//! Р9в, поправка Opus при чтении плана (`docs/work/r9c.md`, «Ответы Opus»): двойник
//! — только с того же альбома. Кандидаты второго этапа — весь каталог, а не файлы,
//! появившиеся в одном скане с пропажей, и совпадение названия, исполнителя и
//! длительности само по себе слабо: «Intro» с другого альбома не должен забирать
//! лайк ушедшего «Intro», а трек своего альбома — уходить из каталога.
//!
//! Альбом совпадает, если нормализованные названия равны либо альбома нет ни у того,
//! ни у другого; альбом только у одного из двух — не двойник. Копия при переносе в
//! два шага несёт теги оригинала, поправка её не задевает: общие сценарии с тем же
//! альбомом — в `r9c_across_scans.rs`.

mod r9c_support;

use plinth_library::db::Database;
use plinth_library::scan::Tags;
use r9c_support::{FIRST_SCAN, Volume, like, liked, run, song, tagged, track_at, visible};

/// «Intro» без альбома или с ним: название, исполнитель и длительность те же.
fn intro(album: Option<&str>) -> Tags {
    Tags { album: album.map(str::to_owned), ..song("Intro", "Band", "", 60) }
}

/// Пропал лайкнутый «Intro» с альбома One; живёт «Intro» без данных с альбома Two.
/// Ключ и длительность те же, но это другая запись: трек Two на месте со своим
/// файлом, трек One скрыт вместе с лайком. Прежний сканер отдал бы файл Two.
#[test]
fn a_twin_from_another_album_does_not_take_the_data_of_the_vanished() {
    let volume = Volume::new();
    volume.put("Music/One/one.mp3");
    volume.put("Music/Two/two.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader =
        tagged(vec![("one.mp3", song("Intro", "Band", "One", 60)), ("two.mp3", song("Intro", "Band", "Two", 60))]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let one = track_at(&db, &volume, "Music/One/one.mp3").unwrap();
    let two = track_at(&db, &volume, "Music/Two/two.mp3").unwrap();
    like(&db, one);

    volume.remove("Music/One/one.mp3");
    let report = run(&db, &[&volume], &reader, FIRST_SCAN + 1);

    assert_eq!(track_at(&db, &volume, "Music/Two/two.mp3"), Some(two), "трек другого альбома остался со своим файлом");
    assert_eq!(track_at(&db, &volume, "Music/One/one.mp3"), Some(one), "пропавший скрыт на своём пути");
    assert!(liked(&db, one), "лайк на скрытом треке");
    assert_eq!(visible(&db), [(two, Some(volume.uri("Music/Two/two.mp3")))]);
    assert_eq!((report.moved, report.missing), (0, 1), "{report:?}");
}

/// Альбом есть только у одного из двух — не двойник, в обе стороны.
#[test]
fn a_twin_is_not_one_when_only_one_of_the_two_has_an_album() {
    for (vanishing, living) in [(Some("One"), None), (None, Some("One"))] {
        let volume = Volume::new();
        volume.put("Music/original.mp3");
        volume.put("Download/copy.mp3");
        let db = Database::open_in_memory().unwrap();
        let reader = tagged(vec![("original.mp3", intro(vanishing)), ("copy.mp3", intro(living))]);
        run(&db, &[&volume], &reader, FIRST_SCAN);
        let original = track_at(&db, &volume, "Music/original.mp3").unwrap();
        let copy = track_at(&db, &volume, "Download/copy.mp3").unwrap();
        like(&db, original);

        volume.remove("Music/original.mp3");
        let report = run(&db, &[&volume], &reader, FIRST_SCAN + 1);

        assert_eq!(track_at(&db, &volume, "Download/copy.mp3"), Some(copy), "{vanishing:?} против {living:?}");
        assert_eq!(track_at(&db, &volume, "Music/original.mp3"), Some(original));
        assert!(liked(&db, original));
        assert_eq!(visible(&db), [(copy, Some(volume.uri("Download/copy.mp3")))]);
        assert_eq!((report.moved, report.missing), (0, 1), "{vanishing:?} против {living:?}: {report:?}");
    }
}

/// Страж: альбомы сравниваются нормализованными, как в `likeness` — регистр и
/// пробелы не делают копию другим альбомом; без альбома у обоих — тот же альбом.
#[test]
fn the_same_album_in_another_spelling_and_no_album_at_all_are_the_same_album() {
    for (vanishing, living) in [(Some("Greatest Hits"), Some("GREATEST  hits")), (None, None)] {
        let volume = Volume::new();
        volume.put("Music/original.mp3");
        volume.put("Download/copy.mp3");
        let db = Database::open_in_memory().unwrap();
        let reader = tagged(vec![("original.mp3", intro(vanishing)), ("copy.mp3", intro(living))]);
        run(&db, &[&volume], &reader, FIRST_SCAN);
        let original = track_at(&db, &volume, "Music/original.mp3").unwrap();
        let copy = track_at(&db, &volume, "Download/copy.mp3").unwrap();
        like(&db, original);

        volume.remove("Music/original.mp3");
        let report = run(&db, &[&volume], &reader, FIRST_SCAN + 1);

        assert_eq!(track_at(&db, &volume, "Download/copy.mp3"), Some(original), "{vanishing:?} против {living:?}");
        assert_eq!(db.track(copy).unwrap(), None);
        assert!(liked(&db, original));
        assert_eq!((report.moved, report.missing), (1, 0), "{vanishing:?} против {living:?}: {report:?}");
    }
}
