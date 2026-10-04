//! Вкладка «Альбомы» на большой библиотеке (ревью v0.2, находка 12): запрос
//! обложки раньше выполнялся на каждый альбом заново, и время росло как
//! квадрат числа треков — 81 с на 10 тыс. Теперь обложка берётся одним проходом.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

use std::time::{Duration, Instant};

use plinth_library::db::query::AlbumSort;
use plinth_library::db::{Database, IntegrityCheck};
use plinth_library::scan::{FoundFile, ScannedFile, Tags, write};
use plinth_types::{DeviceId, Format, Timestamp};

const TRACKS: usize = 4_000;
const ALBUMS: usize = TRACKS / 20;

#[test]
fn the_albums_tab_stays_fast_on_a_big_library() {
    let dir = std::env::temp_dir().join(format!("plinth-albums-{}", DeviceId::new()));
    std::fs::create_dir_all(&dir).unwrap();
    let db = Database::open(&dir.join("library.db"), IntegrityCheck::Skip).unwrap().db;
    let files: Vec<ScannedFile> = (0..TRACKS)
        .map(|i| ScannedFile {
            file: FoundFile {
                uri: format!("/storage/emulated/0/Music/Album {}/{i:05} Track {i}.mp3", i % ALBUMS),
                folder: format!("Music/Album {}/", i % ALBUMS),
                format: Format::Mp3,
                modified_at: Timestamp::from_millis(1_700_000_000_000 + i as i64),
                size: 4_000_000,
            },
            known: None,
            tags: Tags {
                title: Some(format!("Track {i}")),
                artist: Some(format!("Artist {}", i % (TRACKS / 100))),
                artists: vec![format!("Artist {}", i % (TRACKS / 100))],
                album: Some(format!("Album {}", i % ALBUMS)),
                track: Some(u16::try_from(i / ALBUMS).unwrap() + 1),
                ..Tags::default()
            },
            readable: true,
        })
        .collect();
    for batch in files.chunks(200) {
        db.in_transaction(|db| write(db, batch, Timestamp::from_millis(1_700_000_000_000))).unwrap();
    }

    let started = Instant::now();
    let albums = db.album_list(AlbumSort::Title).unwrap();
    let elapsed = started.elapsed();

    assert_eq!(albums.len(), ALBUMS);
    assert!(albums.iter().all(|album| album.track_count == 20));
    // Обложка — файл первого трека альбома (номер 1, он же i = N).
    let first = albums.iter().find(|album| album.title == "Album 7").unwrap();
    assert_eq!(first.cover_uri.as_deref(), Some("/storage/emulated/0/Music/Album 7/00007 Track 7.mp3"));
    assert!(elapsed < Duration::from_secs(2), "album list took {elapsed:?} on {TRACKS} tracks");
    let _ = std::fs::remove_dir_all(&dir);
}
