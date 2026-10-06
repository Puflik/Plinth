//! Р1.2 (находка 2 ревью v0.2): файл перенесли, переименовали или он
//! оказался на карте с другим именем тома. Скан узнаёт в новом файле
//! пропавший — по паспорту, как перепривязка узнаёт трек журнала, — и трек
//! остаётся прежним: тот же ID, тот же источник, та же дата добавления.
//! Лайки, плейлисты и история в журнале — по ID трека, поэтому остаются на
//! нём без единой записи в журнал.
//!
//! Новый файл, который на пропавший не похож, — новый трек; файл, который
//! никуда не пропадал, своего трека не отдаёт.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use plinth_library::db::Database;
use plinth_library::db::query::TrackSort;
use plinth_library::scan::{ArtistSplit, FolderConfig, RawTags, TagReader, Tags, normalized, scan};
use plinth_types::{CoreError, DeviceId, Timestamp, TrackId};

const FIRST_SCAN: i64 = 1_790_000_000_000;
const LATER: i64 = FIRST_SCAN + 86_400_000;

/// Том во временной папке — корень, который скан получает от Kotlin.
struct Volume(PathBuf);

impl Volume {
    fn new() -> Self {
        let volume = Self(std::env::temp_dir().join(format!("plinth-r1-2-{}", DeviceId::new())));
        fs::create_dir_all(&volume.0).unwrap();
        volume
    }

    fn put(&self, file: &str) {
        let path = self.path(file);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, b"audio").unwrap();
    }

    /// Переносит файл, как файловый менеджер: папка назначения создаётся.
    fn shift(&self, from: &str, to: &str) {
        let target = self.path(to);
        fs::create_dir_all(target.parent().unwrap()).unwrap();
        fs::rename(self.path(from), target).unwrap();
    }

    /// Путь по сегментам — с теми же разделителями, что у обхода.
    fn path(&self, file: &str) -> PathBuf {
        file.split('/').fold(self.0.clone(), |dir, segment| dir.join(segment))
    }

    /// Так файл лежит в каталоге (`source.local_uri`).
    fn uri(&self, file: &str) -> String {
        self.path(file).to_string_lossy().into_owned()
    }
}

impl Drop for Volume {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// Теги по имени файла; имена из `broken` не читаются. Имени нет ни там, ни
/// там — ошибка самого теста.
struct ByName {
    tags: Vec<(&'static str, Tags)>,
    broken: Vec<&'static str>,
}

fn tagged(tags: Vec<(&'static str, Tags)>) -> ByName {
    ByName { tags, broken: Vec::new() }
}

impl TagReader for ByName {
    fn read(&self, path: &Path) -> Result<Tags, CoreError> {
        let name = path.file_name().unwrap().to_string_lossy();
        if self.broken.iter().any(|broken| *broken == name) {
            return Err(CoreError::parse("not an MP3 stream"));
        }
        let found = self.tags.iter().find(|(file, _)| *file == name);
        Ok(found.map(|(_, tags)| tags.clone()).expect("the test gives no tags for this file"))
    }
}

/// Теги, сведённые так же, как их сведёт настоящее чтение.
fn song(title: &str, artist: &str, album: &str, seconds: u64) -> Tags {
    let raw = RawTags {
        title: Some(title.to_owned()),
        artist: vec![artist.to_owned()],
        album: Some(album.to_owned()),
        duration: Some(Duration::from_secs(seconds)),
        ..RawTags::default()
    };
    normalized(raw, &ArtistSplit::default())
}

/// Скан томов `volumes` по папкам по умолчанию — `Music` и `Download`.
fn run(db: &Database, volumes: &[&Volume], reader: &dyn TagReader, at: i64) {
    let roots: Vec<PathBuf> = volumes.iter().map(|volume| volume.0.clone()).collect();
    scan(db, &roots, &FolderConfig::default(), reader, &mut |_| true, Timestamp::from_millis(at)).unwrap();
}

/// Трек файла `file` — в том числе пропавшего; файла в каталоге нет — `None`.
fn track_at(db: &Database, volume: &Volume, file: &str) -> Option<TrackId> {
    db.track_at(&volume.uri(file)).unwrap()
}

/// Видимые треки: ID и файл, из которого трек играет.
fn visible(db: &Database) -> Vec<(TrackId, Option<String>)> {
    db.track_list(TrackSort::Title, None).unwrap().into_iter().map(|row| (row.id, row.uri)).collect()
}

/// Сценарий ревью: файл перенесли из «Download» в «Music». Трек тот же — ID,
/// источник (на него ссылается история), дата добавления; в списке он один,
/// с новым путём и папкой, а старого пути каталог больше не знает.
#[test]
fn a_file_moved_to_another_folder_keeps_its_track() {
    let volume = Volume::new();
    volume.put("Download/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let track = track_at(&db, &volume, "Download/Song.mp3").unwrap();
    let source = db.known_local_files().unwrap()[&volume.uri("Download/Song.mp3")].source;

    volume.shift("Download/Song.mp3", "Music/Song.mp3");
    run(&db, &[&volume], &reader, LATER);

    assert_eq!(track_at(&db, &volume, "Music/Song.mp3"), Some(track), "новый файл — прежний трек");
    assert_eq!(track_at(&db, &volume, "Download/Song.mp3"), None, "старого пути каталог больше не знает");
    let rows = db.track_list(TrackSort::Title, None).unwrap();
    assert_eq!(rows.len(), 1, "{rows:?}");
    assert_eq!(rows[0].id, track);
    assert_eq!(rows[0].uri.as_deref(), Some(volume.uri("Music/Song.mp3").as_str()));
    assert_eq!(rows[0].folder.as_deref(), Some("Music/"));
    assert_eq!(db.known_local_files().unwrap()[&volume.uri("Music/Song.mp3")].source, source);
    let added_at = db.track(track).unwrap().unwrap().added_at;
    assert_eq!(added_at, Timestamp::from_millis(FIRST_SCAN), "перенос — не новая музыка");

    run(&db, &[&volume], &reader, LATER + 1);
    assert_eq!(visible(&db), [(track, Some(volume.uri("Music/Song.mp3")))], "следующий скан ничего не меняет");
}

/// Переименовали папку альбома и один файл в ней — оба трека прежние.
#[test]
fn a_renamed_file_and_a_renamed_folder_keep_their_tracks() {
    let volume = Volume::new();
    volume.put("Music/Band/01.mp3");
    volume.put("Music/Band/02.mp3");
    let db = Database::open_in_memory().unwrap();
    let (first, second) = (song("Song", "Band", "Album", 180), song("Other Song", "Band", "Album", 200));
    let reader = tagged(vec![("01.mp3", first.clone()), ("01 Song.mp3", first), ("02.mp3", second)]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let one = track_at(&db, &volume, "Music/Band/01.mp3").unwrap();
    let two = track_at(&db, &volume, "Music/Band/02.mp3").unwrap();

    volume.shift("Music/Band/01.mp3", "Music/Band (1999)/01 Song.mp3");
    volume.shift("Music/Band/02.mp3", "Music/Band (1999)/02.mp3");
    run(&db, &[&volume], &reader, LATER);

    assert_eq!(track_at(&db, &volume, "Music/Band (1999)/01 Song.mp3"), Some(one));
    assert_eq!(track_at(&db, &volume, "Music/Band (1999)/02.mp3"), Some(two));
    let mut tracks: Vec<TrackId> = visible(&db).into_iter().map(|(id, _)| id).collect();
    tracks.sort();
    let mut expected = vec![one, two];
    expected.sort();
    assert_eq!(tracks, expected);
}

/// Карту заменили или переформатировали: у тома другое имя, и Kotlin отдаёт
/// скану только новый корень. Тот же файл на новой карте — прежний трек.
#[test]
fn a_file_on_a_card_with_a_new_volume_name_keeps_its_track() {
    let (old_card, new_card) = (Volume::new(), Volume::new());
    old_card.put("Music/Song.mp3");
    new_card.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&old_card], &reader, FIRST_SCAN);
    let track = track_at(&db, &old_card, "Music/Song.mp3").unwrap();

    run(&db, &[&new_card], &reader, LATER);

    assert_eq!(track_at(&db, &new_card, "Music/Song.mp3"), Some(track));
    assert_eq!(track_at(&db, &old_card, "Music/Song.mp3"), None);
    assert_eq!(visible(&db), [(track, Some(new_card.uri("Music/Song.mp3")))]);
}

/// Файл вынесли из папок библиотеки — трек скрыт; через скан его вернули в
/// другую папку — трек прежний и снова виден. Пропавший давно — тоже кандидат.
#[test]
fn a_file_gone_since_an_earlier_scan_takes_its_track_back() {
    let volume = Volume::new();
    volume.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let track = track_at(&db, &volume, "Music/Song.mp3").unwrap();

    volume.shift("Music/Song.mp3", "Other/Song.mp3");
    run(&db, &[&volume], &reader, LATER);
    let hidden = visible(&db).is_empty();
    volume.shift("Other/Song.mp3", "Music/New/Song.mp3");
    run(&db, &[&volume], &reader, LATER + 1);

    assert!(hidden, "вне папок библиотеки файл пропал, трек скрыт");
    assert_eq!(track_at(&db, &volume, "Music/New/Song.mp3"), Some(track));
    assert_eq!(visible(&db), [(track, Some(volume.uri("Music/New/Song.mp3")))]);
}

/// Одна песня с альбома и с концерта: название и исполнитель одни,
/// длительности в пределах двух секунд. Оба файла перенесли и переименовали
/// — каждый берёт свой трек по альбому, а не первый попавшийся. Новые имена
/// идут в обратном порядке, чтобы «первый попавшийся» ошибся.
#[test]
fn each_moved_file_takes_the_track_that_matches_it_best() {
    let volume = Volume::new();
    volume.put("Music/First/first-intro.mp3");
    volume.put("Music/Second/second-intro.mp3");
    let db = Database::open_in_memory().unwrap();
    let (studio, live) = (song("Intro", "Band", "First", 60), song("Intro", "Band", "Second", 61));
    let reader = tagged(vec![
        ("first-intro.mp3", studio.clone()),
        ("second-intro.mp3", live.clone()),
        ("b.mp3", studio),
        ("a.mp3", live),
    ]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let first = track_at(&db, &volume, "Music/First/first-intro.mp3").unwrap();
    let second = track_at(&db, &volume, "Music/Second/second-intro.mp3").unwrap();

    volume.shift("Music/First/first-intro.mp3", "Music/Intros/b.mp3");
    volume.shift("Music/Second/second-intro.mp3", "Music/Intros/a.mp3");
    run(&db, &[&volume], &reader, LATER);

    assert_eq!(track_at(&db, &volume, "Music/Intros/a.mp3"), Some(second), "концертная — по альбому");
    assert_eq!(track_at(&db, &volume, "Music/Intros/b.mp3"), Some(first), "студийная — по альбому");
}

/// Страж: копия рядом с файлом, который никуда не делся, — новый трек;
/// исходный трек остаётся на своём файле.
#[test]
fn a_copy_of_a_file_that_is_still_there_is_a_new_track() {
    let volume = Volume::new();
    volume.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let track = track_at(&db, &volume, "Music/Song.mp3").unwrap();

    fs::create_dir_all(volume.path("Download")).unwrap();
    fs::copy(volume.path("Music/Song.mp3"), volume.path("Download/Song.mp3")).unwrap();
    run(&db, &[&volume], &reader, LATER);

    assert_eq!(track_at(&db, &volume, "Music/Song.mp3"), Some(track));
    let copy = track_at(&db, &volume, "Download/Song.mp3").unwrap();
    assert_ne!(copy, track);
    assert_eq!(visible(&db).len(), 2);
}

/// Страж: файл пропал, а новые на него не похожи — у одного другая
/// длительность (другая запись), у другого другое название. Оба — новые
/// треки, пропавший остаётся скрытым на своём пути.
#[test]
fn new_files_unlike_the_gone_one_are_new_tracks() {
    let volume = Volume::new();
    volume.put("Download/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![
        ("Song.mp3", song("Song", "Band", "Album", 180)),
        ("Song (extended).mp3", song("Song", "Band", "Album", 240)),
        ("Another.mp3", song("Another", "Band", "Album", 180)),
    ]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let track = track_at(&db, &volume, "Download/Song.mp3").unwrap();

    volume.shift("Download/Song.mp3", "Other/Song.mp3");
    volume.put("Music/Song (extended).mp3");
    volume.put("Music/Another.mp3");
    run(&db, &[&volume], &reader, LATER);

    assert_eq!(track_at(&db, &volume, "Download/Song.mp3"), Some(track), "пропавший — на своём пути");
    let tracks: Vec<TrackId> = visible(&db).into_iter().map(|(id, _)| id).collect();
    assert_eq!(tracks.len(), 2);
    assert!(!tracks.contains(&track), "{tracks:?}");
}

/// Страж: теги нового файла не прочлись (скажем, он ещё копируется) — он
/// заводится под именем файла новым треком, а не забирает пропавший, даже
/// если тот назывался так же и был без тегов.
#[test]
fn a_file_whose_tags_do_not_read_does_not_take_a_gone_track() {
    let volume = Volume::new();
    volume.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let untagged = Tags { duration: Some(Duration::from_secs(180)), ..Tags::default() };
    let reader = ByName { tags: vec![("Song.mp3", untagged)], broken: Vec::new() };
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let track = track_at(&db, &volume, "Music/Song.mp3").unwrap();

    volume.shift("Music/Song.mp3", "Other/Song.mp3");
    volume.put("Download/Song.mp3");
    let broken = ByName { tags: Vec::new(), broken: vec!["Song.mp3"] };
    run(&db, &[&volume], &broken, LATER);

    let fresh = track_at(&db, &volume, "Download/Song.mp3").expect("файл с битыми тегами всё равно в каталоге");
    assert_ne!(fresh, track);
    assert_eq!(track_at(&db, &volume, "Music/Song.mp3"), Some(track));
    assert_eq!(visible(&db), [(fresh, Some(volume.uri("Download/Song.mp3")))]);
}
