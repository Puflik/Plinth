//! Тестовые данные репозиториев: «Radiohead — Creep» с альбомом, версией и файлом.

use std::time::Duration;

use plinth_types::{AlbumId, ArtistId, Availability, Bitrate, Format, Mbid, SourceId, Timestamp, TrackId, VersionId};

use crate::db::Database;
use crate::model::{
    Album, AlbumPlacement, Artist, AudioSpec, Explicitness, Source, SourceLocation, Track, Version, VersionKind,
};

pub(crate) fn db() -> Database {
    Database::open_in_memory().unwrap()
}

pub(crate) fn artist(name: &str) -> Artist {
    Artist {
        id: ArtistId::new(),
        name: name.to_owned(),
        sort_name: None,
        mbid: Some("a74b1b7f-71a5-4011-9441-d0b5e4122711".parse::<Mbid>().unwrap()),
        aliases: vec!["Радиохед".to_owned()],
        bio: None,
    }
}

pub(crate) fn album(artist: &Artist) -> Album {
    Album {
        id: AlbumId::new(),
        title: "Pablo Honey".to_owned(),
        artist_credit: artist.name.clone(),
        sort_artist_credit: None,
        artists: vec![artist.id],
        year: Some(1993),
        label: Some("Parlophone".to_owned()),
        country: Some("GB".to_owned()),
        disc_count: Some(1),
        mbid_release: None,
    }
}

pub(crate) fn track(title: &str, artist: &Artist, added_at: i64) -> Track {
    Track {
        id: TrackId::new(),
        title: title.to_owned(),
        artist_credit: artist.name.clone(),
        sort_artist_credit: None,
        artists: vec![artist.id],
        mbid_work: None,
        added_at: Timestamp::from_millis(added_at),
    }
}

pub(crate) fn version(track: &Track, album: Option<&Album>, number: u16) -> Version {
    Version {
        id: VersionId::new(),
        track: track.id,
        kind: VersionKind::Original,
        explicitness: Explicitness::Explicit,
        duration: Some(Duration::from_millis(238_640)),
        album: album.map(|a| AlbumPlacement { album: a.id, disc: Some(1), number: Some(number) }),
        release_year: Some(1993),
        mbid_recording: None,
        fingerprint: None,
    }
}

pub(crate) fn local_source(version: &Version, uri: &str) -> Source {
    Source {
        id: SourceId::new(),
        version: version.id,
        location: SourceLocation::Local { uri: uri.to_owned() },
        audio: AudioSpec {
            format: Format::Flac,
            bitrate: Some(Bitrate::kbps(900)),
            sample_rate_hz: Some(44_100),
            bit_depth: Some(16),
        },
        availability: Availability::Available,
        last_checked_at: None,
    }
}

/// Артист, альбом, трек с версией и локальным файлом — всё сохранено.
pub(crate) fn creep(db: &Database) -> (Artist, Album, Track, Version, Source) {
    let artist = artist("Radiohead");
    let album = album(&artist);
    let track = track("Creep", &artist, 1_000);
    let version = version(&track, Some(&album), 2);
    let source = local_source(&version, "content://media/external/audio/media/42");
    db.save_artist(&artist).unwrap();
    db.save_album(&album).unwrap();
    db.save_track(&track).unwrap();
    db.save_version(&version).unwrap();
    db.save_source(&source).unwrap();
    (artist, album, track, version, source)
}
