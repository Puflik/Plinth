//! Р9в, находка 2 ревью R1 (`docs/review/r1-review.md`, `docs/work/r9c.md`),
//! сквозной сценарий (а): альбом переносят на SD-карту обычным способом — копия,
//! потом удаление оригинала, и между шагами человек открывал приложение. Скан
//! между шагами заводит копию новым треком; после удаления скан и перепривязка
//! (как их делает `Core::scan`) должны оставить лайк, оценку, плейлист и
//! историю на песне, а не на скрытом треке: «Любимое» не пустеет, в плейлисте
//! нет серых строк.
//!
//! Скан здесь настоящий, файлы настоящие; журнал скан и перепривязка в этом
//! сценарии не пишут.

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

/// Теги по имени файла.
struct Tagged;

impl TagReader for Tagged {
    fn read(&self, path: &Path) -> Result<Tags, CoreError> {
        let name = path.file_name().unwrap().to_string_lossy();
        if name != "Song.mp3" {
            return Err(CoreError::parse(format!("the test gives no tags for {name}")));
        }
        let raw = RawTags {
            title: Some("Song".to_owned()),
            artist: vec!["Band".to_owned()],
            album: Some("Album".to_owned()),
            duration: Some(Duration::from_secs(180)),
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

fn copy(volume: &Scratch, from: &str, to: &str) {
    let target = path(volume, to);
    fs::create_dir_all(target.parent().unwrap()).unwrap();
    fs::copy(path(volume, from), target).unwrap();
}

/// Скан, как его зовёт `Core::scan`, без перепривязки: её тест зовёт сам.
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

/// Сценарий ревью: лайкнутая песня с оценкой, местом в плейлисте и
/// прослушиванием; копия на другом месте, скан и перепривязка; оригинал удалён,
/// скан и перепривязка. Всё на песне под прежним ID, из файла копии.
#[test]
fn a_copy_then_a_delete_keeps_likes_ratings_playlists_and_history() {
    let (volume, dir) = (Scratch::new(), Scratch::new());
    put(&volume, "Music/Song.mp3");
    let db = memory_db();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    run(&db, &volume);
    let song = track_at(&db, &volume, "Music/Song.mp3").unwrap();
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
    let before = journal.mark();

    copy(&volume, "Music/Song.mp3", "Download/Song.mp3");
    run(&db, &volume);
    relink(&mut journal, &db).unwrap();
    let copy = track_at(&db, &volume, "Download/Song.mp3").unwrap();
    assert_ne!(copy, song, "пока оригинал на месте, копия — новый трек");

    fs::remove_file(path(&volume, "Music/Song.mp3")).unwrap();
    run(&db, &volume);
    assert_eq!(relink(&mut journal, &db).unwrap(), 0, "перепривязке нечего узнавать: трек оригинала в каталоге");
    catch_up(&journal, &db).unwrap();

    assert_eq!(liked_titles(&db), ["Song"], "«Любимое» после переноса");
    assert_eq!(track_at(&db, &volume, "Download/Song.mp3"), Some(song), "файл копии — прежний трек");
    let user = db.user_data(song).unwrap();
    assert_eq!((user.rating, user.play_count), (Some(Rating::new(4).unwrap()), 1));
    assert_eq!(playlist_tracks(&db, &road), [song], "трек виден в плейлисте");
    let recent: Vec<TrackId> = db.recent_tracks(10).unwrap().into_iter().map(|row| row.id).collect();
    assert_eq!(recent, [song], "«Недавнее»");
    assert_eq!(db.track(copy).unwrap(), None, "трек копии, у которого данных не было, ушёл из каталога");
    assert_eq!(journal.mark().seq, before.seq, "ни скан, ни перепривязка в журнал не писали");
    assert_eq!(relink(&mut journal, &db).unwrap(), 0);
}
