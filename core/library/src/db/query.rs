//! Выборки для экранов (B2.2): списки строк, готовых к показу, одним
//! запросом — без N+1 обращений из Kotlin через границу.

use std::time::Duration;

use plinth_types::{AlbumId, CoreError, EntityId, TrackId};
use rusqlite::{Row, params};

use super::Database;
use super::sql::{Storage, id, opt_duration, opt_id};
use crate::text::normalize;

/// Порядок списка треков (экран библиотеки).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrackSort {
    Title,
    Artist,
    RecentlyAdded,
    MostPlayed,
}

/// Строка списка треков: песня, её основная версия и пользовательское.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackRow {
    pub id: TrackId,
    pub title: String,
    pub artist_credit: String,
    pub album: Option<AlbumId>,
    pub album_title: Option<String>,
    pub duration: Option<Duration>,
    pub liked: bool,
    pub play_count: u32,
}

/// Основная версия трека — первая оригинальная, иначе первая любая.
const TRACK_ROW: &str = "
    SELECT t.id, t.title, t.artist_credit, v.album AS album, a.title AS album_title, v.duration_ms,
           coalesce(u.liked, 0) AS liked, coalesce(u.play_count, 0) AS play_count
    FROM track t
    LEFT JOIN version v ON v.id = (SELECT id FROM version WHERE track = t.id ORDER BY kind <> 'original', id LIMIT 1)
    LEFT JOIN album a ON a.id = v.album
    LEFT JOIN track_user u ON u.track = t.id";

impl Database {
    /// Все треки в порядке `sort`. `search` — по названию и артисту после
    /// нормализации; пустой или из одних знаков — без фильтра.
    pub fn track_list(&self, sort: TrackSort, search: Option<&str>) -> Result<Vec<TrackRow>, CoreError> {
        let query = search.map(normalize).unwrap_or_default();
        let order = match sort {
            TrackSort::Title => "t.title_normalized, t.id",
            TrackSort::Artist => "t.artist_normalized, t.title_normalized, t.id",
            TrackSort::RecentlyAdded => "t.added_at DESC, t.id DESC",
            TrackSort::MostPlayed => "play_count DESC, t.title_normalized, t.id",
        };
        // После нормализации в запросе нет ни `%`, ни `_`: экранировать LIKE нечего.
        let sql = format!(
            "{TRACK_ROW} WHERE ?1 = '' OR t.title_normalized LIKE ?2 OR t.artist_normalized LIKE ?2 ORDER BY {order}"
        );
        let mut statement = self.conn().prepare(&sql).storage()?;
        let rows = statement.query_map(params![query, format!("%{query}%")], read_row).storage()?;
        rows.collect::<Result<_, _>>().storage()
    }

    /// Треки альбома по дискам и номерам (экран альбома).
    pub fn album_tracks(&self, album: AlbumId) -> Result<Vec<TrackRow>, CoreError> {
        let mut statement = self
            .conn()
            .prepare(
                "SELECT t.id, t.title, t.artist_credit, v.album AS album, a.title AS album_title, v.duration_ms,
                        coalesce(u.liked, 0) AS liked, coalesce(u.play_count, 0) AS play_count
                 FROM version v
                 JOIN track t ON t.id = v.track
                 LEFT JOIN album a ON a.id = v.album
                 LEFT JOIN track_user u ON u.track = t.id
                 WHERE v.album = ?1
                 ORDER BY v.disc, v.number, t.title_normalized",
            )
            .storage()?;
        statement.query_map([album.as_bytes()], read_row).storage()?.collect::<Result<_, _>>().storage()
    }
}

fn read_row(row: &Row<'_>) -> rusqlite::Result<TrackRow> {
    Ok(TrackRow {
        id: id(row, "id")?,
        title: row.get("title")?,
        artist_credit: row.get("artist_credit")?,
        album: opt_id(row, "album")?,
        album_title: row.get("album_title")?,
        duration: opt_duration(row, "duration_ms")?,
        liked: row.get("liked")?,
        play_count: row.get("play_count")?,
    })
}

#[cfg(test)]
mod tests {
    use super::TrackSort;
    use crate::db::Database;
    use crate::db::repo::fixtures::{album, artist, db, track, version};
    use crate::model::TrackUserData;

    /// Три трека двух артистов; «Ёлка» добавлена последней, «Creep» слушали 47 раз.
    fn library() -> Database {
        let db = db();
        let radiohead = artist("Radiohead");
        let yolka = artist("Ёлка");
        db.save_artist(&radiohead).unwrap();
        db.save_artist(&yolka).unwrap();
        let honey = album(&radiohead);
        db.save_album(&honey).unwrap();
        for (title, who, added, number) in [
            ("Creep", &radiohead, 1_000, 2),
            ("Anyone Can Play Guitar", &radiohead, 2_000, 3),
            ("Прованс", &yolka, 3_000, 1),
        ] {
            let t = track(title, who, added);
            db.save_track(&t).unwrap();
            let on_album = if std::ptr::eq(who, &radiohead) { Some(&honey) } else { None };
            db.save_version(&version(&t, on_album, number)).unwrap();
            if title == "Creep" {
                let mut data = TrackUserData::empty(t.id);
                data.play_count = 47;
                data.liked = true;
                db.save_user_data(&data).unwrap();
            }
        }
        db
    }

    fn titles(db: &Database, sort: TrackSort, search: Option<&str>) -> Vec<String> {
        db.track_list(sort, search).unwrap().into_iter().map(|row| row.title).collect()
    }

    #[test]
    fn sorted_by_title_artist_added_and_plays() {
        let db = library();

        assert_eq!(titles(&db, TrackSort::Title, None), ["Anyone Can Play Guitar", "Creep", "Прованс"]);
        assert_eq!(titles(&db, TrackSort::Artist, None), ["Anyone Can Play Guitar", "Creep", "Прованс"]);
        assert_eq!(titles(&db, TrackSort::RecentlyAdded, None), ["Прованс", "Anyone Can Play Guitar", "Creep"]);
        assert_eq!(titles(&db, TrackSort::MostPlayed, None)[0], "Creep");
    }

    #[test]
    fn row_carries_duration_and_user_data() {
        let db = library();

        let creep = db.track_list(TrackSort::MostPlayed, None).unwrap().remove(0);

        assert_eq!((creep.liked, creep.play_count), (true, 47));
        assert_eq!(creep.duration.map(|d| d.as_millis()), Some(238_640));
        assert_eq!(creep.album_title.as_deref(), Some("Pablo Honey"));
    }

    /// Поиск — по названию и артисту, без учёта регистра, «ё» и знаков препинания.
    #[test]
    fn search_matches_title_or_artist_normalized() {
        let db = library();

        assert_eq!(titles(&db, TrackSort::Title, Some("GUITAR")), ["Anyone Can Play Guitar"]);
        assert_eq!(titles(&db, TrackSort::Title, Some("radio")), ["Anyone Can Play Guitar", "Creep"]);
        assert_eq!(titles(&db, TrackSort::Title, Some("елка")), ["Прованс"]);
        assert!(titles(&db, TrackSort::Title, Some("%")).len() == 3, "punctuation-only query matches everything");
        assert!(titles(&db, TrackSort::Title, Some("zzz")).is_empty());
    }

    #[test]
    fn album_tracks_follow_disc_and_number() {
        let db = library();
        let honey = db.track_list(TrackSort::Title, Some("creep")).unwrap()[0].album.unwrap();

        let tracks: Vec<String> = db.album_tracks(honey).unwrap().into_iter().map(|row| row.title).collect();

        assert_eq!(tracks, ["Creep", "Anyone Can Play Guitar"]);
    }
}
