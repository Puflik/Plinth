//! Эталон журнала выпуска для `tests/journal_corpus.rs` (F3):
//!
//! ```text
//! cargo run -p plinth-sync --example make_journal_fixture -- 0.2.0
//! ```
//!
//! Журнал — источник правды и переживает переустановку: его возвращают Auto
//! Backup и копия в папке человека (C4). Поэтому, как у базы (B3.4), эталон
//! делается **один раз на выпуск** и дальше не меняется: по нему каждая
//! следующая версия проверяет, что журнал старой установки читается. В папке
//! выпуска:
//!
//! - `journal/` — каталог журнала, каким его возвращает Auto Backup;
//! - `copy.journal` — копия журнала в папке человека.
//!
//! Внутри — лайк и прослушивание «Кукушки», плейлист «Дорога» (Queen, потом
//! Кино) и лайк сетевого трека Internet Archive «OH DOCTOR».

use std::path::PathBuf;
use std::time::Duration;

use plinth_library::db::Database;
use plinth_library::model::{
    AudioSpec, OnlineSource, OnlineTrack, OutputDevice, PlayEvent, Playlist, PlaylistEntry, PlaylistKind, position_for,
};
use plinth_library::scan::{ArtistSplit, FoundFile, RawTags, ScannedFile, normalized, write};
use plinth_sync::journal::{Journal, Op, record_and_project};
use plinth_sync::mirror::copy_of;
use plinth_types::{
    Bitrate, DeviceId, Format, PlayEventId, PlaylistEntryId, PlaylistId, ProviderId, Timestamp, TrackId,
};

/// Время копии и событий: эталон не зависит от дня, когда его сделали.
const AT: i64 = 1_790_500_000_000;

/// Файл, как его прочёл бы скан: путь, название, исполнитель, альбом, длительность.
const SONGS: [(&str, &str, &str, &str, u64); 2] = [
    ("/storage/emulated/0/Music/Кино/Кукушка.mp3", "Кукушка", "Кино", "Звезда по имени Солнце", 398_000),
    (
        "/storage/emulated/0/Music/Queen/Bohemian Rhapsody.mp3",
        "Bohemian Rhapsody",
        "Queen",
        "A Night at the Opera",
        354_000,
    ),
];

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let release = std::env::args().nth(1).ok_or("usage: make_journal_fixture <release>, e.g. 0.2.0")?;
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/journal").join(&release);
    if dir.exists() {
        return Err(format!("{} exists: a fixture of a release never changes", dir.display()).into());
    }

    let db = Database::open_in_memory()?;
    scan(&db)?;
    let files = db.known_local_files()?;
    let (kino, queen) = (files[SONGS[0].0].track, files[SONGS[1].0].track);
    let online = db.add_online_track(&oh_doctor()?, Timestamp::from_millis(AT))?;

    let mut journal = Journal::open(&dir.join("journal"), DeviceId::new())?;
    let road = Playlist {
        id: PlaylistId::new(),
        name: "Дорога".to_owned(),
        kind: PlaylistKind::Manual,
        created_at: Timestamp::from_millis(AT),
    };
    for op in [Op::Like { track: kino }, Op::CreatePlaylist(road.clone())] {
        record_and_project(&mut journal, &db, &op)?;
    }
    for track in [queen, kino] {
        let entries = db.entries(road.id)?;
        let op = Op::AddEntry(entry(&road, track, position_for(&entries, entries.len(), None)));
        record_and_project(&mut journal, &db, &op)?;
    }
    for op in [Op::Play(play(kino)), Op::Like { track: online }] {
        record_and_project(&mut journal, &db, &op)?;
    }

    std::fs::write(dir.join("copy.journal"), copy_of(&journal, Timestamp::from_millis(AT)))?;
    println!("{} written", dir.display());
    Ok(())
}

/// Кладёт `SONGS` в каталог тем же путём, что скан.
fn scan(db: &Database) -> Result<(), Box<dyn std::error::Error>> {
    let now = Timestamp::from_millis(AT);
    let split = ArtistSplit::default();
    let files: Vec<ScannedFile> = SONGS
        .iter()
        .map(|&(path, title, artist, album, duration_ms)| {
            let raw = RawTags {
                title: Some(title.to_owned()),
                artist: vec![artist.to_owned()],
                album: Some(album.to_owned()),
                duration: Some(Duration::from_millis(duration_ms)),
                ..RawTags::default()
            };
            ScannedFile {
                file: FoundFile {
                    uri: path.to_owned(),
                    folder: "Music/".to_owned(),
                    format: Format::Mp3,
                    modified_at: now,
                    size: 0,
                },
                known: None,
                tags: normalized(raw, &split),
                readable: true,
            }
        })
        .collect();
    db.in_transaction(|db| write(db, &files, now))?;
    Ok(())
}

fn entry(playlist: &Playlist, track: TrackId, position: plinth_types::Position) -> PlaylistEntry {
    PlaylistEntry {
        id: PlaylistEntryId::new(),
        playlist: playlist.id,
        track,
        position,
        added_at: Timestamp::from_millis(AT),
    }
}

fn play(track: TrackId) -> PlayEvent {
    PlayEvent {
        id: PlayEventId::new(),
        track,
        version: None,
        source: None,
        started_at: Timestamp::from_millis(AT),
        utc_offset_minutes: 180,
        listened: Duration::from_secs(300),
        track_length: Some(Duration::from_secs(398)),
        skipped_at: None,
        output: OutputDevice::Headphones,
        previous_track: None,
    }
}

fn oh_doctor() -> Result<OnlineTrack, Box<dyn std::error::Error>> {
    let provider = ProviderId::new("archive.org")?;
    let source = |external: &str, format, kbps: Option<u32>| OnlineSource {
        provider: provider.clone(),
        external_id: external.to_owned(),
        audio: AudioSpec { format, bitrate: kbps.map(Bitrate::kbps), sample_rate_hz: None, bit_depth: None },
    };
    Ok(OnlineTrack {
        title: "OH DOCTOR".to_owned(),
        artist: Some("H. Pearl".to_owned()),
        album: Some("OH DOCTOR".to_owned()),
        number: Some(1),
        year: None,
        duration: Some(Duration::from_millis(127_450)),
        mbid: None,
        sources: vec![source("78_oh/a.flac", Format::Flac, None), source("78_oh/a.mp3", Format::Mp3, Some(233))],
    })
}
