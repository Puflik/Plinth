//! Паспорта треков и перепривязка (C4, ADR 0007): после переустановки скан
//! выдаёт файлам новые ID, а журнал по паспорту узнаёт свои треки, и каталог
//! берёт ID из журнала — лайки, плейлисты и история снова на своих треках.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;

use common::{Scratch, Song, memory_db, play, playlist, scan, track_at};
use plinth_library::db::Database;
use plinth_library::model::{PlaylistEntry, Track, TrackPassport};
use plinth_sync::journal::{Journal, Op, catch_up, describe_missing, record_and_project, relink};
use plinth_types::{DeviceId, PlaylistEntryId, Position, Timestamp, TrackId};

const KINO: Song = Song {
    path: "/storage/emulated/0/Music/Кино/Кукушка.mp3",
    title: "Кукушка",
    artist: "Кино",
    album: Some("Звезда по имени Солнце"),
    duration_ms: Some(398_000),
};

const QUEEN: Song = Song {
    path: "/storage/emulated/0/Music/Queen/Bohemian Rhapsody.mp3",
    title: "Bohemian Rhapsody",
    artist: "Queen",
    album: Some("A Night at the Opera"),
    duration_ms: Some(354_000),
};

/// Та же песня с концерта: название и исполнитель те же, альбом другой.
const QUEEN_LIVE: Song = Song {
    path: "/storage/emulated/0/Music/Queen/Live/Bohemian Rhapsody.mp3",
    title: "Bohemian Rhapsody",
    artist: "Queen",
    album: Some("Live at Wembley '86"),
    duration_ms: Some(355_000),
};

fn journal(dir: &Scratch) -> Journal {
    Journal::open(&dir.journal(), DeviceId::new()).unwrap()
}

fn liked_titles(db: &Database) -> Vec<String> {
    db.liked_tracks().unwrap().into_iter().map(|row| row.title).collect()
}

fn entry_of(playlist: &plinth_library::model::Playlist, track: TrackId) -> PlaylistEntry {
    PlaylistEntry {
        id: PlaylistEntryId::new(),
        playlist: playlist.id,
        track,
        position: Position::first(),
        added_at: Timestamp::now(),
    }
}

/// Прошлая установка: библиотека, лайк Кино, плейлист с Queen и прослушивание.
struct Old {
    _dir: Scratch,
    journal: Journal,
    kino: TrackId,
    queen: TrackId,
}

fn old_installation() -> Old {
    let dir = Scratch::new();
    let db = memory_db();
    scan(&db, &[KINO, QUEEN]);
    let mut journal = journal(&dir);
    let (kino, queen) = (track_at(&db, KINO), track_at(&db, QUEEN));
    let road = playlist("Road");
    for op in [
        Op::Like { track: kino },
        Op::CreatePlaylist(road.clone()),
        Op::AddEntry(entry_of(&road, queen)),
        Op::Play(play(kino, 1_790_000_000_000, 300, Some(398))),
    ] {
        record_and_project(&mut journal, &db, &op).unwrap();
    }
    Old { _dir: dir, journal, kino, queen }
}

/// Вливает журнал прошлой установки в новый и пересобирает проекцию.
fn restore(into: &mut Journal, db: &Database, old: &Old) {
    into.merge(&old.journal.updates_since(&into.state_vector()).unwrap()).unwrap();
    catch_up(into, db).unwrap();
}

#[test]
fn a_user_op_describes_its_catalog_track_in_the_same_change() {
    let dir = Scratch::new();
    let db = memory_db();
    scan(&db, &[KINO]);
    let mut journal = journal(&dir);
    let kino = track_at(&db, KINO);
    let before = journal.mark();

    record_and_project(&mut journal, &db, &Op::Like { track: kino }).unwrap();

    assert_eq!(journal.state().passports, vec![db.track_passport(kino).unwrap().unwrap()]);
    assert_eq!(journal.mark().seq, before.seq + 1);
}

#[test]
fn a_track_outside_the_catalog_is_not_described() {
    let dir = Scratch::new();
    let db = memory_db();
    let mut journal = journal(&dir);

    record_and_project(&mut journal, &db, &Op::Like { track: TrackId::new() }).unwrap();

    assert!(journal.state().passports.is_empty());
}

/// Теги поправили — следующее действие с треком описывает его заново.
#[test]
fn a_retagged_track_is_described_again() {
    let dir = Scratch::new();
    let db = memory_db();
    scan(&db, &[KINO]);
    let mut journal = journal(&dir);
    let kino = track_at(&db, KINO);
    record_and_project(&mut journal, &db, &Op::Like { track: kino }).unwrap();
    let retagged = Track { title: "Кукушка (ремастер)".to_owned(), ..db.track(kino).unwrap().unwrap() };
    db.save_track(&retagged).unwrap();

    record_and_project(&mut journal, &db, &Op::Play(play(kino, 1, 300, None))).unwrap();

    let passports = journal.state().passports;
    assert_eq!(passports.len(), 1);
    assert_eq!(passports[0].title, "Кукушка (ремастер)");
}

/// Журнал до C4: данные есть, паспортов нет. Их дописывает одна правка,
/// повтор ничего не меняет.
#[test]
fn old_user_data_gets_its_passports() {
    let dir = Scratch::new();
    let db = memory_db();
    scan(&db, &[KINO, QUEEN]);
    let mut journal = journal(&dir);
    let (kino, queen) = (track_at(&db, KINO), track_at(&db, QUEEN));
    let road = playlist("Road");
    for op in [Op::Like { track: kino }, Op::CreatePlaylist(road.clone()), Op::AddEntry(entry_of(&road, queen))] {
        journal.record(&op).unwrap();
    }
    catch_up(&journal, &db).unwrap();
    let before = journal.mark();

    assert_eq!(describe_missing(&mut journal, &db).unwrap(), 2);
    assert_eq!(describe_missing(&mut journal, &db).unwrap(), 0);

    let mut described: Vec<TrackId> = journal.state().passports.into_iter().map(|p| p.track).collect();
    described.sort();
    let mut expected = vec![kino, queen];
    expected.sort();
    assert_eq!(described, expected);
    assert_eq!(journal.mark().seq, before.seq + 1);
    assert_eq!(catch_up(&journal, &db).unwrap(), plinth_sync::journal::CatchUp::UpToDate);
}

/// Переустановка: скан выдал новые ID, слитый журнал узнаёт свои треки.
#[test]
fn a_rescanned_library_takes_its_ids_from_the_journal() {
    let old = old_installation();
    let dir = Scratch::new();
    let db = memory_db();
    scan(&db, &[KINO, QUEEN]);
    assert_ne!(track_at(&db, KINO), old.kino);
    let mut journal = journal(&dir);
    restore(&mut journal, &db, &old);
    assert!(liked_titles(&db).is_empty(), "до перепривязки лайк висит на старом ID");

    assert_eq!(relink(&journal, &db).unwrap(), 2);

    assert_eq!((track_at(&db, KINO), track_at(&db, QUEEN)), (old.kino, old.queen));
    assert_eq!(liked_titles(&db), ["Кукушка"]);
    let road = &db.playlists().unwrap()[0];
    let tracks: Vec<TrackId> = db.entries(road.id).unwrap().into_iter().map(|e| e.track).collect();
    assert_eq!(tracks, [old.queen]);
    assert_eq!(db.user_data(old.kino).unwrap().play_count, 1);
    assert_eq!(relink(&journal, &db).unwrap(), 0, "второй раз перепривязывать нечего");
}

/// Сначала восстановление, потом скан: перепривязка после скана.
#[test]
fn a_restore_before_the_scan_relinks_after_it() {
    let old = old_installation();
    let dir = Scratch::new();
    let db = memory_db();
    let mut journal = journal(&dir);
    restore(&mut journal, &db, &old);
    assert_eq!(relink(&journal, &db).unwrap(), 0, "каталог ещё пуст");

    scan(&db, &[QUEEN, KINO]);
    relink(&journal, &db).unwrap();

    assert_eq!(liked_titles(&db), ["Кукушка"]);
    assert_eq!(track_at(&db, QUEEN), old.queen);
}

/// Текст сравнивается нормализованным: регистр, «ё» и знаки не мешают.
#[test]
fn text_is_compared_normalized() {
    let dir = Scratch::new();
    let db = memory_db();
    scan(&db, &[KINO]);
    let mut journal = journal(&dir);
    let old_id = TrackId::new();
    let passport = TrackPassport {
        track: old_id,
        title: "КУКУШКА!".to_owned(),
        artist: "кино".to_owned(),
        album: None,
        duration: None,
        mbid: None,
    };
    journal.record_all(&[Op::Describe(passport), Op::Like { track: old_id }]).unwrap();
    catch_up(&journal, &db).unwrap();

    assert_eq!(relink(&journal, &db).unwrap(), 1);

    assert_eq!(track_at(&db, KINO), old_id);
}

/// Одна песня с альбома и с концерта, длительность обеих в пределах двух
/// секунд: паспорт с альбомом берёт свой файл. Длительность дальше двух
/// секунд — не тот трек.
#[test]
fn album_and_duration_pick_the_right_file() {
    let dir = Scratch::new();
    let db = memory_db();
    scan(&db, &[QUEEN, QUEEN_LIVE]);
    let mut journal = journal(&dir);
    let (live, other_cut) = (TrackId::new(), TrackId::new());
    let described = |track, album: &str, duration_ms| TrackPassport {
        track,
        title: "Bohemian Rhapsody".to_owned(),
        artist: "Queen".to_owned(),
        album: Some(album.to_owned()),
        duration: Some(std::time::Duration::from_millis(duration_ms)),
        mbid: None,
    };
    journal
        .record_all(&[
            Op::Describe(described(live, "Live at Wembley '86", 355_000)),
            Op::Describe(described(other_cut, "Greatest Hits", 300_000)),
            Op::Like { track: live },
            Op::Like { track: other_cut },
        ])
        .unwrap();
    catch_up(&journal, &db).unwrap();

    assert_eq!(relink(&journal, &db).unwrap(), 1);

    assert_eq!(track_at(&db, QUEEN_LIVE), live);
    assert_ne!(track_at(&db, QUEEN), other_cut);
}

/// Трек, на который новая установка успела поставить свои данные, не
/// перехватывается: его данные остались бы без трека.
#[test]
fn a_track_with_data_of_this_installation_is_not_taken() {
    let old = old_installation();
    let dir = Scratch::new();
    let db = memory_db();
    scan(&db, &[KINO, QUEEN]);
    let mut journal = journal(&dir);
    let fresh_kino = track_at(&db, KINO);
    record_and_project(&mut journal, &db, &Op::Like { track: fresh_kino }).unwrap();
    restore(&mut journal, &db, &old);

    assert_eq!(relink(&journal, &db).unwrap(), 1);

    assert_eq!(track_at(&db, KINO), fresh_kino);
    assert_eq!(track_at(&db, QUEEN), old.queen);
    assert_eq!(liked_titles(&db), ["Кукушка"]);
}
