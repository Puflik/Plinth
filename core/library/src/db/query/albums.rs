//! Альбомы для экранов (D3): список, альбомы исполнителя, поиск альбома по
//! названию и исполнителю — так его находит экран, открытый по ссылке.

use plinth_types::{AlbumId, CoreError};
use rusqlite::{Params, Row};

use super::PLAYABLE;
use crate::db::Database;
use crate::db::sql::{Storage, id};
use crate::text::normalize;

/// Порядок списка альбомов. Альбомы без исполнителя — в конце.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AlbumSort {
    /// По названию, затем по исполнителю.
    Title,
    /// По исполнителю, затем по названию.
    Artist,
}

/// Строка списка альбомов.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AlbumRow {
    pub id: AlbumId,
    pub title: String,
    /// Исполнитель альбома; `None` — неизвестен.
    pub artist_credit: Option<String>,
    /// Видимых треков на альбоме.
    pub track_count: u32,
    /// Файл первого трека альбома — его картинка служит обложкой.
    pub cover_uri: Option<String>,
}

/// Видимые треки: какой альбом, место на нём, файл.
const VISIBLE: &str = "SELECT t.id AS track, v.album AS album, v.disc AS disc, v.number AS number,
                              t.title_sort AS title_sort, s.local_uri AS uri";

impl Database {
    /// Альбомы, на которых есть видимые треки.
    pub fn album_list(&self, sort: AlbumSort) -> Result<Vec<AlbumRow>, CoreError> {
        let order = match sort {
            AlbumSort::Title => "a.title_sort, a.artist_credit = '', a.artist_sort, a.id",
            AlbumSort::Artist => "a.artist_credit = '', a.artist_sort, a.title_sort, a.id",
        };
        self.album_rows("", [], order)
    }

    /// Альбомы, где есть треки исполнителя `name`, по названию. Число треков —
    /// всего альбома: сборник с одной его песней — целый сборник.
    pub fn artist_albums(&self, name: &str) -> Result<Vec<AlbumRow>, CoreError> {
        let filter = "WHERE a.id IN (SELECT w.album FROM visible w
                                     JOIN track_artist ta ON ta.track = w.track
                                     JOIN artist ar ON ar.id = ta.artist
                                     WHERE ar.name_normalized = ?1)";
        self.album_rows(filter, [normalize(name)], "a.title_sort, a.artist_credit = '', a.artist_sort, a.id")
    }

    /// Альбом с названием `title` и исполнителем `artist` (`None` —
    /// исполнитель неизвестен); регистр, диакритика и знаки не важны.
    pub fn find_album(&self, title: &str, artist: Option<&str>) -> Result<Option<AlbumId>, CoreError> {
        let wanted = normalize(artist.unwrap_or_default());
        Ok(self.albums_titled(title)?.into_iter().find(|album| normalize(&album.artist_credit) == wanted).map(|a| a.id))
    }

    fn album_rows(&self, filter: &str, params: impl Params, order: &str) -> Result<Vec<AlbumRow>, CoreError> {
        let sql = format!(
            "WITH visible AS ({VISIBLE} {PLAYABLE})
             SELECT a.id, a.title, nullif(a.artist_credit, '') AS artist_credit, count(*) AS track_count,
                    (SELECT w.uri FROM visible w WHERE w.album = a.id
                     ORDER BY w.disc IS NOT NULL, w.disc, w.number IS NULL, w.number, w.title_sort, w.track
                     LIMIT 1) AS cover_uri
             FROM album a JOIN visible vt ON vt.album = a.id
             {filter}
             GROUP BY a.id
             ORDER BY {order}"
        );
        let mut statement = self.conn().prepare(&sql).storage()?;
        statement.query_map(params, read_album_row).storage()?.collect::<Result<_, _>>().storage()
    }
}

fn read_album_row(row: &Row<'_>) -> rusqlite::Result<AlbumRow> {
    Ok(AlbumRow {
        id: id(row, "id")?,
        title: row.get("title")?,
        artist_credit: row.get("artist_credit")?,
        track_count: row.get("track_count")?,
        cover_uri: row.get("cover_uri")?,
    })
}

#[cfg(test)]
mod tests {
    use super::AlbumSort;
    use crate::db::query::testing::{Library, library};

    #[test]
    fn albums_by_title_with_counts_and_covers() {
        let lib = library();

        let albums = lib.db.album_list(AlbumSort::Title).unwrap();

        let summary: Vec<(&str, Option<&str>, u32)> =
            albums.iter().map(|a| (a.title.as_str(), a.artist_credit.as_deref(), a.track_count)).collect();
        assert_eq!(
            summary,
            [
                ("Abbey Road", Some("The Beatles"), 2),
                ("Now 99", Some("Various Artists"), 1),
                ("Pablo Honey", Some("Radiohead"), 3)
            ],
            "пропавший трек не считается"
        );
        assert_eq!(albums[2].cover_uri.as_deref(), Some("/storage/emulated/0/Music/Radiohead/You.mp3"), "первый трек");
    }

    /// По исполнителю без артикля: «The Beatles» под «B», раньше «Radiohead».
    #[test]
    fn albums_by_artist() {
        let lib = library();

        let titles: Vec<String> = lib.db.album_list(AlbumSort::Artist).unwrap().into_iter().map(|a| a.title).collect();

        assert_eq!(titles, ["Abbey Road", "Pablo Honey", "Now 99"]);
    }

    /// Исполнитель альбома для сортировки из тегов: «Bowie, David» под «B».
    #[test]
    fn albums_by_artist_follow_the_sort_credit() {
        let mut lib = Library::new();
        for (title, credit) in [("Heroes", "David Bowie"), ("Parachutes", "Coldplay"), ("Arrival", "ABBA")] {
            let album = lib.album(title, credit, &[]);
            lib.track(title, credit, &[], Some((album, None, None)), true);
        }
        let heroes = lib.db.albums_titled("Heroes").unwrap().remove(0).id;
        let mut album = lib.db.album(heroes).unwrap().unwrap();

        album.sort_artist_credit = Some("Bowie, David".to_owned());
        lib.db.save_album(&album).unwrap();

        let titles: Vec<String> = lib.db.album_list(AlbumSort::Artist).unwrap().into_iter().map(|a| a.title).collect();
        assert_eq!(titles, ["Arrival", "Heroes", "Parachutes"]);
        assert_eq!(lib.db.album(heroes).unwrap(), Some(album));
    }

    /// Одноимённые альбомы — по исполнителю; без исполнителя — в конце, как в
    /// списке по исполнителю.
    #[test]
    fn same_titled_albums_put_the_unknown_artist_last() {
        let mut lib = Library::new();
        let zz = lib.artist("Zz");
        let (nobody, theirs) = (lib.album("Bootleg", "", &[]), lib.album("Bootleg", "Zz", &[zz]));
        lib.track("a", "", &[], Some((nobody, None, None)), true);
        lib.track("b", "Zz", &[zz], Some((theirs, None, None)), true);

        let owners: Vec<Option<String>> =
            lib.db.album_list(AlbumSort::Title).unwrap().into_iter().map(|a| a.artist_credit).collect();

        assert_eq!(owners, [Some("Zz".to_owned()), None]);
    }

    /// Диск без номера — первым: у однодисковых альбомов тега диска обычно нет.
    /// Обложка — от первого трека в этом же порядке.
    #[test]
    fn a_disc_without_number_goes_first_and_gives_the_cover() {
        let mut lib = Library::new();
        let album = lib.album("Mellon Collie", "", &[]);
        lib.track("d1-t1", "", &[], Some((album, Some(1), Some(1))), true);
        lib.track("none-t5", "", &[], Some((album, None, Some(5))), true);

        let row = lib.db.album_list(AlbumSort::Title).unwrap().remove(0);
        let tracks: Vec<String> = lib.db.album_tracks(row.id).unwrap().into_iter().map(|t| t.title).collect();

        assert_eq!(tracks, ["none-t5", "d1-t1"]);
        assert_eq!(row.cover_uri.as_deref(), Some("/storage/emulated/0/Music/none-t5.mp3"));
    }

    /// Альбом, все треки которого пропали, скрыт.
    #[test]
    fn an_album_without_playable_tracks_is_hidden() {
        let mut lib = library();
        let lost = lib.album("Lost Album", "Nobody", &[]);
        lib.track("Gone", "Nobody", &[], Some((lost, None, Some(1))), false);

        let titles: Vec<String> = lib.db.album_list(AlbumSort::Title).unwrap().into_iter().map(|a| a.title).collect();

        assert!(!titles.contains(&"Lost Album".to_owned()));
    }

    /// Альбомы исполнителя — с числом треков всего альбома.
    #[test]
    fn albums_of_an_artist() {
        let lib = library();

        let pharrell = lib.db.artist_albums("pharrell").unwrap();
        let radiohead = lib.db.artist_albums("Radiohead").unwrap();

        assert_eq!(pharrell.iter().map(|a| (a.title.as_str(), a.track_count)).collect::<Vec<_>>(), [("Now 99", 1)]);
        assert_eq!(radiohead.iter().map(|a| a.title.as_str()).collect::<Vec<_>>(), ["Pablo Honey"]);
        assert!(lib.db.artist_albums("Nobody").unwrap().is_empty());
    }

    #[test]
    fn an_album_is_found_by_title_and_artist() {
        let lib = library();
        let honey = lib.db.album_list(AlbumSort::Title).unwrap().remove(2).id;

        assert_eq!(lib.db.find_album("pablo  HONEY", Some("radiohead")).unwrap(), Some(honey));
        assert_eq!(lib.db.find_album("Pablo Honey", Some("Queen")).unwrap(), None);
        assert_eq!(lib.db.find_album("Pablo Honey", None).unwrap(), None);
    }
}
