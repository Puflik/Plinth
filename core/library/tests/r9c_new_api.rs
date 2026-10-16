//! Р9в (`docs/work/r9c.md`): новый API задачи — раздача пар паспортов лучшими
//! вперёд (`TrackPassport::pair_up`, общая для скана и — после Р9г — для
//! перепривязки) и трек по версии (`Database::track_of_version`). Файл не
//! собирается, пока их нет: сигнатуры — в разделе «Решение» файла задачи.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod r9c_support;

use std::time::Duration;

use plinth_library::db::Database;
use plinth_library::model::{Pairing, TrackPassport};
use plinth_types::{Mbid, TrackId, VersionId};
use r9c_support::{FIRST_SCAN, Volume, run, song, tagged, track_at};

fn passport(title: &str, artist: &str, album: Option<&str>, seconds: Option<u64>) -> TrackPassport {
    TrackPassport {
        track: TrackId::new(),
        title: title.to_owned(),
        artist: artist.to_owned(),
        album: album.map(str::to_owned),
        duration: seconds.map(Duration::from_secs),
        mbid: None,
        sources: Vec::new(),
    }
}

fn mbid(text: &str) -> Mbid {
    text.parse().unwrap()
}

fn sorted(mut pairs: Vec<Pairing>) -> Vec<Pairing> {
    pairs.sort_by_key(|pair| (pair.wanted, pair.candidate));
    pairs
}

/// Находка 12 на уровне паспортов: файл со сборника идёт первым, но лучшая пара
/// — файл с альбома; он и получает кандидата.
#[test]
fn the_best_pair_goes_before_the_first_wanted() {
    let wanted = [
        passport("Bohemian Rhapsody", "Queen", Some("Greatest Hits"), Some(355)),
        passport("Bohemian Rhapsody", "Queen", Some("A Night at the Opera"), Some(355)),
    ];
    let candidates = [passport("Bohemian Rhapsody", "Queen", Some("A Night at the Opera"), Some(355))];

    let pairs = TrackPassport::pair_up(&wanted, &candidates, |_| 0);

    assert_eq!(pairs, [Pairing { wanted: 1, candidate: 0, score: 3 }]);
}

/// Сходство равное — берётся кандидат с меньшим рангом, как бы ни лежали кандидаты.
#[test]
fn of_equal_scores_the_lower_rank_wins() {
    let wanted = [passport("Song", "Band", None, Some(180))];
    let candidates = [passport("Song", "Band", None, Some(180)), passport("Song", "Band", None, Some(181))];

    let pairs = TrackPassport::pair_up(&wanted, &candidates, |index| [5, 1][index]);

    assert_eq!(pairs, [Pairing { wanted: 0, candidate: 1, score: 1 }]);
}

/// Сходство и ранг равны — кандидат достаётся тому, кто раньше в `wanted`: порядок
/// обхода (по пути) делает исход одним и тем же на любом запуске.
#[test]
fn of_equal_scores_and_ranks_the_earlier_wanted_wins() {
    let wanted = [passport("Song", "Band", None, Some(180)), passport("Song", "Band", None, Some(180))];
    let candidates = [passport("Song", "Band", None, Some(180))];

    let pairs = TrackPassport::pair_up(&wanted, &candidates, |_| 0);

    assert_eq!(pairs, [Pairing { wanted: 0, candidate: 0, score: 1 }]);
}

/// Другое название, другой исполнитель, длительность дальше двух секунд, другой
/// MBID — не пара.
#[test]
fn passports_that_do_not_match_are_not_paired() {
    let wanted = passport("Song", "Band", None, Some(180));
    let mut recording = passport("Song", "Band", None, Some(180));
    recording.mbid = Some(mbid("00000000-0000-0000-0000-00000000000a"));
    let mut other_recording = passport("Song", "Band", None, Some(180));
    other_recording.mbid = Some(mbid("00000000-0000-0000-0000-00000000000b"));
    let cases = [
        (wanted.clone(), passport("Another Song", "Band", None, Some(180))),
        (wanted.clone(), passport("Song", "Someone Else", None, Some(180))),
        (wanted, passport("Song", "Band", None, Some(190))),
        (recording, other_recording),
    ];

    for (wanted, candidate) in cases {
        assert_eq!(TrackPassport::pair_up(&[wanted], &[candidate], |_| 0), []);
    }
}

/// Каждый паспорт — в одной паре самое большее; пар столько, сколько хватило кандидатов.
#[test]
fn every_passport_is_used_at_most_once() {
    let wanted: Vec<TrackPassport> = (0..3).map(|_| passport("Song", "Band", Some("Album"), Some(180))).collect();
    let candidates: Vec<TrackPassport> = (0..2).map(|_| passport("Song", "Band", Some("Album"), Some(180))).collect();

    let pairs = sorted(TrackPassport::pair_up(&wanted, &candidates, |index| index));

    assert_eq!(
        pairs,
        [Pairing { wanted: 0, candidate: 0, score: 3 }, Pairing { wanted: 1, candidate: 1, score: 3 }],
        "кандидаты по рангу, желающие по порядку"
    );
}

/// ID трека версии — для записи, которая не должна верить ID, снятому в начале скана.
#[test]
fn the_track_of_a_version_follows_a_rekey() {
    let volume = Volume::new();
    volume.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let track = track_at(&db, &volume, "Music/Song.mp3").unwrap();
    let version = db.known_local_files().unwrap()[&volume.uri("Music/Song.mp3")].version;
    assert_eq!(db.track_of_version(version).unwrap(), Some(track));

    let journal_id = TrackId::new();
    db.rekey_track(track, journal_id).unwrap();

    assert_eq!(db.track_of_version(version).unwrap(), Some(journal_id));
    assert_eq!(db.track_of_version(VersionId::new()).unwrap(), None, "версии нет — трека нет");
}
