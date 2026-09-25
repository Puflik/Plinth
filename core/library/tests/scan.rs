//! D1: сканер на подготовленном дереве файлов — полный цикл: первый скан,
//! повторный без изменений, правка, пропажа и возвращение файла, исключённая
//! папка, остановка, нечитаемые теги, запись пачками.
//!
//! D2: артисты и альбомы из тегов, сборники, уборка каталога, настоящее
//! чтение тегов на фикстурах приложения.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use plinth_library::db::Database;
use plinth_library::db::query::TrackSort;
use plinth_library::model::{Album, AudioSpec, Track, Version};
use plinth_library::scan::{
    ArtistSplit, FileNameOnly, FileTags, FolderConfig, RawTags, ScanPhase, ScanProgress, ScanReport, TagReader, Tags,
    normalized, scan,
};
use plinth_types::{ArtistId, CoreError, DeviceId, Format, Timestamp};

struct Tree(PathBuf);

impl Tree {
    fn new(files: &[&str]) -> Self {
        let tree = Self(std::env::temp_dir().join(format!("plinth-scan-{}", DeviceId::new())));
        fs::create_dir_all(&tree.0).unwrap();
        for file in files {
            tree.write(file, b"audio");
        }
        tree
    }

    fn write(&self, file: &str, bytes: &[u8]) {
        let path = self.0.join(file);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, bytes).unwrap();
    }

    /// Путь по сегментам — с теми же разделителями, что у обхода.
    fn path(&self, file: &str) -> PathBuf {
        file.split('/').fold(self.0.clone(), |dir, segment| dir.join(segment))
    }
}

impl Drop for Tree {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn run(db: &Database, tree: &Tree, config: &FolderConfig, reader: &dyn TagReader) -> ScanReport {
    scan(db, std::slice::from_ref(&tree.0), config, reader, &mut |_| true, Timestamp::from_millis(1_790_000_000_000))
        .unwrap()
}

fn titles(db: &Database) -> Vec<String> {
    db.track_list(TrackSort::Title, None).unwrap().into_iter().map(|row| row.title).collect()
}

fn available(db: &Database, tree: &Tree, file: &str) -> bool {
    db.known_local_files().unwrap()[&tree.path(file).to_string_lossy().into_owned()].available
}

#[test]
fn the_first_scan_adds_files_and_a_second_changes_nothing() {
    let tree = Tree::new(&["Music/Queen/Bohemian Rhapsody.flac", "Music/Creep.mp3", "Download/talk.opus"]);
    let db = Database::open_in_memory().unwrap();

    let first = run(&db, &tree, &FolderConfig::default(), &FileNameOnly);
    let second = run(&db, &tree, &FolderConfig::default(), &FileNameOnly);

    assert_eq!((first.found, first.added), (3, 3));
    assert_eq!(titles(&db), vec!["Bohemian Rhapsody", "Creep", "talk"]);
    assert_eq!((second.found, second.added, second.changed, second.missing), (3, 0, 0, 0));
    assert_eq!(titles(&db).len(), 3);
}

/// Изменённый файл перечитывается и остаётся тем же треком: лайки и плейлисты на месте.
#[test]
fn an_edited_file_keeps_its_track() {
    let tree = Tree::new(&["Music/a.mp3"]);
    let db = Database::open_in_memory().unwrap();
    run(&db, &tree, &FolderConfig::default(), &FileNameOnly);
    let before = db.track_list(TrackSort::Title, None).unwrap()[0].id;

    tree.write("Music/a.mp3", b"re-tagged audio");
    let report = run(&db, &tree, &FolderConfig::default(), &Titled("Retitled"));

    let rows = db.track_list(TrackSort::Title, None).unwrap();
    assert_eq!(report.changed, 1);
    assert_eq!(rows.len(), 1);
    assert_eq!((rows[0].id, rows[0].title.as_str()), (before, "Retitled"));
}

/// Пропавший файл не удаляется: трек остаётся недоступным и оживает, когда файл вернули.
#[test]
fn a_missing_file_turns_unavailable_and_comes_back() {
    let tree = Tree::new(&["Music/a.mp3", "Music/b.mp3"]);
    let db = Database::open_in_memory().unwrap();
    run(&db, &tree, &FolderConfig::default(), &FileNameOnly);
    let aside = tree.path("a.mp3.aside");

    fs::rename(tree.path("Music/a.mp3"), &aside).unwrap();
    let gone = run(&db, &tree, &FolderConfig::default(), &FileNameOnly);
    let unavailable = !available(&db, &tree, "Music/a.mp3");
    fs::rename(&aside, tree.path("Music/a.mp3")).unwrap();
    let back = run(&db, &tree, &FolderConfig::default(), &FileNameOnly);

    assert_eq!(gone.missing, 1);
    assert!(unavailable);
    assert_eq!(titles(&db).len(), 2);
    assert_eq!(back.returned, 1);
    assert!(available(&db, &tree, "Music/a.mp3"));
}

#[test]
fn an_excluded_folder_hides_its_tracks() {
    let tree = Tree::new(&["Music/a.mp3", "Music/Podcasts/ep.mp3"]);
    let db = Database::open_in_memory().unwrap();
    run(&db, &tree, &FolderConfig::default(), &FileNameOnly);

    let config = FolderConfig { excluded: vec!["Music/Podcasts/".to_owned()], ..FolderConfig::default() };
    let report = run(&db, &tree, &config, &FileNameOnly);

    assert_eq!(report.missing, 1);
    assert!(!available(&db, &tree, "Music/Podcasts/ep.mp3"));
    assert!(available(&db, &tree, "Music/a.mp3"));
}

/// Файл с битыми тегами — не повод его потерять: он в каталоге под именем файла.
#[test]
fn a_file_with_unreadable_tags_is_added_by_its_name() {
    let tree = Tree::new(&["Music/broken.flac"]);
    let db = Database::open_in_memory().unwrap();

    let report = run(&db, &tree, &FolderConfig::default(), &Broken);

    assert_eq!((report.added, report.unreadable_files), (1, 1));
    assert_eq!(titles(&db), vec!["broken"]);
}

/// Остановка во время чтения тегов: в каталог не попадает ничего.
#[test]
fn a_scan_stopped_while_reading_writes_nothing() {
    let tree = Tree::new(&["Music/a.mp3", "Music/b.mp3"]);
    let db = Database::open_in_memory().unwrap();

    let report = scan(
        &db,
        std::slice::from_ref(&tree.0),
        &FolderConfig::default(),
        &FileNameOnly,
        &mut |p: ScanProgress| p.phase != ScanPhase::Reading,
        Timestamp::from_millis(1),
    )
    .unwrap();

    assert!(report.stopped);
    assert!(titles(&db).is_empty());
}

/// Большая папка пишется пачками, и ход записи виден до конца.
#[test]
fn many_files_are_written_in_batches() {
    let names: Vec<String> = (0..450).map(|i| format!("Music/track {i:03}.mp3")).collect();
    let tree = Tree::new(&names.iter().map(String::as_str).collect::<Vec<_>>());
    let db = Database::open_in_memory().unwrap();
    let mut writing: Vec<(u32, u32)> = Vec::new();

    let report = scan(
        &db,
        std::slice::from_ref(&tree.0),
        &FolderConfig::default(),
        &FileNameOnly,
        &mut |p: ScanProgress| {
            if p.phase == ScanPhase::Writing {
                writing.push((p.done, p.total));
            }
            true
        },
        Timestamp::from_millis(1),
    )
    .unwrap();

    assert_eq!(report.added, 450);
    assert_eq!(titles(&db).len(), 450);
    assert_eq!(writing, vec![(200, 450), (400, 450), (450, 450)]);
}

/// Теги из «читателя» — в каталог: название, исполнитель, длительность.
#[test]
fn tags_reach_the_catalog() {
    let tree = Tree::new(&["Music/01.flac"]);
    let db = Database::open_in_memory().unwrap();

    run(&db, &tree, &FolderConfig::default(), &Titled("Creep"));

    let row = &db.track_list(TrackSort::Title, None).unwrap()[0];
    assert_eq!((row.title.as_str(), row.artist_credit.as_str()), ("Creep", "Radiohead"));
    assert_eq!(row.duration, Some(Duration::from_secs(238)));
}

struct Titled(&'static str);

impl TagReader for Titled {
    fn read(&self, _path: &Path) -> Result<Tags, CoreError> {
        Ok(Tags {
            title: Some(self.0.to_owned()),
            artist: Some("Radiohead".to_owned()),
            duration: Some(Duration::from_secs(238)),
            ..Tags::default()
        })
    }
}

struct Broken;

impl TagReader for Broken {
    fn read(&self, _path: &Path) -> Result<Tags, CoreError> {
        Err(CoreError::parse("not a FLAC stream"))
    }
}

/// Теги по имени файла; незнакомый файл — без тегов.
struct ByName(Vec<(&'static str, Tags)>);

impl TagReader for ByName {
    fn read(&self, path: &Path) -> Result<Tags, CoreError> {
        let name = path.file_name().unwrap().to_string_lossy();
        Ok(self.0.iter().find(|(file, _)| *file == name).map(|(_, tags)| tags.clone()).unwrap_or_default())
    }
}

/// Теги, сведённые так же, как их сведёт настоящее чтение.
fn song(title: &str, artist: &str, album: Option<&str>, album_artist: Option<&str>) -> Tags {
    let raw = RawTags {
        title: Some(title.to_owned()),
        artist: vec![artist.to_owned()],
        album: album.map(str::to_owned),
        album_artist: album_artist.map(str::to_owned).into_iter().collect(),
        ..RawTags::default()
    };
    normalized(raw, &ArtistSplit::default())
}

fn track(db: &Database, title: &str) -> Track {
    let rows = db.track_list(TrackSort::Title, None).unwrap();
    let row = rows.iter().find(|row| row.title == title).expect("no track with this title");
    db.track(row.id).unwrap().unwrap()
}

fn version(db: &Database, title: &str) -> Version {
    db.versions_of(track(db, title).id).unwrap().remove(0)
}

fn album(db: &Database, title: &str) -> Option<Album> {
    version(db, title).album.map(|placement| db.album(placement.album).unwrap().unwrap())
}

fn names(db: &Database, ids: &[ArtistId]) -> Vec<String> {
    ids.iter().map(|id| db.artist(*id).unwrap().unwrap().name).collect()
}

#[test]
fn artists_and_the_album_reach_the_catalog() {
    let tree = Tree::new(&["Music/08.flac"]);
    let db = Database::open_in_memory().unwrap();
    let mut tags =
        song("Get Lucky", "Daft Punk feat. Pharrell Williams", Some("Random Access Memories"), Some("Daft Punk"));
    (tags.track, tags.disc, tags.disc_total, tags.year) = (Some(8), Some(1), Some(1), Some(2013));

    run(&db, &tree, &FolderConfig::default(), &ByName(vec![("08.flac", tags)]));

    let lucky = track(&db, "Get Lucky");
    assert_eq!(lucky.artist_credit, "Daft Punk feat. Pharrell Williams");
    assert_eq!(names(&db, &lucky.artists), ["Daft Punk", "Pharrell Williams"]);
    let version = version(&db, "Get Lucky");
    let placement = version.album.unwrap();
    assert_eq!((placement.disc, placement.number, version.release_year), (Some(1), Some(8), Some(2013)));
    let album = db.album(placement.album).unwrap().unwrap();
    assert_eq!((album.title.as_str(), album.artist_credit.as_str()), ("Random Access Memories", "Daft Punk"));
    assert_eq!(names(&db, &album.artists), ["Daft Punk"]);
    assert_eq!((album.year, album.disc_count), (Some(2013), Some(1)));
    assert_eq!(album.artists[0], lucky.artists[0], "один артист, а не два одноимённых");
}

/// Файлы одного альбома — один альбом; артист один на все треки.
#[test]
fn files_of_one_album_share_it_and_its_artist() {
    let tree = Tree::new(&["Music/01.mp3", "Music/02.mp3"]);
    let db = Database::open_in_memory().unwrap();
    let reader = ByName(vec![
        ("01.mp3", song("Airbag", "Radiohead", Some("OK Computer"), Some("Radiohead"))),
        ("02.mp3", song("Paranoid Android", "radiohead", Some("ok computer"), Some("Radiohead"))),
    ]);

    run(&db, &tree, &FolderConfig::default(), &reader);

    assert_eq!(db.albums_titled("OK Computer").unwrap().len(), 1);
    assert_eq!(album(&db, "Airbag"), album(&db, "Paranoid Android"));
    assert_eq!(track(&db, "Airbag").artists, track(&db, "Paranoid Android").artists);
    assert_eq!(db.artists_named("Radiohead").unwrap().len(), 1);
}

/// Без album artist альбом держится основного исполнителя: гость в
/// «A feat. B» не отрывает трек в отдельный альбом.
#[test]
fn without_an_album_artist_the_album_goes_by_the_main_artist() {
    let tree = Tree::new(&["Music/01.mp3", "Music/02.mp3"]);
    let db = Database::open_in_memory().unwrap();
    let reader = ByName(vec![
        ("01.mp3", song("Intro", "Nightcore Reality", Some("Hits"), None)),
        ("02.mp3", song("Echo", "Nightcore Reality feat. Thatcher", Some("Hits"), None)),
    ]);

    run(&db, &tree, &FolderConfig::default(), &reader);

    let hits = album(&db, "Intro").unwrap();
    assert_eq!(album(&db, "Echo").unwrap().id, hits.id);
    assert_eq!(hits.artist_credit, "Nightcore Reality");
    assert_eq!(names(&db, &hits.artists), ["Nightcore Reality"]);
}

/// Сборник не рассыпается на альбомы по одному треку (plan.md 13.2).
#[test]
fn a_compilation_stays_one_album() {
    let tree = Tree::new(&["Music/01.mp3", "Music/02.mp3", "Music/03.mp3"]);
    let db = Database::open_in_memory().unwrap();
    let mut flagged = song("One", "Alpha", Some("Now 99"), None);
    flagged.compilation = true;
    let reader = ByName(vec![
        ("01.mp3", flagged),
        ("02.mp3", song("Two", "Beta", Some("Now 99"), Some("Various Artists"))),
        ("03.mp3", song("Three", "Gamma", Some("Now 99"), Some("Various Artists"))),
    ]);

    run(&db, &tree, &FolderConfig::default(), &reader);

    let now = album(&db, "One").unwrap();
    assert_eq!(db.albums_titled("Now 99").unwrap().len(), 1);
    assert_eq!(now.artist_credit, "Various Artists");
    assert!(now.artists.is_empty(), "артиста «Various Artists» нет");
    assert!(db.artists_named("Various Artists").unwrap().is_empty());
    assert_eq!(names(&db, &track(&db, "Two").artists), ["Beta"]);
}

#[test]
fn one_title_by_two_artists_is_two_albums() {
    let tree = Tree::new(&["Music/q.mp3", "Music/a.mp3"]);
    let db = Database::open_in_memory().unwrap();
    let reader = ByName(vec![
        ("q.mp3", song("Bohemian Rhapsody", "Queen", Some("Greatest Hits"), None)),
        ("a.mp3", song("Waterloo", "ABBA", Some("Greatest Hits"), None)),
    ]);

    run(&db, &tree, &FolderConfig::default(), &reader);

    assert_eq!(db.albums_titled("Greatest Hits").unwrap().len(), 2);
}

/// Нет тега альбома — нет альбома, а не альбом по имени папки (ADR 0005).
#[test]
fn no_album_tag_no_album() {
    let tree = Tree::new(&["Music/Singles/a.mp3"]);
    let db = Database::open_in_memory().unwrap();

    run(&db, &tree, &FolderConfig::default(), &ByName(vec![("a.mp3", song("Single", "Solo", None, None))]));

    assert_eq!(album(&db, "Single"), None);
    assert!(db.albums_titled("Singles").unwrap().is_empty());
}

/// Сменились теги — трек тот же, артисты и альбом новые; старые, которым
/// больше не на что ссылаться, уходят из каталога.
#[test]
fn retagging_moves_the_track_and_prunes_leftovers() {
    let tree = Tree::new(&["Music/a.mp3"]);
    let db = Database::open_in_memory().unwrap();
    let old = ByName(vec![("a.mp3", song("Song", "Old Name", Some("Old Album"), None))]);
    run(&db, &tree, &FolderConfig::default(), &old);
    let id = track(&db, "Song").id;

    tree.write("Music/a.mp3", b"re-tagged audio");
    let new = ByName(vec![("a.mp3", song("Song", "New Name", Some("New Album"), None))]);
    run(&db, &tree, &FolderConfig::default(), &new);

    let song = track(&db, "Song");
    assert_eq!(song.id, id);
    assert_eq!(names(&db, &song.artists), ["New Name"]);
    assert_eq!(album(&db, "Song").unwrap().title, "New Album");
    assert!(db.artists_named("Old Name").unwrap().is_empty());
    assert!(db.albums_titled("Old Album").unwrap().is_empty());
}

/// Изменённый файл с битыми тегами (скажем, ещё копируется) каталог не
/// портит и перечитывается следующим сканом.
#[test]
fn an_edited_file_with_broken_tags_keeps_the_catalog() {
    let tree = Tree::new(&["Music/a.mp3"]);
    let db = Database::open_in_memory().unwrap();
    run(&db, &tree, &FolderConfig::default(), &ByName(vec![("a.mp3", song("Song", "Artist", Some("Album"), None))]));

    tree.write("Music/a.mp3", b"half-copied audio");
    let broken = run(&db, &tree, &FolderConfig::default(), &Broken);
    let again = run(&db, &tree, &FolderConfig::default(), &Broken);

    assert_eq!((broken.changed, broken.unreadable_files), (1, 1));
    assert_eq!(names(&db, &track(&db, "Song").artists), ["Artist"]);
    assert_eq!(album(&db, "Song").unwrap().title, "Album");
    assert_eq!(again.changed, 1, "без отметки скана файл перечитывается");
}

/// Формат — по содержимому, если теги прочлись; иначе — по расширению.
#[test]
fn the_format_comes_from_the_contents() {
    let tree = Tree::new(&["Music/lossless.m4a", "Music/unknown.m4a"]);
    let db = Database::open_in_memory().unwrap();
    let alac = Tags {
        title: Some("Lossless".to_owned()),
        audio: Some(AudioSpec {
            format: Format::Alac,
            bitrate: None,
            sample_rate_hz: Some(96_000),
            bit_depth: Some(24),
        }),
        ..Tags::default()
    };

    run(&db, &tree, &FolderConfig::default(), &ByName(vec![("lossless.m4a", alac)]));

    let audio = |title| db.sources_of(version(&db, title).id).unwrap()[0].audio;
    assert_eq!((audio("Lossless").format, audio("Lossless").sample_rate_hz), (Format::Alac, Some(96_000)));
    assert_eq!(audio("unknown").format, Format::Aac);
}

/// Настоящее чтение на фикстурах приложения (`tools/make_tag_fixtures.py`).
#[test]
fn real_tags_from_the_app_fixtures() {
    let tree = Tree::new(&["Music/junk.mp3"]);
    let assets = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../app/src/androidTest/assets/tags");
    for name in ["plinth-mp3.mp3", "plinth-flac.flac", "plinth-m4a.m4a", "plinth-untagged.mp3"] {
        tree.write(&format!("Music/Fixtures/{name}"), &fs::read(assets.join(name)).unwrap());
    }
    let db = Database::open_in_memory().unwrap();

    let report = run(&db, &tree, &FolderConfig::default(), &FileTags::default());

    assert_eq!((report.added, report.unreadable_files), (5, 1));
    assert_eq!(titles(&db), ["FLAC Silence", "junk", "M4A Silence", "plinth-untagged", "Тишина"]);
    let fixtures = db.albums_titled("Fixtures").unwrap();
    assert_eq!(fixtures.len(), 1);
    assert_eq!(fixtures[0].artist_credit, "Plinth Various");
    let tishina = version(&db, "Тишина").album.unwrap();
    assert_eq!((tishina.album, tishina.disc, tishina.number), (fixtures[0].id, Some(2), Some(3)));
    assert_eq!(names(&db, &track(&db, "M4A Silence").artists), ["The Plinth"]);
    assert_eq!(db.sources_of(version(&db, "FLAC Silence").id).unwrap()[0].audio.bit_depth, Some(16));
    assert!(version(&db, "Тишина").duration.is_some());
    assert_eq!(album(&db, "plinth-untagged"), None);
}
