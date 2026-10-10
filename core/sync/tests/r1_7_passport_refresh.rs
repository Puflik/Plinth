//! Р1.7 (`docs/work/r1-7.md`, находка 7 ревью v0.2): теги поправили вне
//! приложения, скан обновил их в каталоге (ID прежние), а паспорт в журнале
//! остался старым — и после переустановки перепривязка не узнаёт трек.
//! `describe_changed`, которую ядро зовёт после скана, приводит паспорта
//! треков с данными к каталогу.
//!
//! Скан здесь настоящий, файлы настоящие. Теги лежат в самом файле
//! (`название|исполнитель|альбом|секунды`); правка тегов — перезапись файла.
//!
//! Новый API здесь — `plinth_sync::journal::describe_changed`; до его
//! появления файл не собирается.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;

use std::cell::Cell;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use common::{Scratch, play};
use plinth_library::db::Database;
use plinth_library::model::{AudioSpec, OnlineSource, OnlineTrack};
use plinth_library::scan::{ArtistSplit, FolderConfig, RawTags, ScanReport, TagReader, Tags, normalized, scan};
use plinth_sync::journal::{Journal, Op, catch_up, describe_changed, record_and_project, relink};
use plinth_types::{CoreError, DeviceId, Format, ProviderId, Timestamp, TrackId};

const SONG: &str = "Song|Band|Debut|180";

/// Читает теги из содержимого файла; лишние поля за четвёртым не читаются.
struct Embedded;

impl TagReader for Embedded {
    fn read(&self, path: &Path) -> Result<Tags, CoreError> {
        let text = fs::read_to_string(path).map_err(|error| CoreError::parse(error.to_string()))?;
        let mut fields = text.split('|');
        let mut next = || fields.next().unwrap_or_default().trim().to_owned();
        let (title, artist, album, seconds) = (next(), next(), next(), next());
        let raw = RawTags {
            title: Some(title),
            artist: vec![artist],
            album: Some(album).filter(|album| !album.is_empty()),
            duration: seconds.parse().ok().map(Duration::from_secs),
            ..RawTags::default()
        };
        Ok(normalized(raw, &ArtistSplit::default()))
    }
}

/// Папка с музыкой во временном каталоге; стирается в `Drop`.
struct Tree {
    root: Scratch,
    /// Сколько раз файлы переписывали: каждая правка длиннее прежней, чтобы скан
    /// увидел её по размеру, а не только по времени.
    revision: Cell<usize>,
}

impl Tree {
    fn new() -> Self {
        Self { root: Scratch::new(), revision: Cell::new(0) }
    }

    fn path(&self, file: &str) -> PathBuf {
        file.split('/').fold(self.root.0.clone(), |dir, segment| dir.join(segment))
    }

    /// Кладёт файл; содержимое — его теги.
    fn put(&self, file: &str, tags: &str) {
        let path = self.path(file);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, tags).unwrap();
    }

    /// Теги файла поправили вне приложения: тот же путь, другое содержимое.
    fn retag(&self, file: &str, tags: &str) {
        self.revision.set(self.revision.get() + 1);
        self.put(file, &format!("{tags}{}", "|".repeat(self.revision.get())));
    }
}

fn rescan(db: &Database, tree: &Tree) -> ScanReport {
    let roots = std::slice::from_ref(&tree.root.0);
    scan(db, roots, &FolderConfig::default(), &Embedded, &mut |_| true, Timestamp::now()).unwrap()
}

fn track_at(db: &Database, tree: &Tree, file: &str) -> TrackId {
    db.known_local_files().unwrap()[&tree.path(file).to_string_lossy().into_owned()].track
}

fn liked_titles(db: &Database) -> Vec<String> {
    let mut titles: Vec<String> = db.liked_tracks().unwrap().into_iter().map(|row| row.title).collect();
    titles.sort();
    titles
}

fn do_op(journal: &mut Journal, db: &Database, op: Op) {
    record_and_project(journal, db, &op).unwrap();
}

/// Журнал и каталог с одним лайкнутым файлом `Song.mp3`.
struct Library {
    tree: Tree,
    _dir: Scratch,
    db: Database,
    journal: Journal,
    song: TrackId,
}

fn library() -> Library {
    let (tree, dir) = (Tree::new(), Scratch::new());
    let db = Database::open_in_memory().unwrap();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    tree.put("Music/Song.mp3", SONG);
    rescan(&db, &tree);
    let song = track_at(&db, &tree, "Music/Song.mp3");
    do_op(&mut journal, &db, Op::Like { track: song });
    Library { tree, _dir: dir, db, journal, song }
}

/// Каталог и паспорт трека совпали.
fn fresh(library: &Library, track: TrackId) -> bool {
    library.journal.passport(track) == library.db.track_passport(track).unwrap()
}

/// Находка 7: название и длительность поправили вне приложения. Скан
/// подхватил, паспорт в журнале нет — пока его не обновят; обновление —
/// одна правка журнала, повторное ничего не пишет.
#[test]
fn a_retagged_track_gets_a_fresh_passport() {
    let mut lib = library();
    assert!(fresh(&lib, lib.song));

    lib.tree.retag("Music/Song.mp3", "Song (Remastered)|Band|Debut|181");
    let report = rescan(&lib.db, &lib.tree);

    assert_eq!(report.changed, 1);
    assert_eq!(track_at(&lib.db, &lib.tree, "Music/Song.mp3"), lib.song, "ID прежний, теги новые");
    assert!(!fresh(&lib, lib.song), "до обновления паспорт в журнале старый");
    // Обновление опирается на состояние, а не на итог последнего скана: оборвали
    // приложение между сканом и обновлением — следующий скан, где менять уже нечего,
    // доводит дело до конца.
    assert_eq!(rescan(&lib.db, &lib.tree).changed, 0);
    let before = lib.journal.mark();
    assert_eq!(describe_changed(&mut lib.journal, &lib.db).unwrap(), 1);

    assert!(fresh(&lib, lib.song));
    assert_eq!(lib.journal.passport(lib.song).unwrap().title, "Song (Remastered)");
    assert_eq!(lib.journal.mark().seq, before.seq + 1, "одна правка журнала");
    assert_eq!(catch_up(&lib.journal, &lib.db).unwrap(), plinth_sync::journal::CatchUp::UpToDate);
    let after = lib.journal.mark();
    assert_eq!(describe_changed(&mut lib.journal, &lib.db).unwrap(), 0, "повтор ничего не меняет");
    assert_eq!(lib.journal.mark(), after);
}

/// Ради чего всё: теги поправили, переустановили — трек находится по новому
/// паспорту. Без обновления (`refresh = false`) та же переустановка трека не
/// находит — это и есть находка 7.
fn reinstall_after_a_retag(refresh: bool) -> (usize, Vec<String>) {
    let mut lib = library();
    lib.tree.retag("Music/Song.mp3", "Song (Remastered)|Band|Debut|181");
    rescan(&lib.db, &lib.tree);
    if refresh {
        describe_changed(&mut lib.journal, &lib.db).unwrap();
    }

    // Переустановка: пустая база, журнал прошлой установки влит в новый, скан тех же файлов.
    let dir = Scratch::new();
    let db = Database::open_in_memory().unwrap();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    rescan(&db, &lib.tree);
    assert_ne!(track_at(&db, &lib.tree, "Music/Song.mp3"), lib.song, "скан выдал новый ID");
    journal.merge(&lib.journal.updates_since(&journal.state_vector()).unwrap()).unwrap();
    catch_up(&journal, &db).unwrap();
    let relinked = relink(&mut journal, &db).unwrap();
    (relinked, liked_titles(&db))
}

#[test]
fn a_reinstall_after_a_retag_finds_the_track_only_with_a_fresh_passport() {
    assert_eq!(reinstall_after_a_retag(false), (0, Vec::<String>::new()), "старый паспорт трек не находит");
    assert_eq!(reinstall_after_a_retag(true), (1, vec!["Song (Remastered)".to_owned()]));
}

/// Обновляются только описание и только у треков с данными. У трека без
/// данных паспорта не заводят, у трека, с которого данные сняли, не
/// обновляют: журналу незачем помнить теги того, что человеку не нужно.
#[test]
fn only_tracks_with_data_are_refreshed() {
    let mut lib = library();
    lib.tree.put("Music/Gone.mp3", "Gone|Band|Debut|200");
    lib.tree.put("Music/Never.mp3", "Never|Band|Debut|210");
    rescan(&lib.db, &lib.tree);
    let (gone, never) =
        (track_at(&lib.db, &lib.tree, "Music/Gone.mp3"), track_at(&lib.db, &lib.tree, "Music/Never.mp3"));
    do_op(&mut lib.journal, &lib.db, Op::Like { track: gone });
    do_op(&mut lib.journal, &lib.db, Op::Unlike { track: gone });
    assert!(lib.journal.passport(gone).is_some() && lib.journal.passport(never).is_none());

    lib.tree.retag("Music/Song.mp3", "Song (Remastered)|Band|Debut|180");
    lib.tree.retag("Music/Gone.mp3", "Gone (Remastered)|Band|Debut|200");
    lib.tree.retag("Music/Never.mp3", "Never (Remastered)|Band|Debut|210");
    assert_eq!(rescan(&lib.db, &lib.tree).changed, 3);

    assert_eq!(describe_changed(&mut lib.journal, &lib.db).unwrap(), 1);
    assert!(fresh(&lib, lib.song));
    assert_eq!(lib.journal.passport(gone).unwrap().title, "Gone", "данных нет — паспорт не трогают");
    assert_eq!(lib.journal.passport(never), None, "данных не было — паспорт не заводят");
}

/// Альбом и длительность — тоже описание; две правки — одна правка журнала.
#[test]
fn album_and_duration_changes_are_refreshed_in_one_edit() {
    let mut lib = library();
    lib.tree.put("Music/Other.mp3", "Other|Band|Debut|200");
    rescan(&lib.db, &lib.tree);
    let other = track_at(&lib.db, &lib.tree, "Music/Other.mp3");
    do_op(&mut lib.journal, &lib.db, Op::Like { track: other });

    lib.tree.retag("Music/Song.mp3", "Song|Band|Greatest Hits|180");
    lib.tree.retag("Music/Other.mp3", "Other|Band|Debut|250");
    assert_eq!(rescan(&lib.db, &lib.tree).changed, 2);
    let before = lib.journal.mark();

    assert_eq!(describe_changed(&mut lib.journal, &lib.db).unwrap(), 2);

    assert!(fresh(&lib, lib.song) && fresh(&lib, other));
    assert_eq!(lib.journal.passport(lib.song).unwrap().album.as_deref(), Some("Greatest Hits"));
    assert_eq!(lib.journal.passport(other).unwrap().duration, Some(Duration::from_secs(250)));
    assert_eq!(lib.journal.mark().seq, before.seq + 1);
}

/// Перезапись файла без смены описания (поправили чужой тег) журнал не трогает.
#[test]
fn a_rewrite_that_changes_no_description_writes_nothing() {
    let mut lib = library();
    lib.tree.retag("Music/Song.mp3", SONG);
    assert_eq!(rescan(&lib.db, &lib.tree).changed, 1);
    let before = lib.journal.mark();

    assert_eq!(describe_changed(&mut lib.journal, &lib.db).unwrap(), 0);

    assert_eq!(lib.journal.mark(), before);
}

fn oh_doctor() -> OnlineTrack {
    OnlineTrack {
        title: "OH DOCTOR".to_owned(),
        artist: Some("H. Pearl".to_owned()),
        album: Some("OH DOCTOR".to_owned()),
        number: Some(1),
        year: None,
        duration: Some(Duration::from_millis(127_450)),
        mbid: None,
        sources: vec![OnlineSource {
            provider: ProviderId::new("archive.org").unwrap(),
            external_id: "78_oh/a.mp3".to_owned(),
            audio: AudioSpec { format: Format::Mp3, bitrate: None, sample_rate_hz: None, bit_depth: None },
        }],
    }
}

/// Паспорт сетевого трека несёт его варианты у провайдера, а каталог отдаёт
/// паспорта списком без них. Описание то же — значит, и обновлять нечего: ни
/// сейчас, ни на каждом скане.
#[test]
fn a_provider_track_is_not_rewritten_on_every_call() {
    let mut lib = library();
    let online = lib.db.add_online_track(&oh_doctor(), Timestamp::from_millis(1)).unwrap();
    do_op(&mut lib.journal, &lib.db, Op::Like { track: online });
    assert_eq!(lib.journal.passport(online).unwrap().sources, oh_doctor().sources);
    let before = lib.journal.mark();

    assert_eq!(describe_changed(&mut lib.journal, &lib.db).unwrap(), 0);
    assert_eq!(describe_changed(&mut lib.journal, &lib.db).unwrap(), 0);

    assert_eq!(lib.journal.mark(), before);
    assert_eq!(lib.journal.passport(online).unwrap().sources, oh_doctor().sources);
}

/// Трека, на который есть данные, нет в каталоге (переустановка, скан ещё не
/// был): его паспорт не трогают и это не ошибка — он нужен перепривязке как есть.
#[test]
fn a_track_missing_from_the_catalog_keeps_its_passport() {
    let mut lib = library();
    let lost = TrackId::new();
    let passport = plinth_library::model::TrackPassport {
        track: lost,
        title: "Lost".to_owned(),
        artist: "Band".to_owned(),
        album: None,
        duration: None,
        mbid: None,
        sources: Vec::new(),
    };
    lib.journal.record_all(&[Op::Describe(passport.clone()), Op::Like { track: lost }]).unwrap();
    catch_up(&lib.journal, &lib.db).unwrap();
    let before = lib.journal.mark();

    assert_eq!(describe_changed(&mut lib.journal, &lib.db).unwrap(), 0);

    assert_eq!(lib.journal.passport(lost), Some(passport));
    assert_eq!(lib.journal.mark(), before);
}

/// Обновление не ломает то, что было: действие над треком по-прежнему
/// описывает его сразу, а прослушивание считается как раньше.
#[test]
fn an_action_after_the_refresh_changes_nothing_more() {
    let mut lib = library();
    lib.tree.retag("Music/Song.mp3", "Song (Remastered)|Band|Debut|181");
    rescan(&lib.db, &lib.tree);
    describe_changed(&mut lib.journal, &lib.db).unwrap();
    let before = lib.journal.mark();

    do_op(&mut lib.journal, &lib.db, Op::Play(play(lib.song, 1_790_000_000_000, 150, Some(181))));

    assert_eq!(lib.journal.mark().seq, before.seq + 1, "паспорт уже свежий — в правке только прослушивание");
    assert_eq!(lib.db.user_data(lib.song).unwrap().play_count, 1);
}
