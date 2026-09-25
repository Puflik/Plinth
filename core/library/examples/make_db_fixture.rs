//! Эталонная база текущей версии схемы для `tests/migrations.rs` (B3.4):
//!
//! ```text
//! cargo run -p plinth-library --example make_db_fixture
//! ```
//!
//! Эталон делается **один раз на выпущенную версию схемы** и дальше не
//! меняется: по нему каждая следующая версия проверяет, что старая база
//! пользователя открывается и мигрирует (ADR 0011).

use std::path::PathBuf;
use std::time::Duration;

use plinth_library::db::{Database, IntegrityCheck};
use plinth_library::model::{
    Album, AlbumPlacement, Artist, AudioSpec, Explicitness, Playlist, PlaylistEntry, PlaylistKind, Rating, Source,
    SourceLocation, Track, TrackUserData, Version, VersionKind, position_for,
};
use plinth_types::{
    AlbumId, ArtistId, Availability, Bitrate, Format, PlaylistEntryId, PlaylistId, SourceId, Timestamp, TrackId,
    VersionId,
};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/db");
    std::fs::create_dir_all(&dir)?;
    let scratch = dir.join("fixture.tmp.db");
    let _ = std::fs::remove_file(&scratch);
    let db = Database::open(&scratch, IntegrityCheck::Skip)?.db;
    let version = db.schema_version()?;

    let artist = Artist {
        id: ArtistId::new(),
        name: "Radiohead".to_owned(),
        sort_name: None,
        mbid: None,
        aliases: vec!["Радиохед".to_owned()],
        bio: None,
    };
    db.save_artist(&artist)?;
    let album = Album {
        id: AlbumId::new(),
        title: "Pablo Honey".to_owned(),
        artist_credit: "Radiohead".to_owned(),
        artists: vec![artist.id],
        year: Some(1993),
        label: None,
        country: Some("GB".to_owned()),
        disc_count: Some(1),
        mbid_release: None,
    };
    db.save_album(&album)?;

    let playlist = Playlist {
        id: PlaylistId::new(),
        name: "Любимое".to_owned(),
        kind: PlaylistKind::Manual,
        created_at: Timestamp::from_millis(1_790_000_000_000),
    };
    db.save_playlist(&playlist)?;

    for (number, title) in (1_u16..).zip(["You", "Creep", "How Do You?"]) {
        let track = Track {
            id: TrackId::new(),
            title: title.to_owned(),
            artist_credit: "Radiohead".to_owned(),
            artists: vec![artist.id],
            mbid_work: None,
            added_at: Timestamp::from_millis(1_790_000_000_000 + i64::from(number)),
        };
        db.save_track(&track)?;
        let recording = Version {
            id: VersionId::new(),
            track: track.id,
            kind: VersionKind::Original,
            explicitness: Explicitness::Unknown,
            duration: Some(Duration::from_secs(200 + u64::from(number))),
            album: Some(AlbumPlacement { album: album.id, disc: Some(1), number: Some(number) }),
            release_year: Some(1993),
            mbid_recording: None,
            fingerprint: None,
        };
        db.save_version(&recording)?;
        let file = SourceId::new();
        db.save_source(&Source {
            id: file,
            version: recording.id,
            location: SourceLocation::Local { uri: format!("content://media/external/audio/media/{number}") },
            audio: AudioSpec {
                format: Format::Flac,
                bitrate: Some(Bitrate::kbps(900)),
                sample_rate_hz: Some(44_100),
                bit_depth: Some(16),
            },
            availability: Availability::Available,
            last_checked_at: None,
        })?;
        // Учёт сканера (схема v2): каким файл был при последнем скане.
        db.save_file_stamp(file, Timestamp::from_millis(1_758_000_000_000), 4_096 * u64::from(number), "Music/")?;
        db.save_user_data(&TrackUserData {
            track: track.id,
            liked: number == 2,
            rating: Rating::new(3).ok(),
            play_count: u32::from(number) * 10,
            last_played_at: None,
        })?;
        let entries = db.entries(playlist.id)?;
        db.put_entry(&PlaylistEntry {
            id: PlaylistEntryId::new(),
            playlist: playlist.id,
            track: track.id,
            position: position_for(&entries, entries.len(), None),
            added_at: Timestamp::from_millis(1_790_000_000_100),
        })?;
    }
    drop(db);

    let target = dir.join(format!("v{version}.db"));
    std::fs::rename(&scratch, &target)?;
    println!("{}", target.display());
    Ok(())
}
