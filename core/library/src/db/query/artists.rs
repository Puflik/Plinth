//! Исполнители для экранов (D3): список со счётчиками и треки исполнителя.
//!
//! Исполнитель — артист каталога, связанный с треком (`track_artist`): у
//! «Daft Punk feat. Pharrell» их двое. Экран открывает исполнителя по имени.

use plinth_types::{ArtistId, CoreError};
use rusqlite::Row;

use super::{BY_ALBUM, ON_ALBUM, PLAYABLE, TrackRow};
use crate::db::Database;
use crate::db::sql::{Storage, id};
use crate::text::normalize;

/// Строка списка исполнителей.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArtistRow {
    pub id: ArtistId,
    pub name: String,
    /// Сколько разных альбомов среди его видимых треков, сборники тоже.
    pub album_count: u32,
    pub track_count: u32,
}

impl Database {
    /// Исполнители видимых треков по имени, без артикля, в естественном порядке.
    pub fn artist_list(&self) -> Result<Vec<ArtistRow>, CoreError> {
        let sql = format!(
            "WITH visible AS (SELECT t.id AS track, v.album AS album {PLAYABLE})
             SELECT ar.id, ar.name, count(DISTINCT w.album) AS album_count, count(*) AS track_count
             FROM artist ar
             JOIN track_artist ta ON ta.artist = ar.id
             JOIN visible w ON w.track = ta.track
             GROUP BY ar.id
             ORDER BY ar.name_sort, ar.id"
        );
        let mut statement = self.conn().prepare(&sql).storage()?;
        statement.query_map([], read_artist_row).storage()?.collect::<Result<_, _>>().storage()
    }

    /// Треки исполнителя `name`: альбомы по названию, внутри — диск и номер;
    /// треки без альбома — в конце.
    pub fn artist_tracks(&self, name: &str) -> Result<Vec<TrackRow>, CoreError> {
        let filter = "WHERE t.id IN (SELECT ta.track FROM track_artist ta
                                     JOIN artist ar ON ar.id = ta.artist
                                     WHERE ar.name_normalized = ?1)";
        let order = format!("{BY_ALBUM}, {ON_ALBUM}");
        self.track_rows(filter, [normalize(name)], &order)
    }
}

fn read_artist_row(row: &Row<'_>) -> rusqlite::Result<ArtistRow> {
    Ok(ArtistRow {
        id: id(row, "id")?,
        name: row.get("name")?,
        album_count: row.get("album_count")?,
        track_count: row.get("track_count")?,
    })
}

#[cfg(test)]
mod tests {
    use crate::db::query::testing::library;

    /// Без артикля и без исполнителей, у которых не осталось видимых треков.
    #[test]
    fn artists_by_name_with_counts() {
        let mut lib = library();
        let gone = lib.artist("Gone Band");
        lib.track("Lost", "Gone Band", &[gone], None, false);

        let artists = lib.db.artist_list().unwrap();

        let summary: Vec<(&str, u32, u32)> =
            artists.iter().map(|a| (a.name.as_str(), a.album_count, a.track_count)).collect();
        assert_eq!(
            summary,
            [("The Beatles", 1, 2), ("Daft Punk", 1, 1), ("Pharrell", 1, 1), ("Radiohead", 1, 3), ("Ёлка", 0, 1)]
        );
    }

    /// Гость из «feat.» — тоже исполнитель трека.
    #[test]
    fn tracks_of_an_artist_by_album_then_number() {
        let mut lib = library();
        let radiohead = lib.db.artists_named("Radiohead").unwrap()[0].id;
        lib.track("Single", "Radiohead", &[radiohead], None, true);

        let titles = |name: &str| -> Vec<String> {
            lib.db.artist_tracks(name).unwrap().into_iter().map(|row| row.title).collect()
        };

        assert_eq!(titles("radiohead"), ["You", "Creep", "Anyone Can Play Guitar", "Single"]);
        assert_eq!(titles("Pharrell"), ["Get Lucky"]);
        assert!(titles("Nobody").is_empty());
    }
}
