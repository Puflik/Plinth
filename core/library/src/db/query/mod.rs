//! Выборки для экранов (B2.2, D3): списки строк, готовых к показу, одним
//! запросом — без N+1 обращений из Kotlin через границу.
//!
//! Видны только треки, которые можно сыграть: у основной версии есть
//! доступный источник. Пропавший файл скрыт из всех списков, но трек со
//! своими лайками и местом в плейлистах остаётся (ADR 0007).
//!
//! Названия сортируются по ключам (`crate::sort`): естественный порядок, без
//! ведущего артикля. Пустое — исполнителя нет, альбома нет — идёт в конце.

mod albums;
mod artists;

use std::time::Duration;

use plinth_types::{AlbumId, CoreError, EntityId, TrackId};
use rusqlite::{Params, Row};

pub use albums::{AlbumRow, AlbumSort};
pub use artists::ArtistRow;

use super::Database;
use super::sql::{Storage, id, opt_duration, opt_id};
use crate::text::normalize;

/// Порядок списка треков.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrackSort {
    /// По названию, затем по исполнителю.
    Title,
    /// По исполнителю; внутри — альбомы по названию, диск и номер; без альбома — в конце.
    Artist,
    /// По альбому и его исполнителю, внутри — диск и номер; без альбома — в конце.
    Album,
    RecentlyAdded,
    MostPlayed,
}

/// Строка списка треков: песня, её основная версия, файл и пользовательское.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackRow {
    pub id: TrackId,
    pub title: String,
    pub artist_credit: String,
    pub album: Option<AlbumId>,
    pub album_title: Option<String>,
    /// Исполнитель альбома: album artist, «Various Artists» у сборника.
    pub album_artist: Option<String>,
    pub disc: Option<u16>,
    pub number: Option<u16>,
    pub duration: Option<Duration>,
    /// Файл, из которого трек играет.
    pub uri: Option<String>,
    /// Папка файла от корня тома: `Music/Queen/`.
    pub folder: Option<String>,
    pub liked: bool,
    pub play_count: u32,
}

/// Треки, которые можно сыграть: основная версия — первая оригинальная,
/// иначе первая любая; источник — доступный, локальный раньше остальных.
/// `'available'` — код `Availability::Available` (`db/codes.rs`).
const PLAYABLE: &str = "
    FROM track t
    JOIN version v ON v.id = (SELECT id FROM version WHERE track = t.id ORDER BY kind <> 'original', id LIMIT 1)
    JOIN source s ON s.id = (SELECT id FROM source WHERE version = v.id AND availability = 'available'
                             ORDER BY local_uri IS NULL, id LIMIT 1)";

const TRACK_COLUMNS: &str = "
    SELECT t.id, t.title, t.artist_credit, v.album AS album, a.title AS album_title,
           nullif(a.artist_credit, '') AS album_artist, v.disc, v.number, v.duration_ms,
           s.local_uri AS uri, f.folder AS folder,
           coalesce(u.liked, 0) AS liked, coalesce(u.play_count, 0) AS play_count";

const TRACK_JOINS: &str = "
    LEFT JOIN scan_file f ON f.source = s.id
    LEFT JOIN album a ON a.id = v.album
    LEFT JOIN track_user u ON u.track = t.id";

/// Порядок внутри альбома: диск, номер, без номера — в конце диска.
const ON_ALBUM: &str = "v.disc IS NULL, v.disc, v.number IS NULL, v.number, t.title_sort, t.id";

impl Database {
    /// Видимые треки в порядке `sort`. `search` — каждое слово запроса есть в
    /// названии, исполнителе, альбоме или исполнителе альбома, без учёта
    /// регистра, диакритики и знаков; пустой запрос — без фильтра.
    pub fn track_list(&self, sort: TrackSort, search: Option<&str>) -> Result<Vec<TrackRow>, CoreError> {
        let order = match sort {
            TrackSort::Title => "t.title_sort, t.artist_sort, t.id".to_owned(),
            TrackSort::Artist => {
                format!("t.artist_credit = '', t.artist_sort, a.id IS NULL, a.title_sort, a.id, {ON_ALBUM}")
            }
            TrackSort::Album => format!("a.id IS NULL, a.title_sort, a.artist_sort, a.id, {ON_ALBUM}"),
            TrackSort::RecentlyAdded => "t.added_at DESC, t.id DESC".to_owned(),
            TrackSort::MostPlayed => "play_count DESC, t.title_sort, t.id".to_owned(),
        };
        let rows = self.track_rows("", [], &order)?;
        let words: Vec<String> =
            search.map(normalize).unwrap_or_default().split(' ').filter(|w| !w.is_empty()).map(str::to_owned).collect();
        if words.is_empty() {
            return Ok(rows);
        }
        Ok(rows.into_iter().filter(|row| matches(row, &words)).collect())
    }

    /// Треки альбома по дискам и номерам (экран альбома).
    pub fn album_tracks(&self, album: AlbumId) -> Result<Vec<TrackRow>, CoreError> {
        self.track_rows("WHERE v.album = ?1", [album.as_bytes()], ON_ALBUM)
    }

    /// Видимые треки с условием `filter` в порядке `order`.
    fn track_rows(&self, filter: &str, params: impl Params, order: &str) -> Result<Vec<TrackRow>, CoreError> {
        let sql = format!("{TRACK_COLUMNS} {PLAYABLE} {TRACK_JOINS} {filter} ORDER BY {order}");
        let mut statement = self.conn().prepare(&sql).storage()?;
        statement.query_map(params, read_row).storage()?.collect::<Result<_, _>>().storage()
    }
}

/// Все слова запроса нашлись в полях строки.
fn matches(row: &TrackRow, words: &[String]) -> bool {
    let fields = [Some(&row.title), Some(&row.artist_credit), row.album_title.as_ref(), row.album_artist.as_ref()];
    let text = normalize(&fields.into_iter().flatten().map(String::as_str).collect::<Vec<_>>().join(" "));
    words.iter().all(|word| text.contains(word.as_str()))
}

fn read_row(row: &Row<'_>) -> rusqlite::Result<TrackRow> {
    Ok(TrackRow {
        id: id(row, "id")?,
        title: row.get("title")?,
        artist_credit: row.get("artist_credit")?,
        album: opt_id(row, "album")?,
        album_title: row.get("album_title")?,
        album_artist: row.get("album_artist")?,
        disc: row.get("disc")?,
        number: row.get("number")?,
        duration: opt_duration(row, "duration_ms")?,
        uri: row.get("uri")?,
        folder: row.get::<_, Option<String>>("folder")?.filter(|f| !f.is_empty()),
        liked: row.get("liked")?,
        play_count: row.get("play_count")?,
    })
}

/// Библиотека для тестов выборок: альбомы, сборник, синглы, пропавший файл.
#[cfg(test)]
pub(crate) mod testing {
    use std::time::Duration;

    use plinth_types::{AlbumId, ArtistId, Availability, Format, SourceId, Timestamp, TrackId, VersionId};

    use crate::db::Database;
    use crate::model::{
        Album, AlbumPlacement, Artist, AudioSpec, Explicitness, Source, SourceLocation, Track, TrackUserData, Version,
        VersionKind,
    };

    pub(crate) struct Library {
        pub db: Database,
        added: i64,
    }

    impl Library {
        pub(crate) fn new() -> Self {
            Self { db: Database::open_in_memory().unwrap(), added: 0 }
        }

        pub(crate) fn artist(&self, name: &str) -> ArtistId {
            let artist = Artist {
                id: ArtistId::new(),
                name: name.to_owned(),
                sort_name: None,
                mbid: None,
                aliases: vec![],
                bio: None,
            };
            self.db.save_artist(&artist).unwrap();
            artist.id
        }

        pub(crate) fn album(&self, title: &str, credit: &str, artists: &[ArtistId]) -> AlbumId {
            let album = Album {
                id: AlbumId::new(),
                title: title.to_owned(),
                artist_credit: credit.to_owned(),
                artists: artists.to_vec(),
                year: None,
                label: None,
                country: None,
                disc_count: None,
                mbid_release: None,
            };
            self.db.save_album(&album).unwrap();
            album.id
        }

        /// Трек с файлом `Music/<folder>/<title>.mp3`; `on` — альбом, диск, номер.
        pub(crate) fn track(
            &mut self,
            title: &str,
            credit: &str,
            artists: &[ArtistId],
            on: Option<(AlbumId, Option<u16>, Option<u16>)>,
            available: bool,
        ) -> TrackId {
            self.added += 1;
            let track = Track {
                id: TrackId::new(),
                title: title.to_owned(),
                artist_credit: credit.to_owned(),
                artists: artists.to_vec(),
                mbid_work: None,
                added_at: Timestamp::from_millis(self.added),
            };
            self.db.save_track(&track).unwrap();
            let version = Version {
                id: VersionId::new(),
                track: track.id,
                kind: VersionKind::Original,
                explicitness: Explicitness::Unknown,
                duration: Some(Duration::from_secs(200)),
                album: on.map(|(album, disc, number)| AlbumPlacement { album, disc, number }),
                release_year: None,
                mbid_recording: None,
                fingerprint: None,
            };
            self.db.save_version(&version).unwrap();
            let folder = if credit.is_empty() { "Music/".to_owned() } else { format!("Music/{credit}/") };
            let source = Source {
                id: SourceId::new(),
                version: version.id,
                location: SourceLocation::Local { uri: format!("/storage/emulated/0/{folder}{title}.mp3") },
                audio: AudioSpec { format: Format::Mp3, bitrate: None, sample_rate_hz: None, bit_depth: None },
                availability: if available { Availability::Available } else { Availability::Unavailable },
                last_checked_at: None,
            };
            self.db.save_source(&source).unwrap();
            self.db.save_file_stamp(source.id, Timestamp::from_millis(1), 1, &folder).unwrap();
            track.id
        }

        pub(crate) fn play(&self, track: TrackId, times: u32) {
            let mut data = TrackUserData::empty(track);
            data.play_count = times;
            data.liked = true;
            self.db.save_user_data(&data).unwrap();
        }
    }

    /// Radiohead — «Pablo Honey» (пропавший «Lost Song» на нём же), The
    /// Beatles — «Abbey Road», сборник «Now 99» без своих артистов, синглы
    /// «Ёлки» и два трека без исполнителя.
    pub(crate) fn library() -> Library {
        let mut lib = Library::new();
        let radiohead = lib.artist("Radiohead");
        let beatles = lib.artist("The Beatles");
        let yolka = lib.artist("Ёлка");
        let (daft, pharrell) = (lib.artist("Daft Punk"), lib.artist("Pharrell"));
        let honey = lib.album("Pablo Honey", "Radiohead", &[radiohead]);
        let abbey = lib.album("Abbey Road", "The Beatles", &[beatles]);
        let now = lib.album("Now 99", "Various Artists", &[]);
        let creep = lib.track("Creep", "Radiohead", &[radiohead], Some((honey, Some(1), Some(2))), true);
        lib.track("You", "Radiohead", &[radiohead], Some((honey, Some(1), Some(1))), true);
        lib.track("Anyone Can Play Guitar", "Radiohead", &[radiohead], Some((honey, Some(1), None)), true);
        lib.track("Lost Song", "Radiohead", &[radiohead], Some((honey, Some(1), Some(3))), false);
        lib.track("Something", "The Beatles", &[beatles], Some((abbey, Some(1), Some(2))), true);
        lib.track("Come Together", "The Beatles", &[beatles], Some((abbey, Some(1), Some(1))), true);
        lib.track("Get Lucky", "Daft Punk feat. Pharrell", &[daft, pharrell], Some((now, None, Some(8))), true);
        lib.track("Прованс", "Ёлка", &[yolka], None, true);
        lib.track("Track 10", "", &[], None, true);
        lib.track("Track 2", "", &[], None, true);
        lib.play(creep, 47);
        lib
    }
}

#[cfg(test)]
mod tests {
    use super::TrackSort;
    use super::testing::library;
    use crate::db::Database;

    fn titles(db: &Database, sort: TrackSort, search: Option<&str>) -> Vec<String> {
        db.track_list(sort, search).unwrap().into_iter().map(|row| row.title).collect()
    }

    /// Естественный порядок: «Track 2» раньше «Track 10».
    #[test]
    fn by_title_in_natural_order() {
        let lib = library();

        assert_eq!(
            titles(&lib.db, TrackSort::Title, None),
            [
                "Anyone Can Play Guitar",
                "Come Together",
                "Creep",
                "Get Lucky",
                "Something",
                "Track 2",
                "Track 10",
                "You",
                "Прованс"
            ]
        );
    }

    /// По исполнителю без артикля («The Beatles» под «B»), внутри — альбом,
    /// диск и номер; трек без номера — в конце диска; без исполнителя — в конце.
    #[test]
    fn by_artist_then_album_disc_and_number() {
        let lib = library();

        assert_eq!(
            titles(&lib.db, TrackSort::Artist, None),
            [
                "Come Together",
                "Something",
                "Get Lucky",
                "You",
                "Creep",
                "Anyone Can Play Guitar",
                "Прованс",
                "Track 2",
                "Track 10"
            ]
        );
    }

    #[test]
    fn by_album_then_disc_and_number_and_no_album_last() {
        let lib = library();

        assert_eq!(
            titles(&lib.db, TrackSort::Album, None),
            [
                "Come Together",
                "Something",
                "Get Lucky",
                "You",
                "Creep",
                "Anyone Can Play Guitar",
                "Track 2",
                "Track 10",
                "Прованс"
            ]
        );
    }

    #[test]
    fn recently_added_and_most_played() {
        let lib = library();

        assert_eq!(titles(&lib.db, TrackSort::RecentlyAdded, None)[0], "Track 2");
        assert_eq!(titles(&lib.db, TrackSort::MostPlayed, None)[0], "Creep");
    }

    /// Пропавший файл скрыт из всех списков.
    #[test]
    fn unavailable_tracks_are_hidden() {
        let lib = library();

        for sort in [TrackSort::Title, TrackSort::Artist, TrackSort::Album, TrackSort::RecentlyAdded] {
            assert!(!titles(&lib.db, sort, None).contains(&"Lost Song".to_owned()), "{sort:?}");
        }
    }

    #[test]
    fn a_row_carries_the_file_album_and_user_data() {
        let lib = library();

        let creep = lib.db.track_list(TrackSort::MostPlayed, None).unwrap().remove(0);

        assert_eq!((creep.liked, creep.play_count), (true, 47));
        assert_eq!(
            (creep.album_title.as_deref(), creep.album_artist.as_deref()),
            (Some("Pablo Honey"), Some("Radiohead"))
        );
        assert_eq!((creep.disc, creep.number), (Some(1), Some(2)));
        assert_eq!(creep.uri.as_deref(), Some("/storage/emulated/0/Music/Radiohead/Creep.mp3"));
        assert_eq!(creep.folder.as_deref(), Some("Music/Radiohead/"));
        assert!(creep.duration.is_some());
    }

    /// Каждое слово запроса — в названии, исполнителе, альбоме или исполнителе
    /// альбома; регистр, «ё» и знаки не важны.
    #[test]
    fn search_needs_every_word_in_some_field() {
        let lib = library();

        assert_eq!(titles(&lib.db, TrackSort::Title, Some("GUITAR")), ["Anyone Can Play Guitar"]);
        assert_eq!(titles(&lib.db, TrackSort::Title, Some("radiohead honey you")), ["You"]);
        assert_eq!(titles(&lib.db, TrackSort::Title, Some("various lucky")), ["Get Lucky"]);
        assert_eq!(titles(&lib.db, TrackSort::Title, Some("елка")), ["Прованс"]);
        assert!(titles(&lib.db, TrackSort::Title, Some("lost")).is_empty(), "пропавший не находится");
        assert!(titles(&lib.db, TrackSort::Title, Some("creep abbey")).is_empty());
        assert_eq!(titles(&lib.db, TrackSort::Title, Some(" % ")).len(), 9, "запрос из знаков — без фильтра");
    }

    #[test]
    fn album_tracks_follow_disc_and_number() {
        let lib = library();
        let honey = lib.db.track_list(TrackSort::Title, Some("creep")).unwrap()[0].album.unwrap();

        let tracks: Vec<String> = lib.db.album_tracks(honey).unwrap().into_iter().map(|row| row.title).collect();

        assert_eq!(tracks, ["You", "Creep", "Anyone Can Play Guitar"]);
    }
}
