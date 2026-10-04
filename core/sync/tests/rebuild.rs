//! C3.2: полная пересборка базы из журнала — механизм восстановления после
//! любого повреждения. DoD эпика C: «испортил базу вручную — пересобралась
//! из журнала без потерь».

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod common;

use std::fs;
use std::io::{Seek, SeekFrom, Write};

use common::{Scratch, Song, Workload, dump, memory_db, play, playlist, scan, track_at};
use plinth_library::db::{Database, IntegrityCheck};
use plinth_library::model::{PlaylistEntry, Track, position_for};
use plinth_sync::journal::{CatchUp, Journal, Op, catch_up, record_and_project, relink};
use plinth_types::{DeviceId, PlaylistEntryId, Timestamp, TrackId};

const PIANO: Song = Song {
    path: "/storage/emulated/0/Music/Reich/Piano Phase.flac",
    title: "Piano Phase",
    artist: "Steve Reich",
    album: Some("Early Works"),
    duration_ms: Some(1_311_000),
};

const DREAMS: Song = Song {
    path: "/storage/emulated/0/Music/MIXG031/In My Dreams.mp3",
    title: "In My Dreams",
    artist: "MIXG031",
    album: None,
    duration_ms: Some(214_000),
};

fn filled(dir: &Scratch, device: DeviceId, db: &Database) -> Journal {
    let mut journal = Journal::open(&dir.journal(), device).unwrap();
    let mut workload = Workload::new(99);
    for _ in 0..150 {
        let op = workload.op(&journal);
        record_and_project(&mut journal, db, &op).unwrap();
    }
    journal
}

fn remove_db(dir: &Scratch) {
    for suffix in ["", "-wal", "-shm"] {
        let _ = fs::remove_file(format!("{}{suffix}", dir.db().display()));
    }
}

/// Затирает заголовок файла базы — как правка в hex-редакторе.
fn corrupt_db(dir: &Scratch) {
    let mut file = fs::OpenOptions::new().write(true).open(dir.db()).unwrap();
    file.seek(SeekFrom::Start(0)).unwrap();
    file.write_all(&[0xde; 4096]).unwrap();
}

#[test]
fn a_deleted_database_comes_back_from_the_journal() {
    let dir = Scratch::new();
    let device = DeviceId::new();
    let db = Database::open(&dir.db(), IntegrityCheck::Skip).unwrap().db;
    drop(filled(&dir, device, &db));
    let before = dump(&db);
    drop(db);

    remove_db(&dir);
    let journal = Journal::open(&dir.journal(), device).unwrap();
    let db = Database::open(&dir.db(), IntegrityCheck::Skip).unwrap().db;

    assert_eq!(catch_up(&journal, &db).unwrap(), CatchUp::Rebuilt);
    assert_eq!(dump(&db), before);
}

/// DoD эпика C: испорченная база уходит в карантин (B2.3), новая
/// пересобирается из журнала без потерь.
#[test]
fn a_corrupted_database_is_rebuilt_without_loss() {
    let dir = Scratch::new();
    let device = DeviceId::new();
    let db = Database::open(&dir.db(), IntegrityCheck::Skip).unwrap().db;
    drop(filled(&dir, device, &db));
    let before = dump(&db);
    drop(db);

    corrupt_db(&dir);
    let opened = Database::open(&dir.db(), IntegrityCheck::Now).unwrap();
    let journal = Journal::open(&dir.journal(), device).unwrap();

    assert!(opened.recovery.is_some());
    assert_eq!(catch_up(&journal, &opened.db).unwrap(), CatchUp::Rebuilt);
    assert_eq!(dump(&opened.db), before);
}

/// DoD F2 глазами человека (v0.2): вместе с базой пропал и каталог, скан
/// выдал файлам новые ID — лайк, плейлист и история снова на своих треках
/// (перепривязка по паспортам, C4), а не висят на старых ID.
#[test]
fn a_corrupted_library_is_back_on_its_tracks_after_the_rescan() {
    let dir = Scratch::new();
    let device = DeviceId::new();
    let db = Database::open(&dir.db(), IntegrityCheck::Skip).unwrap().db;
    scan(&db, &[PIANO, DREAMS]);
    let (piano, dreams) = (track_at(&db, PIANO), track_at(&db, DREAMS));
    let mut journal = Journal::open(&dir.journal(), device).unwrap();
    let night = playlist("Night");
    let entry = PlaylistEntry {
        id: PlaylistEntryId::new(),
        playlist: night.id,
        track: dreams,
        position: position_for(&[], 0, None),
        added_at: Timestamp::now(),
    };
    for op in [
        Op::Like { track: piano },
        Op::CreatePlaylist(night.clone()),
        Op::AddEntry(entry),
        Op::Play(play(piano, 1_790_000_000_000, 1_200, Some(1_311))),
    ] {
        record_and_project(&mut journal, &db, &op).unwrap();
    }
    drop((journal, db));

    corrupt_db(&dir);
    let db = Database::open(&dir.db(), IntegrityCheck::Now).unwrap().db;
    let mut journal = Journal::open(&dir.journal(), device).unwrap();
    assert_eq!(catch_up(&journal, &db).unwrap(), CatchUp::Rebuilt);
    scan(&db, &[DREAMS, PIANO]);
    relink(&mut journal, &db).unwrap();

    assert_eq!((track_at(&db, PIANO), track_at(&db, DREAMS)), (piano, dreams));
    let liked: Vec<String> = db.liked_tracks().unwrap().into_iter().map(|row| row.title).collect();
    assert_eq!(liked, ["Piano Phase"]);
    let entries: Vec<TrackId> = db.entries(night.id).unwrap().into_iter().map(|e| e.track).collect();
    assert_eq!(entries, [dreams]);
    assert_eq!(db.user_data(piano).unwrap().play_count, 1);
}

/// Пересборка трогает только отражение журнала: каталог пересобирает скан.
#[test]
fn rebuilding_keeps_the_catalog() {
    let dir = Scratch::new();
    let db = memory_db();
    let track = Track {
        id: TrackId::new(),
        title: "Creep".to_owned(),
        artist_credit: "Radiohead".to_owned(),
        sort_artist_credit: None,
        artists: Vec::new(),
        mbid_work: None,
        added_at: Timestamp::from_millis(1),
    };
    db.save_track(&track).unwrap();
    let journal = filled(&dir, DeviceId::new(), &db);

    plinth_sync::journal::rebuild(&journal, &db).unwrap();

    assert_eq!(db.track(track.id).unwrap(), Some(track));
}

/// Два устройства: одно удалило плейлист, другое параллельно добавило в
/// него трек. У записи нет плейлиста — пересборка её пропускает, а не падает
/// на внешнем ключе.
#[test]
fn an_entry_of_a_deleted_playlist_is_skipped() {
    let (a, b) = (Scratch::new(), Scratch::new());
    let mut phone = Journal::open(&a.journal(), DeviceId::new()).unwrap();
    let mix = playlist("Mix");
    phone.record(&Op::CreatePlaylist(mix.clone())).unwrap();
    let mut tablet = Journal::open(&b.journal(), DeviceId::new()).unwrap();
    tablet.merge(&phone.updates_since(&tablet.state_vector()).unwrap()).unwrap();

    phone.record(&Op::DeletePlaylist { playlist: mix.id }).unwrap();
    let orphan = PlaylistEntry {
        id: PlaylistEntryId::new(),
        playlist: mix.id,
        track: TrackId::new(),
        position: position_for(&[], 0, None),
        added_at: Timestamp::now(),
    };
    tablet.record(&Op::AddEntry(orphan)).unwrap();
    phone.merge(&tablet.updates_since(&phone.state_vector()).unwrap()).unwrap();
    let db = memory_db();

    assert_eq!(catch_up(&phone, &db).unwrap(), CatchUp::Rebuilt);
    assert!(db.playlists().unwrap().is_empty());
    assert_eq!(phone.state().entries.len(), 1);
}
