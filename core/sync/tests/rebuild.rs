//! C3.2: полная пересборка базы из журнала — механизм восстановления после
//! любого повреждения. DoD эпика C: «испортил базу вручную — пересобралась
//! из журнала без потерь».

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod common;

use std::fs;
use std::io::{Seek, SeekFrom, Write};

use common::{Scratch, Workload, dump, memory_db, playlist};
use plinth_library::db::{Database, IntegrityCheck};
use plinth_library::model::{PlaylistEntry, Track, position_for};
use plinth_sync::journal::{CatchUp, Journal, Op, catch_up, record_and_project};
use plinth_types::{DeviceId, PlaylistEntryId, Timestamp, TrackId};

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

    let mut file = fs::OpenOptions::new().write(true).open(dir.db()).unwrap();
    file.seek(SeekFrom::Start(0)).unwrap();
    file.write_all(&[0xde; 4096]).unwrap();
    drop(file);
    let opened = Database::open(&dir.db(), IntegrityCheck::Now).unwrap();
    let journal = Journal::open(&dir.journal(), device).unwrap();

    assert!(opened.recovery.is_some());
    assert_eq!(catch_up(&journal, &opened.db).unwrap(), CatchUp::Rebuilt);
    assert_eq!(dump(&opened.db), before);
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
