//! Р1.15 (`docs/work/r1-15.md`, находки 4 и 5 ревью FFI/сканера): настоящий
//! `Core::scan_volumes`. Карту после загрузки ещё проверяют — Kotlin отдаёт её
//! как `pending`, и лайк на её треке остаётся в «Любимом»; вынутую карту (её
//! нет ни в одном списке) скан по-прежнему прячет, а прежний `Core::scan` — это
//! `scan_volumes` без `pending`.
//!
//! `Core::scan_volumes` — новый вход (сигнатура в `docs/work/r1-15.md`): пока
//! его нет, тесты крейта не собираются.

use std::fs;
use std::path::PathBuf;
use std::sync::Arc;

use plinth_library::db::query::TrackSort;
use plinth_library::scan::{FolderConfig, ScanProgress, ScanReport};
use plinth_types::DeviceId;

use super::scan_api::ScanListener;
use crate::session::Core;
use crate::testing::Scratch;

struct Quiet;

impl ScanListener for Quiet {
    fn progress(&self, _progress: ScanProgress) -> bool {
        true
    }
}

/// Том с файлами; стирается в `Drop`.
fn volume(files: &[&str]) -> Scratch {
    let volume = Scratch::new();
    for file in files {
        let path = volume.0.join(file);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, b"audio").unwrap();
    }
    volume
}

/// Карта, которую можно «вынуть из слота» и вставить обратно.
struct Card {
    volume: Scratch,
    aside: PathBuf,
}

impl Card {
    fn new(files: &[&str]) -> Self {
        let aside = std::env::temp_dir().join(format!("plinth-core-aside-{}", DeviceId::new()));
        Self { volume: volume(files), aside }
    }

    fn unmount(&self) {
        fs::rename(&self.volume.0, &self.aside).unwrap();
    }

    fn mount(&self) {
        fs::rename(&self.aside, &self.volume.0).unwrap();
    }

    /// Так файл лежит в каталоге: путь по сегментам, с разделителями обхода.
    fn uri(&self, file: &str) -> String {
        file.split('/').fold(self.volume.0.clone(), |dir, segment| dir.join(segment)).to_string_lossy().into_owned()
    }
}

impl Drop for Card {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.aside);
    }
}

fn scan_volumes(core: &Core, ready: &[&Scratch], pending: &[&Scratch]) -> ScanReport {
    core.scan_volumes(
        ready.iter().map(|volume| volume.path()).collect(),
        pending.iter().map(|volume| volume.path()).collect(),
        FolderConfig::default(),
        Arc::new(Quiet),
    )
    .unwrap()
}

fn titles(core: &Core) -> Vec<String> {
    core.tracks(TrackSort::Title, None).unwrap().into_iter().map(|row| row.title).collect()
}

fn liked(core: &Core) -> Vec<String> {
    core.liked_tracks().unwrap().into_iter().map(|row| row.title).collect()
}

/// Сценарий ревью через настоящее ядро. Трек с карты любим. Карту проверяют
/// (`pending`) — лайк в «Любимом», треки в списке. Карту вынули (её нет в
/// списках) — трек скрыт, как всегда. Карта вернулась — всё на месте.
#[test]
fn a_card_that_is_being_checked_keeps_its_like_and_an_ejected_one_hides_it() {
    let (data, phone) = (Scratch::new(), volume(&["Music/Local.mp3"]));
    let card = Card::new(&["Music/Creep.mp3", "Music/Karma Police.flac"]);
    let core = Core::open(data.path()).unwrap();
    scan_volumes(&core, &[&phone, &card.volume], &[]);
    let creep = core.track_at(card.uri("Music/Creep.mp3")).unwrap().unwrap();
    core.like(creep).unwrap();
    assert_eq!(liked(&core), ["Creep"]);

    card.unmount();
    let checking = scan_volumes(&core, &[&phone], &[&card.volume]);
    assert_eq!((checking.found, checking.missing), (1, 0));
    assert_eq!(liked(&core), ["Creep"], "карту проверяют: лайк на месте");
    assert_eq!(titles(&core), ["Creep", "Karma Police", "Local"]);

    let ejected = scan_volumes(&core, &[&phone], &[]);
    assert_eq!(ejected.missing, 2);
    assert!(liked(&core).is_empty(), "вынутая карта: трек скрыт, как всегда");
    assert_eq!(titles(&core), ["Local"]);

    card.mount();
    let back = scan_volumes(&core, &[&phone, &card.volume], &[]);
    assert_eq!(back.returned, 2);
    assert_eq!(liked(&core), ["Creep"]);
}

/// Прежний `Core::scan` остаётся: все его тома готовы, отчёт такой же, как у
/// `scan_volumes` без `pending`.
#[test]
fn the_older_scan_is_scan_volumes_without_pending() {
    let (data_old, data_new) = (Scratch::new(), Scratch::new());
    let phone = volume(&["Music/A.mp3", "Music/B.flac"]);
    let (old, new) = (Core::open(data_old.path()).unwrap(), Core::open(data_new.path()).unwrap());

    let before = old.scan(vec![phone.path()], FolderConfig::default(), Arc::new(Quiet)).unwrap();
    let after = scan_volumes(&new, &[&phone], &[]);

    assert_eq!(before, after);
    assert_eq!(titles(&old), titles(&new));
}
