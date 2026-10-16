//! Р9в, находки 11 и 12 ревью R1 (`docs/review/r1-review.md`, `docs/work/r9c.md`):
//! новые файлы сводились с пропавшими по очереди пути, и каждый брал лучшего из
//! оставшихся — лучшая пара проигрывала первой попавшейся (12); а при равном
//! сходстве выбор решал возраст ID, не глядя, у кого из пропавших есть данные
//! пользователя (11).
//!
//! Теперь сначала раздаются пропавшие с данными пользователя, потом остальные; в
//! каждой группе пары идут лучшими вперёд — по сходству, при равном сходстве первым
//! идёт пропавший в этом скане, потом более старый трек.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod r9c_support;

use plinth_library::db::Database;
use r9c_support::{FIRST_SCAN, Volume, like, liked, run, song, tagged, tick, track_at};

/// Находка 12. Папку «Opera» с лайкнутой «Bohemian Rhapsody» перенесли в
/// `Music/Queen/Opera`, в тот же заход докачали `Music/Queen/Greatest Hits` с
/// той же записью. Сборник идёт раньше по пути, но с альбомом совпадает файл
/// из «Opera»: трек достаётся ему, а сборник — новый трек.
#[test]
fn the_file_that_matches_best_takes_the_track_not_the_first_one_by_path() {
    let volume = Volume::new();
    volume.put("Music/Opera/Bohemian.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![
        ("Bohemian.mp3", song("Bohemian Rhapsody", "Queen", "A Night at the Opera", 355)),
        ("Bohemian (hits).mp3", song("Bohemian Rhapsody", "Queen", "Greatest Hits", 355)),
    ]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let track = track_at(&db, &volume, "Music/Opera/Bohemian.mp3").unwrap();
    like(&db, track);

    volume.shift("Music/Opera/Bohemian.mp3", "Music/Queen/Opera/Bohemian.mp3");
    volume.put("Music/Queen/Greatest Hits/Bohemian (hits).mp3");
    let report = run(&db, &[&volume], &reader, FIRST_SCAN + 1);

    assert_eq!(track_at(&db, &volume, "Music/Queen/Opera/Bohemian.mp3"), Some(track), "лучшая пара — по альбому");
    let hits = track_at(&db, &volume, "Music/Queen/Greatest Hits/Bohemian (hits).mp3").unwrap();
    assert_ne!(hits, track, "сборник — новый трек");
    assert!(liked(&db, track));
    assert_eq!((report.moved, report.added, report.missing), (1, 1, 0), "{report:?}");
}

/// Находка 11. Одна песня лежала дважды — в `Download` (трек старше, данных
/// нет) и в папке альбома (трек новее, лайк). Дубль из `Download` удалён, а
/// альбом в тот же заход разложен по папкам исполнителей: сходство у обоих
/// пропавших равное. Файл достаётся лайкнутому треку, а не старшему.
#[test]
fn of_two_equal_vanished_tracks_the_one_with_data_takes_the_moved_file() {
    let volume = Volume::new();
    volume.put("Download/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let older = track_at(&db, &volume, "Download/Song.mp3").unwrap();
    tick();
    volume.copy("Download/Song.mp3", "Music/Album/Song.mp3");
    run(&db, &[&volume], &reader, FIRST_SCAN + 1);
    let newer = track_at(&db, &volume, "Music/Album/Song.mp3").unwrap();
    assert!(older < newer, "в тесте дубль из Download — старший трек");
    like(&db, newer);

    volume.remove("Download/Song.mp3");
    volume.shift("Music/Album/Song.mp3", "Music/Band/Song.mp3");
    let report = run(&db, &[&volume], &reader, FIRST_SCAN + 2);

    assert_eq!(track_at(&db, &volume, "Music/Band/Song.mp3"), Some(newer), "файл — треку с данными");
    assert!(liked(&db, newer));
    assert_eq!(track_at(&db, &volume, "Download/Song.mp3"), Some(older), "дубль без данных остаётся скрытым");
    assert_eq!((report.moved, report.missing, report.added), (1, 1, 0), "{report:?}");
}

/// Данные идут первыми: пропавший трек с данными берёт файл раньше пропавшего без
/// данных — даже когда второй подходит файлу лучше (альбом совпал). Одна песня
/// на пропавшем треке с данными и на пропавшем треке без них: отдавать файл треку,
/// у которого нечего терять, значит оставить данные скрытыми при живом файле.
#[test]
fn a_track_with_data_takes_the_file_before_one_that_matches_it_better() {
    let volume = Volume::new();
    volume.put("Music/One/intro one.mp3");
    volume.put("Music/Two/intro two.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![
        ("intro one.mp3", song("Intro", "Band", "One", 60)),
        ("intro two.mp3", song("Intro", "Band", "Two", 60)),
        ("fresh.mp3", song("Intro", "Band", "Two", 60)),
    ]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let one = track_at(&db, &volume, "Music/One/intro one.mp3").unwrap();
    let two = track_at(&db, &volume, "Music/Two/intro two.mp3").unwrap();
    like(&db, one);

    volume.remove("Music/One/intro one.mp3");
    volume.remove("Music/Two/intro two.mp3");
    volume.put("Music/Intros/fresh.mp3");
    let report = run(&db, &[&volume], &reader, FIRST_SCAN + 1);

    assert_eq!(track_at(&db, &volume, "Music/Intros/fresh.mp3"), Some(one), "файл — треку с данными");
    assert!(liked(&db, one));
    assert_eq!(track_at(&db, &volume, "Music/Two/intro two.mp3"), Some(two), "трек без данных скрыт на своём пути");
    assert_eq!((report.moved, report.missing, report.added), (1, 1, 0), "{report:?}");
}
