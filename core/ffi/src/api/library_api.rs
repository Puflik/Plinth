//! Чтение библиотеки (A3.1, D3): списки экранов одним вызовом, строки готовы
//! к показу. Видны только треки, которые можно сыграть; названия — в
//! естественном порядке и без ведущего артикля (`plinth_library::sort`).

use std::path::Path;

use plinth_library::db::query::{AlbumRow, AlbumSort, ArtistRow, PlaylistRow, TrackRow, TrackSort};
use plinth_library::model::TrackUserData;
use plinth_library::scan::{Artwork, artwork};
use plinth_library::sort::sort_key;
use plinth_types::{AlbumId, CoreError, PlaylistId, TrackId};

use crate::panic;
use crate::session::Core;

#[uniffi::export]
impl Core {
    /// Треки в порядке `sort`; `search` — каждое слово есть в названии,
    /// исполнителе, альбоме или исполнителе альбома, без учёта регистра,
    /// диакритики и знаков. Запрос без слов ничего не находит.
    pub fn tracks(&self, sort: TrackSort, search: Option<String>) -> Result<Vec<TrackRow>, CoreError> {
        panic::guard(|| self.with(|state| state.db.track_list(sort, search.as_deref())))
    }

    /// Треки альбома по дискам и номерам.
    pub fn album_tracks(&self, album: AlbumId) -> Result<Vec<TrackRow>, CoreError> {
        panic::guard(|| self.with(|state| state.db.album_tracks(album)))
    }

    /// Альбомы с видимыми треками: число треков и файл-обложка.
    pub fn albums(&self, sort: AlbumSort) -> Result<Vec<AlbumRow>, CoreError> {
        panic::guard(|| self.with(|state| state.db.album_list(sort)))
    }

    /// Альбом по названию и исполнителю — так его открывает экран по ссылке.
    pub fn find_album(&self, title: String, artist: Option<String>) -> Result<Option<AlbumId>, CoreError> {
        panic::guard(|| self.with(|state| state.db.find_album(&title, artist.as_deref())))
    }

    /// Исполнители видимых треков со счётчиками альбомов и треков.
    pub fn artists(&self) -> Result<Vec<ArtistRow>, CoreError> {
        panic::guard(|| self.with(|state| state.db.artist_list()))
    }

    /// Треки исполнителя `name`: альбомы по названию, внутри — диск и номер.
    pub fn artist_tracks(&self, name: String) -> Result<Vec<TrackRow>, CoreError> {
        panic::guard(|| self.with(|state| state.db.artist_tracks(&name)))
    }

    /// Альбомы, где есть треки исполнителя `name`.
    pub fn artist_albums(&self, name: String) -> Result<Vec<AlbumRow>, CoreError> {
        panic::guard(|| self.with(|state| state.db.artist_albums(&name)))
    }

    /// «Любимое»: видимые треки с лайком, по названию.
    pub fn liked_tracks(&self) -> Result<Vec<TrackRow>, CoreError> {
        panic::guard(|| self.with(|state| state.db.liked_tracks()))
    }

    /// «Недавнее»: видимые треки по последнему засчитанному прослушиванию,
    /// новые первыми, не больше `limit`.
    pub fn recent_tracks(&self, limit: u32) -> Result<Vec<TrackRow>, CoreError> {
        panic::guard(|| self.with(|state| state.db.recent_tracks(limit)))
    }

    /// Видимые треки плейлиста в его порядке, каждый — со своей записью.
    /// Пропавший файл скрыт, а его запись — нет: индекс строки здесь не индекс
    /// записи среди всех (`playlist_items`), по которому переставляет
    /// `move_in_playlist`.
    pub fn playlist_tracks(&self, playlist: PlaylistId) -> Result<Vec<PlaylistRow>, CoreError> {
        panic::guard(|| self.with(|state| state.db.playlist_tracks(playlist)))
    }

    /// Трек библиотеки, который играет из файла `path`; файла в библиотеке нет — `None`.
    pub fn track_at(&self, path: String) -> Result<Option<TrackId>, CoreError> {
        panic::guard(|| self.with(|state| state.db.track_at(&path)))
    }

    /// Лайк, оценка и счётчики трека; не слушали и не оценивали — пустые.
    pub fn user_data(&self, track: TrackId) -> Result<TrackUserData, CoreError> {
        panic::guard(|| self.with(|state| state.db.user_data(track)))
    }

    /// Обложка файла `path`: встроенная, иначе `cover.jpg` рядом. Читает файл,
    /// а не базу, — замок ядра не берёт.
    pub fn artwork(&self, path: String) -> Result<Option<Artwork>, CoreError> {
        panic::guard(|| artwork(Path::new(&path)))
    }

    /// Ключи сортировки названий — для того, что Kotlin собирает сам (папки):
    /// тот же порядок, что у списков ядра.
    pub fn sort_keys(&self, texts: Vec<String>) -> Result<Vec<String>, CoreError> {
        panic::guard(|| Ok(texts.iter().map(|text| sort_key(text)).collect()))
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::Path;
    use std::sync::Arc;

    use plinth_library::db::query::{AlbumSort, TrackSort};
    use plinth_library::scan::FolderConfig;

    use crate::api::scan_api::ScanListener;
    use crate::session::Core;
    use crate::testing::Scratch;

    struct Quiet;

    impl ScanListener for Quiet {
        fn progress(&self, _progress: plinth_library::scan::ScanProgress) -> bool {
            true
        }
    }

    /// Том с фикстурами тегов приложения: MP3, FLAC и M4A альбома «Fixtures»
    /// и MP3 без тегов.
    fn scanned() -> (Scratch, Scratch, Arc<Core>) {
        let (data, volume) = (Scratch::new(), Scratch::new());
        let assets = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../app/src/androidTest/assets/tags");
        let folder = volume.0.join("Music/Fixtures");
        fs::create_dir_all(&folder).unwrap();
        for name in ["plinth-mp3.mp3", "plinth-flac.flac", "plinth-m4a.m4a", "plinth-untagged.mp3"] {
            fs::copy(assets.join(name), folder.join(name)).unwrap();
        }
        let core = Core::open(data.path()).unwrap();
        core.scan(vec![volume.path()], FolderConfig::default(), Arc::new(Quiet)).unwrap();
        (data, volume, core)
    }

    #[test]
    fn a_fresh_library_is_empty() {
        let dir = Scratch::new();
        let core = Core::open(dir.path()).unwrap();

        assert!(core.tracks(TrackSort::Title, None).unwrap().is_empty());
        assert!(core.albums(AlbumSort::Title).unwrap().is_empty());
        assert!(core.artists().unwrap().is_empty());
    }

    /// Строка списка несёт каталог, файл и пользовательское из журнала.
    #[test]
    fn track_rows_show_likes_files_and_follow_search() {
        let (_data, _volume, core) = scanned();
        let tishina = core.tracks(TrackSort::Title, Some("тишина".to_owned())).unwrap().remove(0);

        core.like(tishina.id).unwrap();

        let rows = core.tracks(TrackSort::Album, None).unwrap();
        let summary: Vec<(&str, bool)> = rows.iter().map(|r| (r.title.as_str(), r.liked)).collect();
        assert_eq!(
            summary,
            [("FLAC Silence", false), ("M4A Silence", false), ("Тишина", true), ("plinth-untagged", false)]
        );
        assert_eq!(rows[2].folder.as_deref(), Some("Music/Fixtures/"));
        assert!(rows[2].uri.as_deref().is_some_and(|uri| uri.ends_with("plinth-mp3.mp3")));
        assert_eq!((rows[2].disc, rows[2].number), (Some(2), Some(3)));
    }

    /// Плеер знает только путь к файлу — по нему ядро находит трек.
    #[test]
    fn a_track_is_found_by_its_path() {
        let (_data, _volume, core) = scanned();
        let tishina = core.tracks(TrackSort::Title, Some("тишина".to_owned())).unwrap().remove(0);

        assert_eq!(core.track_at(tishina.uri.clone().unwrap()).unwrap(), Some(tishina.id));
        assert_eq!(core.track_at("/nowhere/song.mp3".to_owned()).unwrap(), None);
    }

    /// Лайк ведёт трек в «Любимое», засчитанное прослушивание — в «Недавнее»;
    /// брошенный на десятой секунде трек в «Недавнее» не попадает.
    #[test]
    fn liked_and_recent_tracks_follow_the_journal() {
        let (_data, _volume, core) = scanned();
        let find = |title: &str| core.tracks(TrackSort::Title, Some(title.to_owned())).unwrap().remove(0).id;
        let (tishina, flac) = (find("тишина"), find("FLAC Silence"));

        core.like(tishina).unwrap();
        core.record_play(play(flac, 200)).unwrap();
        core.record_play(play(tishina, 10)).unwrap();

        let titles =
            |rows: Vec<plinth_library::db::query::TrackRow>| rows.into_iter().map(|row| row.title).collect::<Vec<_>>();
        assert_eq!(titles(core.liked_tracks().unwrap()), ["Тишина"]);
        assert_eq!(titles(core.recent_tracks(10).unwrap()), ["FLAC Silence"]);
    }

    /// Строки плейлиста — видимые треки в его порядке со своими записями:
    /// пропавший файл скрыт, а среди всех записей (`playlist_items`) остаётся.
    #[test]
    fn playlist_tracks_carry_entries_and_hide_missing_files() {
        let (_data, _volume, core) = scanned();
        let find = |title: &str| core.tracks(TrackSort::Title, Some(title.to_owned())).unwrap().remove(0);
        let (tishina, flac) = (find("тишина"), find("FLAC Silence"));
        let mix = core.create_playlist("Mix".to_owned()).unwrap();
        for track in [tishina.id, flac.id, tishina.id] {
            core.add_to_playlist(mix.id, track, None).unwrap();
        }

        core.hide_for_test(vec![flac.uri.unwrap()]).unwrap();

        let items = core.playlist_items(mix.id).unwrap();
        let rows = core.playlist_tracks(mix.id).unwrap();
        assert_eq!(rows.iter().map(|row| row.track.title.as_str()).collect::<Vec<_>>(), ["Тишина", "Тишина"]);
        assert_eq!(rows.iter().map(|row| row.entry).collect::<Vec<_>>(), [items[0].id, items[2].id]);
        assert_eq!(items.len(), 3);
    }

    fn play(track: plinth_types::TrackId, listened_s: u64) -> crate::types::NewPlay {
        crate::types::NewPlay {
            track,
            version: None,
            source: None,
            started_at: plinth_types::Timestamp::from_millis(1_790_307_000_000),
            utc_offset_minutes: 300,
            listened: std::time::Duration::from_secs(listened_s),
            track_length: Some(std::time::Duration::from_secs(240)),
            skipped_at: None,
            output: plinth_library::model::OutputDevice::Unknown,
            previous_track: None,
        }
    }

    #[test]
    fn albums_artists_and_their_tracks() {
        let (_data, _volume, core) = scanned();

        let albums = core.albums(AlbumSort::Title).unwrap();
        let artists: Vec<(String, u32)> =
            core.artists().unwrap().into_iter().map(|a| (a.name, a.track_count)).collect();
        let found = core.find_album("fixtures".to_owned(), Some("Plinth Various".to_owned())).unwrap();
        let plinth: Vec<String> =
            core.artist_tracks("Plinth".to_owned()).unwrap().into_iter().map(|t| t.title).collect();

        assert_eq!(albums.len(), 1);
        assert_eq!((albums[0].title.as_str(), albums[0].track_count), ("Fixtures", 3));
        assert_eq!(found, Some(albums[0].id));
        assert_eq!(core.album_tracks(albums[0].id).unwrap().len(), 3);
        assert_eq!(artists, [("Plinth".to_owned(), 2), ("The Plinth".to_owned(), 1)]);
        assert_eq!(plinth, ["FLAC Silence", "Тишина"]);
        assert_eq!(core.artist_albums("The Plinth".to_owned()).unwrap()[0].title, "Fixtures");
    }

    #[test]
    fn artwork_and_sort_keys() {
        let dir = Scratch::new();
        let core = Core::open(dir.path()).unwrap();
        let cover =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("../../app/src/androidTest/assets/artwork/plinth-cover.mp3");

        let art = core.artwork(cover.to_string_lossy().into_owned()).unwrap().unwrap();
        let keys = core.sort_keys(vec!["The 10 Bears".to_owned(), "2 Bears".to_owned()]).unwrap();

        assert_eq!(art.mime.as_deref(), Some("image/jpeg"));
        assert!(keys[1] < keys[0], "{keys:?}");
    }
}
