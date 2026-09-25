//! Наполнение каталога для инструментальных тестов (D3b), по образцу
//! `panic_for_test`: контракт `LibraryRepository` на эмуляторе кладёт в ядро
//! файлы с тегами, не создавая самих файлов. Путь записи — тот же, что у
//! скана: теги проходят `normalized` с разделителями по умолчанию и пишутся
//! `scan::write`. Не читается только файл.

use std::time::Duration;

use plinth_library::scan::{ArtistSplit, FoundFile, RawTags, ScannedFile, normalized, write};
use plinth_types::{Availability, CoreError, Format, Timestamp};

use crate::panic;
use crate::session::Core;

/// Файл с тегами — как его прочёл бы скан.
#[derive(uniffi::Record)]
pub struct TestFile {
    /// Путь к файлу — по нему источник узнаётся и скрывается.
    pub uri: String,
    /// Папка от корня тома: `Music/Queen/`.
    pub folder: String,
    /// Пустое или `None` — название из имени файла, как у скана.
    pub title: Option<String>,
    /// Исполнитель строкой; на артистов делится разделителями по умолчанию.
    pub artist: Option<String>,
    pub album: Option<String>,
    pub album_artist: Option<String>,
    pub disc: Option<u32>,
    pub number: Option<u32>,
    pub duration_ms: Option<u64>,
}

#[uniffi::export]
impl Core {
    /// Кладёт `files` в каталог новыми треками одной транзакцией.
    pub fn seed_for_test(&self, files: Vec<TestFile>) -> Result<(), CoreError> {
        panic::guard(|| {
            let now = Timestamp::now();
            let split = ArtistSplit::default();
            let scanned: Vec<ScannedFile> = files.into_iter().map(|file| scanned(file, &split, now)).collect();
            self.with(|state| state.db.in_transaction(|db| write(db, &scanned, now)))
        })
    }

    /// Файлы `paths` пропали: их треки скрываются отовсюду, как после скана.
    /// Неизвестные пути пропускаются.
    pub fn hide_for_test(&self, paths: Vec<String>) -> Result<(), CoreError> {
        panic::guard(|| {
            let now = Timestamp::now();
            self.with(|state| {
                state.db.in_transaction(|db| {
                    let known = db.known_local_files()?;
                    for file in paths.iter().filter_map(|path| known.get(path)) {
                        db.set_availability(file.source, Availability::Unavailable, now)?;
                    }
                    db.prune_catalog()
                })
            })
        })
    }
}

/// Формат для списков не важен: звук файла тесты не проверяют.
fn scanned(file: TestFile, split: &ArtistSplit, now: Timestamp) -> ScannedFile {
    let raw = RawTags {
        title: file.title,
        artist: file.artist.into_iter().collect(),
        album: file.album,
        album_artist: file.album_artist.into_iter().collect(),
        track: file.number,
        disc: file.disc,
        duration: file.duration_ms.map(Duration::from_millis),
        ..RawTags::default()
    };
    ScannedFile {
        file: FoundFile { uri: file.uri, folder: file.folder, format: Format::Mp3, modified_at: now, size: 0 },
        known: None,
        tags: normalized(raw, split),
        readable: true,
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_library::db::query::{AlbumSort, TrackSort};

    use super::TestFile;
    use crate::session::Core;
    use crate::testing::Scratch;

    fn file(name: &str, title: &str, artist: Option<&str>, album: Option<&str>) -> TestFile {
        TestFile {
            uri: format!("/storage/emulated/0/Music/{name}.mp3"),
            folder: "Music/".to_owned(),
            title: Some(title.to_owned()),
            artist: artist.map(str::to_owned),
            album: album.map(str::to_owned),
            album_artist: None,
            disc: None,
            number: None,
            duration_ms: None,
        }
    }

    /// Файл ложится в каталог так же, как после скана: с путём, папкой,
    /// номерами, длительностью, альбомом и артистами по отдельности.
    #[test]
    fn seeded_files_are_listed_like_scanned_ones() {
        let data = Scratch::new();
        let core = Core::open(data.path()).unwrap();
        let mut under = file("under-pressure", "Under Pressure", Some("Queen & David Bowie"), Some("Hot Space"));
        (under.disc, under.number, under.duration_ms) = (Some(1), Some(11), Some(248_000));

        core.seed_for_test(vec![under, file("untitled", "", None, None)]).unwrap();

        let rows = core.tracks(TrackSort::Title, None).unwrap();
        let titles: Vec<&str> = rows.iter().map(|row| row.title.as_str()).collect();
        assert_eq!(titles, ["Under Pressure", "untitled"], "без названия — имя файла");
        let row = &rows[0];
        assert_eq!(row.uri.as_deref(), Some("/storage/emulated/0/Music/under-pressure.mp3"));
        assert_eq!(row.folder.as_deref(), Some("Music/"));
        assert_eq!((row.disc, row.number), (Some(1), Some(11)));
        assert_eq!(row.duration, Some(Duration::from_millis(248_000)));
        assert_eq!((row.album_title.as_deref(), row.album_artist.as_deref()), (Some("Hot Space"), Some("Queen")));
        let artists: Vec<String> = core.artists().unwrap().into_iter().map(|artist| artist.name).collect();
        assert_eq!(artists, ["David Bowie", "Queen"]);
    }

    /// Скрытый файл пропадает отовсюду, как пропавший при скане.
    #[test]
    fn hidden_files_leave_every_list() {
        let data = Scratch::new();
        let core = Core::open(data.path()).unwrap();
        let (kept, gone) = (
            file("jazz", "Mustapha", Some("Queen"), Some("Jazz")),
            file("arrival", "Dancing Queen", Some("ABBA"), None),
        );
        let path = gone.uri.clone();
        core.seed_for_test(vec![kept, gone]).unwrap();

        core.hide_for_test(vec![path, "/storage/emulated/0/Music/never-seeded.mp3".to_owned()]).unwrap();

        let titles: Vec<String> = core.tracks(TrackSort::Title, None).unwrap().into_iter().map(|t| t.title).collect();
        assert_eq!(titles, ["Mustapha"]);
        assert_eq!(core.artists().unwrap().len(), 1);
        assert_eq!(core.albums(AlbumSort::Title).unwrap().len(), 1);
    }
}
