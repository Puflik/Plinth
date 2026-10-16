//! Р9в, находка 2 ревью R1 (`docs/review/r1-review.md`, `docs/work/r9c.md`):
//! сведение перенесённых жило только внутри одного скана. Файл, который
//! скан уже завёл своим треком, к сведению не допускался, а пропавший трек с
//! лайками ждал «нового» файла, которого уже не будет, и оставался скрытым.
//!
//! Сценарии: (а) копия, потом удаление оригинала — разными сканами (так
//! переносят на SD-карту); (б) скан застал файл на новом месте недописанным —
//! теги не прочлись, и файл завёлся новым треком; (в) папку перенесли посреди
//! обхода — файл виден на двух местах за один скан, то есть то же состояние,
//! что в (а): второй скан после удаления первого места повторяет (а), отдельного
//! теста у (в) нет.
//!
//! Данные пользователя здесь — лайк или оценка в проекции журнала
//! (`save_user_data`): так их видит скан. Сквозная проверка с журналом —
//! `core/sync/tests/r9c_copy_then_delete.rs`.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod r9c_support;

use std::cell::Cell;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

use plinth_library::db::{Database, IntegrityCheck};
use plinth_library::model::{AudioSpec, Source, SourceLocation};
use plinth_library::scan::{DbAccess, FolderConfig, TagReader, Tags, scan};
use plinth_types::{Availability, CoreError, DeviceId, Format, SourceId, Timestamp, TrackId};
use r9c_support::{
    FIRST_SCAN, Volume, like, liked, rate, run, song, tagged, tick, track_at, unreadable, untagged, visible,
};

/// (а) Лайкнутую песню копируют на другое место; скан между шагами заводит
/// копию новым треком (копия рядом с живым оригиналом — новый трек, так задумано).
/// Оригинал удалён — следующий скан отдаёт файл копии треку оригинала: ID,
/// источник и дата добавления те же, лайк виден, трека копии (данных у него не
/// было) в каталоге больше нет. Повторный скан молчит.
#[test]
fn a_copy_and_a_delete_in_two_steps_keep_the_track_of_the_original() {
    let volume = Volume::new();
    volume.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let original = track_at(&db, &volume, "Music/Song.mp3").unwrap();
    like(&db, original);

    volume.copy("Music/Song.mp3", "Download/Song.mp3");
    run(&db, &[&volume], &reader, FIRST_SCAN + 1);
    let copy = track_at(&db, &volume, "Download/Song.mp3").unwrap();
    assert_ne!(copy, original, "пока оригинал на месте, копия — новый трек");

    volume.remove("Music/Song.mp3");
    let report = run(&db, &[&volume], &reader, FIRST_SCAN + 2);

    assert_eq!(track_at(&db, &volume, "Download/Song.mp3"), Some(original), "файл копии — трек оригинала");
    assert_eq!(db.track(copy).unwrap(), None, "трек копии без данных уходит из каталога");
    assert_eq!(visible(&db), [(original, Some(volume.uri("Download/Song.mp3")))]);
    assert!(liked(&db, original), "лайк на месте");
    let added_at = db.track(original).unwrap().unwrap().added_at;
    assert_eq!(added_at.as_millis(), FIRST_SCAN, "перенос — не новая музыка");
    assert_eq!((report.moved, report.missing, report.added), (1, 0, 0), "{report:?}");

    let after = run(&db, &[&volume], &reader, FIRST_SCAN + 3);
    assert_eq!((after.moved, after.missing, after.added, after.changed), (0, 0, 0, 0), "{after:?}");
    assert_eq!(visible(&db), [(original, Some(volume.uri("Download/Song.mp3")))]);
}

/// (б) Оригинал удалён, на новом месте файл недописан: теги не читаются, файл
/// заведён новым треком (страж Р1.2: недочитанный файл пропавший не забирает).
/// Копия докачалась — следующий скан читает её как изменённую, и трек оригинала
/// получает её файл, а трек недописанного файла уходит.
#[test]
fn a_file_caught_half_copied_takes_the_track_once_its_tags_read() {
    let volume = Volume::new();
    volume.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let original = track_at(&db, &volume, "Music/Song.mp3").unwrap();
    like(&db, original);

    volume.remove("Music/Song.mp3");
    volume.put("Download/Song.mp3");
    let half = run(&db, &[&volume], &unreadable("Song.mp3"), FIRST_SCAN + 1);
    let fresh = track_at(&db, &volume, "Download/Song.mp3").expect("файл с битыми тегами всё равно в каталоге");
    assert_ne!(fresh, original);
    assert_eq!((half.unreadable_files, half.added, half.moved), (1, 1, 0), "{half:?}");
    assert_eq!(visible(&db), [(fresh, Some(volume.uri("Download/Song.mp3")))]);

    volume.write("Download/Song.mp3", b"audio, and the rest of the copy");
    let done = run(&db, &[&volume], &reader, FIRST_SCAN + 2);

    assert_eq!(track_at(&db, &volume, "Download/Song.mp3"), Some(original));
    assert_eq!(db.track(fresh).unwrap(), None, "трек недописанного файла уходит");
    assert_eq!(visible(&db), [(original, Some(volume.uri("Download/Song.mp3")))]);
    assert!(liked(&db, original));
    assert_eq!(done.moved, 1, "{done:?}");
}

/// Страж к (б): файл без тегов идёт под именем файла, и недочитанный файл
/// совпадает с пропавшим безтеговым треком по ключу. Пока теги не читаются,
/// он трека не забирает; когда прочлись (тех же тегов нет, но чтение прошло) —
/// забирает.
#[test]
fn an_unreadable_file_does_not_take_a_track_even_when_the_keys_agree() {
    let volume = Volume::new();
    volume.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", untagged(180))]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let original = track_at(&db, &volume, "Music/Song.mp3").unwrap();
    like(&db, original);

    volume.remove("Music/Song.mp3");
    volume.put("Download/Song.mp3");
    let half = run(&db, &[&volume], &unreadable("Song.mp3"), FIRST_SCAN + 1);
    let fresh = track_at(&db, &volume, "Download/Song.mp3").unwrap();
    assert_ne!(fresh, original, "недочитанный файл трека с данными не забирает");
    assert_eq!(half.moved, 0, "{half:?}");
    assert_eq!(db.track(original).unwrap().map(|track| track.title), Some("Song".to_owned()));

    volume.write("Download/Song.mp3", b"audio, and the rest of the copy");
    run(&db, &[&volume], &reader, FIRST_SCAN + 2);

    assert_eq!(track_at(&db, &volume, "Download/Song.mp3"), Some(original));
    assert_eq!(db.track(fresh).unwrap(), None);
    assert!(liked(&db, original));
}

/// Страж: у пропавшего трека данных пользователя нет — отдавать нечего, файл
/// копии остаётся своим треком, пропавший скрыт, как и раньше.
#[test]
fn a_copy_keeps_its_own_track_when_the_vanished_one_has_nothing_of_the_user() {
    let volume = Volume::new();
    volume.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let original = track_at(&db, &volume, "Music/Song.mp3").unwrap();
    volume.copy("Music/Song.mp3", "Download/Song.mp3");
    run(&db, &[&volume], &reader, FIRST_SCAN + 1);
    let copy = track_at(&db, &volume, "Download/Song.mp3").unwrap();

    volume.remove("Music/Song.mp3");
    let report = run(&db, &[&volume], &reader, FIRST_SCAN + 2);

    assert_eq!(track_at(&db, &volume, "Download/Song.mp3"), Some(copy));
    assert_eq!(track_at(&db, &volume, "Music/Song.mp3"), Some(original), "пропавший — на своём пути");
    assert_eq!(visible(&db), [(copy, Some(volume.uri("Download/Song.mp3")))]);
    assert_eq!((report.moved, report.missing), (0, 1), "{report:?}");
}

/// Страж: у копии свои данные (её оценили) — её трек не трогается, данные
/// оригинала остаются на скрытом треке. Склейка данных двух треков — не эта
/// задача (журнал), см. «Замечено» в `docs/work/r9c.md`.
#[test]
fn a_copy_with_data_of_its_own_is_left_alone() {
    let volume = Volume::new();
    volume.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let original = track_at(&db, &volume, "Music/Song.mp3").unwrap();
    like(&db, original);
    volume.copy("Music/Song.mp3", "Download/Song.mp3");
    run(&db, &[&volume], &reader, FIRST_SCAN + 1);
    let copy = track_at(&db, &volume, "Download/Song.mp3").unwrap();
    rate(&db, copy);

    volume.remove("Music/Song.mp3");
    let report = run(&db, &[&volume], &reader, FIRST_SCAN + 2);

    assert_eq!(track_at(&db, &volume, "Download/Song.mp3"), Some(copy));
    assert_eq!(visible(&db), [(copy, Some(volume.uri("Download/Song.mp3")))]);
    assert!(liked(&db, original), "данные оригинала целы на скрытом треке");
    assert_eq!(db.user_data(copy).unwrap().rating.map(|rating| rating.stars()), Some(4));
    assert_eq!((report.moved, report.missing), (0, 1), "{report:?}");
}

/// Страж: карту вынули. Скану «вынули» и «перенесли» неотличимы, но двойник,
/// который давно лежит на другом томе, данных карты не забирает: трек карты
/// остаётся скрытым на своём пути и оживает вместе с картой.
#[test]
fn a_track_on_a_card_that_is_not_scanned_does_not_give_its_data_to_a_copy_elsewhere() {
    let (card, phone) = (Volume::new(), Volume::new());
    card.put("Music/Song.mp3");
    phone.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&card, &phone], &reader, FIRST_SCAN);
    let on_card = track_at(&db, &card, "Music/Song.mp3").unwrap();
    let on_phone = track_at(&db, &phone, "Music/Song.mp3").unwrap();
    assert_ne!(on_card, on_phone);
    like(&db, on_card);

    let pulled = run(&db, &[&phone], &reader, FIRST_SCAN + 1);

    assert_eq!((pulled.missing, pulled.moved), (1, 0), "{pulled:?}");
    assert_eq!(track_at(&db, &phone, "Music/Song.mp3"), Some(on_phone));
    assert_eq!(track_at(&db, &card, "Music/Song.mp3"), Some(on_card), "трек карты — на пути карты");
    assert_eq!(visible(&db), [(on_phone, Some(phone.uri("Music/Song.mp3")))]);

    let back = run(&db, &[&card, &phone], &reader, FIRST_SCAN + 2);

    assert_eq!((back.returned, back.moved), (1, 0), "{back:?}");
    let mut tracks: Vec<_> = visible(&db).into_iter().map(|(id, _)| id).collect();
    tracks.sort();
    let mut expected = vec![on_card, on_phone];
    expected.sort();
    assert_eq!(tracks, expected);
    assert!(liked(&db, on_card));
}

/// Один двойник, два пропавших трека с данными: файл достаётся тому, кому он
/// больше подходит (альбом и длительность), а не тому, кто идёт первым. Порядок
/// заведения треков обоими способами — какой бы из двух ни был старше, исход тот же.
#[test]
fn one_twin_goes_to_the_original_that_matches_it_best() {
    for first_album in ["One", "Two"] {
        let volume = Volume::new();
        let db = Database::open_in_memory().unwrap();
        let reader = tagged(vec![
            ("a.mp3", song("Intro", "Band", "One", 60)),
            ("b.mp3", song("Intro", "Band", "Two", 60)),
            ("y.mp3", song("Intro", "Band", "Two", 60)),
        ]);
        let (first, second) = if first_album == "One" {
            ("Music/One/a.mp3", "Music/Two/b.mp3")
        } else {
            ("Music/Two/b.mp3", "Music/One/a.mp3")
        };
        volume.put(first);
        run(&db, &[&volume], &reader, FIRST_SCAN);
        tick();
        volume.put(second);
        run(&db, &[&volume], &reader, FIRST_SCAN + 1);
        let one = track_at(&db, &volume, "Music/One/a.mp3").unwrap();
        let two = track_at(&db, &volume, "Music/Two/b.mp3").unwrap();
        like(&db, one);
        like(&db, two);
        volume.put("Download/y.mp3");
        run(&db, &[&volume], &reader, FIRST_SCAN + 2);
        let twin = track_at(&db, &volume, "Download/y.mp3").unwrap();
        assert!(twin != one && twin != two);

        volume.remove("Music/One/a.mp3");
        volume.remove("Music/Two/b.mp3");
        let report = run(&db, &[&volume], &reader, FIRST_SCAN + 3);

        assert_eq!(
            track_at(&db, &volume, "Download/y.mp3"),
            Some(two),
            "альбом совпал — файл этого трека ({first_album})"
        );
        assert_eq!(db.track(twin).unwrap(), None);
        assert_eq!(track_at(&db, &volume, "Music/One/a.mp3"), Some(one), "второй остаётся скрытым со своими данными");
        assert!(liked(&db, one) && liked(&db, two));
        assert_eq!((report.moved, report.missing), (1, 1), "{report:?}");
    }
}

/// Доступ к базе, который считает заходы: каждый заход — замок ядра на время работы.
struct Counting<'a> {
    db: &'a Database,
    runs: Cell<usize>,
}

impl DbAccess for Counting<'_> {
    fn run(&self, work: &mut dyn FnMut(&Database) -> Result<(), CoreError>) -> Result<(), CoreError> {
        self.runs.set(self.runs.get() + 1);
        work(self.db)
    }
}

/// Сколько заходов в базу делает скан, в котором ничего не изменилось.
fn quiet_scan_runs(db: &Database, volume: &Volume, reader: &r9c_support::ByName, at: i64) -> usize {
    let counting = Counting { db, runs: Cell::new(0) };
    let roots: Vec<PathBuf> = vec![volume.0.clone()];
    let report =
        scan(&counting, &roots, &FolderConfig::default(), reader, &mut |_| true, Timestamp::from_millis(at)).unwrap();
    assert_eq!((report.added, report.changed, report.missing, report.moved), (0, 0, 0, 0), "{report:?}");
    counting.runs.get()
}

/// Инвариант: тихий скан не ищет двойников. Пропавший навсегда трек с лайком, которому
/// двойника нет, не должен стоить базе лишних заходов на каждом скане: столько же,
/// сколько в каталоге, где ничего не пропадало.
#[test]
fn a_quiet_scan_does_not_look_for_twins() {
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    let (control, volume) = (Volume::new(), Volume::new());
    let (control_db, db) = (Database::open_in_memory().unwrap(), Database::open_in_memory().unwrap());
    control.put("Music/Song.mp3");
    run(&control_db, &[&control], &reader, FIRST_SCAN);
    volume.put("Music/Song.mp3");
    volume.put("Music/Kept.mp3");
    let reader_both = tagged(vec![
        ("Song.mp3", song("Song", "Band", "Album", 180)),
        ("Kept.mp3", song("Kept", "Band", "Album", 200)),
    ]);
    run(&db, &[&volume], &reader_both, FIRST_SCAN);
    like(&db, track_at(&db, &volume, "Music/Song.mp3").unwrap());
    volume.remove("Music/Song.mp3");
    run(&db, &[&volume], &reader_both, FIRST_SCAN + 1);
    run(&db, &[&volume], &reader_both, FIRST_SCAN + 2);

    let with_hidden = quiet_scan_runs(&db, &volume, &reader_both, FIRST_SCAN + 3);
    let without = quiet_scan_runs(&control_db, &control, &reader, FIRST_SCAN + 3);

    assert_eq!(with_hidden, without, "скрытый трек с лайком не должен делать тихий скан дороже");
}

/// Каталог базы-файла; стирается в `Drop`.
struct Dir(PathBuf);

impl Dir {
    fn new() -> Self {
        let dir = std::env::temp_dir().join(format!("plinth-r9c-db-{}", DeviceId::new()));
        fs::create_dir_all(&dir).unwrap();
        Self(dir)
    }

    fn db(&self) -> PathBuf {
        self.0.join("library.db")
    }
}

impl Drop for Dir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// Читатель, который в миг чтения файла `trigger` ставит лайк `target` — с другого
/// соединения с той же базой: человек лайкнул копию, пока скан читал её теги, между
/// выбором пары и записью.
struct LikeWhileReading {
    inner: r9c_support::ByName,
    db: PathBuf,
    trigger: &'static str,
    target: Mutex<Option<TrackId>>,
}

impl TagReader for LikeWhileReading {
    fn read(&self, path: &Path) -> Result<Tags, CoreError> {
        if path.file_name().is_some_and(|name| name == self.trigger)
            && let Some(track) = self.target.lock().unwrap().take()
        {
            like(&Database::open(&self.db, IntegrityCheck::Skip).unwrap().db, track);
        }
        self.inner.read(path)
    }
}

/// Гонка: двойник на момент выбора пары чист, а к записи у него уже есть данные. Проверка
/// внутри транзакции оставляет его трек: уйти из каталога может только трек без данных.
#[test]
fn a_like_that_lands_while_the_twin_is_being_read_keeps_the_twin() {
    let (volume, dir) = (Volume::new(), Dir::new());
    volume.put("Music/Song.mp3");
    let db = Database::open(&dir.db(), IntegrityCheck::Skip).unwrap().db;
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let original = track_at(&db, &volume, "Music/Song.mp3").unwrap();
    like(&db, original);
    volume.copy("Music/Song.mp3", "Download/Song.mp3");
    run(&db, &[&volume], &reader, FIRST_SCAN + 1);
    let copy = track_at(&db, &volume, "Download/Song.mp3").unwrap();
    volume.remove("Music/Song.mp3");

    let racing = LikeWhileReading {
        inner: tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]),
        db: dir.db(),
        trigger: "Song.mp3",
        target: Mutex::new(Some(copy)),
    };
    let report = run(&db, &[&volume], &racing, FIRST_SCAN + 2);

    assert!(racing.target.lock().unwrap().is_none(), "лайк не поставлен: тест ничего не проверил");
    assert!(db.track(copy).unwrap().is_some(), "трек с лайком из каталога не уходит");
    assert_eq!(track_at(&db, &volume, "Download/Song.mp3"), Some(copy));
    assert!(liked(&db, copy) && liked(&db, original), "данные обоих целы");
    assert_eq!(report.moved, 0, "{report:?}");
}

/// У трека двойника есть второй источник (скажем, склеенная версия): его трек не уходит,
/// чтобы не унести заодно и чужой файл.
#[test]
fn a_twin_with_a_second_source_is_not_deleted() {
    let volume = Volume::new();
    volume.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&volume], &reader, FIRST_SCAN);
    let original = track_at(&db, &volume, "Music/Song.mp3").unwrap();
    like(&db, original);
    volume.copy("Music/Song.mp3", "Download/Song.mp3");
    run(&db, &[&volume], &reader, FIRST_SCAN + 1);
    let copy = track_at(&db, &volume, "Download/Song.mp3").unwrap();
    let version = db.known_local_files().unwrap()[&volume.uri("Download/Song.mp3")].version;
    let elsewhere = Source {
        id: SourceId::new(),
        version,
        location: SourceLocation::Local { uri: "/elsewhere/Song.mp3".to_owned() },
        audio: AudioSpec { format: Format::Mp3, bitrate: None, sample_rate_hz: None, bit_depth: None },
        availability: Availability::Unavailable,
        last_checked_at: None,
    };
    db.save_source(&elsewhere).unwrap();
    volume.remove("Music/Song.mp3");

    let report = run(&db, &[&volume], &reader, FIRST_SCAN + 2);

    assert_eq!(track_at(&db, &volume, "Download/Song.mp3"), Some(copy), "трек с двумя источниками двойником не бывает");
    assert!(liked(&db, original));
    assert_eq!(db.sources_of(version).unwrap().len(), 2);
    assert_eq!(report.moved, 0, "{report:?}");
}

/// Читатель, у которого чтение файла `name` падает один раз — после `armed.store(true)` — и
/// удаётся потом: файл дописался между чтением тегов скана и этапом двойников.
struct Flaky {
    inner: r9c_support::ByName,
    name: &'static str,
    armed: AtomicBool,
}

impl TagReader for Flaky {
    fn read(&self, path: &Path) -> Result<Tags, CoreError> {
        if path.file_name().is_some_and(|name| name == self.name) && self.armed.swap(false, Ordering::SeqCst) {
            return Err(CoreError::parse("not an MP3 stream"));
        }
        self.inner.read(path)
    }
}

/// Файл, у которого теги не прочлись в этом скане, двойником в нём не бывает, даже если
/// к этапу двойников он уже читается: решение по заглушке из имени файла — не решение.
#[test]
fn a_file_that_became_readable_during_the_scan_is_not_a_twin_in_it() {
    let volume = Volume::new();
    volume.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let flaky =
        Flaky { inner: tagged(vec![("Song.mp3", untagged(180))]), name: "Song.mp3", armed: AtomicBool::new(false) };
    run(&db, &[&volume], &flaky, FIRST_SCAN);
    let original = track_at(&db, &volume, "Music/Song.mp3").unwrap();
    like(&db, original);

    volume.remove("Music/Song.mp3");
    volume.put("Download/Song.mp3");
    flaky.armed.store(true, Ordering::SeqCst);
    let report = run(&db, &[&volume], &flaky, FIRST_SCAN + 1);

    assert!(!flaky.armed.load(Ordering::SeqCst), "чтение не падало: тест ничего не проверил");
    let fresh = track_at(&db, &volume, "Download/Song.mp3").unwrap();
    assert_ne!(fresh, original, "недочитанный файл трека с данными не забирает");
    assert_eq!((report.unreadable_files, report.moved, report.added), (1, 0, 1), "{report:?}");
    assert!(liked(&db, original));
}
