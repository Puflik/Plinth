//! Р1.2 (находка 2 ревью v0.2): перенесённый файл не теряет
//! пользовательского. Сценарий ревью — скан, действия человека, перенос,
//! скан и перепривязка, как их делает `Core::scan`, — с оценкой, плейлистом и
//! прослушиванием, а не только лайком (урок Р1.1); карта с другим именем
//! тома; переустановка после переноса.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use common::{Scratch, memory_db, play, playlist};
use plinth_library::db::Database;
use plinth_library::model::{Playlist, PlaylistEntry, Rating};
use plinth_library::scan::{ArtistSplit, FolderConfig, RawTags, TagReader, Tags, normalized, scan};
use plinth_sync::journal::{Journal, Op, catch_up, record_and_project, relink};
use plinth_types::{CoreError, DeviceId, PlaylistEntryId, Position, Timestamp, TrackId};

/// Теги по имени файла: «Song» и «Other» одной группы с одного альбома.
struct Tagged;

impl TagReader for Tagged {
    fn read(&self, path: &Path) -> Result<Tags, CoreError> {
        let (title, seconds) = match path.file_name().unwrap().to_string_lossy().as_ref() {
            "Song.mp3" => ("Song", 180),
            "Other.mp3" => ("Other", 200),
            name => return Err(CoreError::parse(format!("the test gives no tags for {name}"))),
        };
        let raw = RawTags {
            title: Some(title.to_owned()),
            artist: vec!["Band".to_owned()],
            album: Some("Album".to_owned()),
            duration: Some(Duration::from_secs(seconds)),
            ..RawTags::default()
        };
        Ok(normalized(raw, &ArtistSplit::default()))
    }
}

fn path(volume: &Scratch, file: &str) -> PathBuf {
    file.split('/').fold(volume.0.clone(), |dir, segment| dir.join(segment))
}

fn put(volume: &Scratch, file: &str) {
    let path = path(volume, file);
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(path, b"audio").unwrap();
}

fn shift(volume: &Scratch, from: &str, to: &str) {
    let target = path(volume, to);
    fs::create_dir_all(target.parent().unwrap()).unwrap();
    fs::rename(path(volume, from), target).unwrap();
}

/// Скан, как его зовёт `Core::scan`, без перепривязки: её тесты зовут сами.
fn run(db: &Database, volume: &Scratch) {
    scan(db, std::slice::from_ref(&volume.0), &FolderConfig::default(), &Tagged, &mut |_| true, Timestamp::now())
        .unwrap();
}

fn track_at(db: &Database, volume: &Scratch, file: &str) -> Option<TrackId> {
    db.track_at(&path(volume, file).to_string_lossy()).unwrap()
}

fn liked_titles(db: &Database) -> Vec<String> {
    db.liked_tracks().unwrap().into_iter().map(|row| row.title).collect()
}

fn entry(playlist: &Playlist, track: TrackId) -> PlaylistEntry {
    PlaylistEntry {
        id: PlaylistEntryId::new(),
        playlist: playlist.id,
        track,
        position: Position::first(),
        added_at: Timestamp::now(),
    }
}

fn playlist_tracks(db: &Database, playlist: &Playlist) -> Vec<TrackId> {
    db.playlist_tracks(playlist.id).unwrap().into_iter().map(|row| row.track.id).collect()
}

/// Сценарий ревью: трек в «Download» с лайком, оценкой, местом в плейлисте и
/// прослушиванием перенесли в «Music». После скана и перепривязки всё это на
/// нём: «Любимое», «Недавнее», плейлист, оценка и счётчик. Журнал перенос не
/// трогает — ни одной новой записи.
#[test]
fn a_moved_file_keeps_its_likes_rating_playlists_and_history() {
    let (volume, dir) = (Scratch::new(), Scratch::new());
    put(&volume, "Download/Song.mp3");
    let db = memory_db();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    run(&db, &volume);
    let song = track_at(&db, &volume, "Download/Song.mp3").unwrap();
    let road = playlist("Road");
    for op in [
        Op::Like { track: song },
        Op::Rate { track: song, rating: Some(Rating::new(4).unwrap()) },
        Op::CreatePlaylist(road.clone()),
        Op::AddEntry(entry(&road, song)),
        Op::Play(play(song, 1_790_000_000_000, 150, Some(180))),
    ] {
        record_and_project(&mut journal, &db, &op).unwrap();
    }
    assert_eq!(liked_titles(&db), ["Song"]);
    let before = journal.mark();

    shift(&volume, "Download/Song.mp3", "Music/Song.mp3");
    run(&db, &volume);
    relink(&mut journal, &db).unwrap();
    catch_up(&journal, &db).unwrap();

    assert_eq!(liked_titles(&db), ["Song"], "«Любимое» после переноса");
    assert_eq!(track_at(&db, &volume, "Music/Song.mp3"), Some(song), "новый путь — прежний трек");
    let user = db.user_data(song).unwrap();
    assert_eq!((user.rating, user.play_count), (Some(Rating::new(4).unwrap()), 1));
    assert_eq!(playlist_tracks(&db, &road), [song], "трек виден в плейлисте");
    let recent: Vec<TrackId> = db.recent_tracks(10).unwrap().into_iter().map(|row| row.id).collect();
    assert_eq!(recent, [song], "«Недавнее»");
    assert_eq!(journal.mark().seq, before.seq, "перенос файла в журнал не пишет");
    assert_eq!(relink(&mut journal, &db).unwrap(), 0);
}

/// Карту заменили или переформатировали: у тома другое имя, пути всех
/// файлов другие, Kotlin отдаёт скану только новый корень. Лайк, плейлист и
/// история всех треков на месте.
#[test]
fn a_card_with_a_new_volume_name_keeps_the_data_of_every_track() {
    let (old_card, new_card, dir) = (Scratch::new(), Scratch::new(), Scratch::new());
    for file in ["Music/Song.mp3", "Music/Other.mp3"] {
        put(&old_card, file);
        put(&new_card, file);
    }
    let db = memory_db();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    run(&db, &old_card);
    let (song, other) =
        (track_at(&db, &old_card, "Music/Song.mp3").unwrap(), track_at(&db, &old_card, "Music/Other.mp3").unwrap());
    let road = playlist("Road");
    for op in [
        Op::Like { track: song },
        Op::CreatePlaylist(road.clone()),
        Op::AddEntry(entry(&road, other)),
        Op::Play(play(other, 1_790_000_000_000, 200, Some(200))),
    ] {
        record_and_project(&mut journal, &db, &op).unwrap();
    }

    run(&db, &new_card);
    relink(&mut journal, &db).unwrap();

    assert_eq!(liked_titles(&db), ["Song"]);
    assert_eq!(playlist_tracks(&db, &road), [other]);
    assert_eq!(track_at(&db, &new_card, "Music/Song.mp3"), Some(song));
    assert_eq!(track_at(&db, &new_card, "Music/Other.mp3"), Some(other));
    assert_eq!(db.user_data(other).unwrap().play_count, 1);
}

/// Страж: после переноса переустановка по-прежнему находит трек — паспорт в
/// журнале описывает песню, а не путь.
#[test]
fn after_a_move_a_reinstall_still_finds_the_track() {
    let (volume, old_dir) = (Scratch::new(), Scratch::new());
    put(&volume, "Download/Song.mp3");
    let old_db = memory_db();
    let mut old_journal = Journal::open(&old_dir.journal(), DeviceId::new()).unwrap();
    run(&old_db, &volume);
    let song = track_at(&old_db, &volume, "Download/Song.mp3").unwrap();
    record_and_project(&mut old_journal, &old_db, &Op::Like { track: song }).unwrap();
    shift(&volume, "Download/Song.mp3", "Music/Song.mp3");
    run(&old_db, &volume);
    relink(&mut old_journal, &old_db).unwrap();

    let new_dir = Scratch::new();
    let db = memory_db();
    let mut journal = Journal::open(&new_dir.journal(), DeviceId::new()).unwrap();
    run(&db, &volume);
    assert_ne!(track_at(&db, &volume, "Music/Song.mp3"), Some(song), "новая установка выдала файлу новый ID");
    journal.merge(&old_journal.updates_since(&journal.state_vector()).unwrap()).unwrap();
    catch_up(&journal, &db).unwrap();
    relink(&mut journal, &db).unwrap();

    assert_eq!(track_at(&db, &volume, "Music/Song.mp3"), Some(song));
    assert_eq!(liked_titles(&db), ["Song"]);
}
