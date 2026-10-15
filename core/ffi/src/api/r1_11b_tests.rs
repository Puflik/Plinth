//! Р1.11б (`docs/work/r1-11b.md`, находка 22 ревью v0.2): настоящий
//! `Core::import_playlist` на файле в десять тысяч строк. Раньше записи
//! получали позиции цепочкой `Position::after`, и они росли с номером строки
//! (для 60 тысяч — до 948 символов, 28,5 МБ журнала); теперь позиции короткие,
//! а порядок записей — порядок строк файла, и он переживает перезапуск.

use std::collections::HashMap;

use plinth_library::db::query::TrackSort;
use plinth_types::TrackId;

use super::test_api::TestFile;
use crate::session::Core;
use crate::testing::Scratch;

const TRACKS: usize = 10_000;

fn path(n: usize) -> String {
    format!("/storage/emulated/0/Music/Album/{n:05}.mp3")
}

fn file(n: usize) -> TestFile {
    TestFile {
        uri: path(n),
        folder: "Music/".to_owned(),
        title: Some(format!("Song {n}")),
        artist: Some("Artist".to_owned()),
        album: None,
        album_artist: None,
        disc: None,
        number: None,
        duration_ms: None,
    }
}

/// Строка файла `line` — трек `(line * 7919) % TRACKS`: порядок строк не совпадает ни с каким порядком каталога.
fn shuffled(line: usize) -> usize {
    line * 7919 % TRACKS
}

#[test]
fn a_huge_playlist_file_gets_short_positions_and_keeps_its_order() {
    let dir = Scratch::new();
    let core = Core::open(dir.path()).unwrap();
    core.seed_for_test((0..TRACKS).map(file).collect()).unwrap();
    let mut text = String::from("#EXTM3U\n");
    for line in 0..TRACKS {
        text.push_str(&path(shuffled(line)));
        text.push('\n');
    }

    let report = core.import_playlist("Big".to_owned(), text.into_bytes(), None).unwrap();

    assert_eq!((report.added as usize, report.not_found), (TRACKS, 0));
    let playlist = report.playlist.unwrap();
    let by_path: HashMap<String, TrackId> = core
        .with(|state| state.db.track_list(TrackSort::Title, None))
        .unwrap()
        .into_iter()
        .filter_map(|row| row.uri.map(|uri| (uri, row.id)))
        .collect();
    let expected: Vec<TrackId> = (0..TRACKS).map(|line| by_path[&path(shuffled(line))]).collect();
    let in_order = |core: &Core| -> Vec<TrackId> {
        core.playlist_items(playlist.id).unwrap().into_iter().map(|item| item.track).collect()
    };
    assert_eq!(in_order(&core), expected, "порядок строк файла");

    let lengths: Vec<usize> = core
        .with(|state| state.db.entries(playlist.id))
        .unwrap()
        .iter()
        .map(|entry| entry.position.to_string().len())
        .collect();
    let longest = lengths.iter().copied().max().unwrap();
    assert!(longest <= 48, "самая длинная позиция — {longest} символов (цепочка `after` даёт 160)");
    assert!(
        lengths.iter().sum::<usize>() <= TRACKS * 24,
        "позиции вместе — {} символов",
        lengths.iter().sum::<usize>()
    );

    drop(core);
    let again = Core::open(dir.path()).unwrap();
    assert_eq!(in_order(&again), expected, "после перезапуска из журнала");
}
