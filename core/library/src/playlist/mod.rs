//! Файлы плейлистов (D4c): M3U, M3U8 и PLS — на вход, M3U8 — на выход.
//! Строки файла находятся среди треков фонотеки по правилу автора
//! (`matching`); ненайденные пропускаются и считаются.

mod encoding;
pub mod m3u;
mod matching;

pub use m3u::{PlaylistLine, read, write};
pub use matching::{Matched, match_lines};

/// Строки треков для тестов разбора и сопоставления.
#[cfg(test)]
pub(crate) mod testing {
    use std::time::Duration;

    use plinth_types::TrackId;

    use crate::db::query::TrackRow;

    /// Трек с файлом `uri`, исполнителем, названием и длительностью.
    pub(crate) fn row(uri: Option<&str>, artist: &str, title: &str, duration_s: Option<u64>) -> TrackRow {
        TrackRow {
            id: TrackId::new(),
            title: title.to_owned(),
            artist_credit: artist.to_owned(),
            album: None,
            album_title: None,
            album_artist: None,
            disc: None,
            number: None,
            duration: duration_s.map(Duration::from_secs),
            uri: uri.map(str::to_owned),
            folder: None,
            liked: false,
            play_count: 0,
        }
    }
}
