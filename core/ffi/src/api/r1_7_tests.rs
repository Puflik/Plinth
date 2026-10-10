//! Р1.7 (`docs/work/r1-7.md`, находка 7 ревью v0.2): теги поправили вне
//! приложения, настоящий `Core::scan` их подхватил — и паспорт в журнале
//! догоняет каталог. Копия журнала несёт свежий паспорт, поэтому после
//! переустановки трек находится по новым тегам.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use plinth_library::db::JournalMark;
use plinth_library::scan::{FolderConfig, ScanProgress, ScanReport};

use super::scan_api::ScanListener;
use crate::session::Core;
use crate::testing::Scratch;

struct Quiet;

impl ScanListener for Quiet {
    fn progress(&self, _progress: ScanProgress) -> bool {
        true
    }
}

fn fixture(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../app/src/androidTest/assets/tags").join(name)
}

fn scan(core: &Core, volume: &Scratch) -> ScanReport {
    core.scan(vec![volume.path()], FolderConfig::default(), Arc::new(Quiet)).unwrap()
}

fn mark(core: &Core) -> JournalMark {
    core.with(|state| Ok(state.journal.mark())).unwrap()
}

fn liked(core: &Core) -> Vec<String> {
    core.liked_tracks().unwrap().into_iter().map(|row| row.title).collect()
}

/// Файл с тегами («Тишина», Plinth); после правки тегов вне приложения — тот же
/// путь, в файле тегов нет, название берётся из имени файла.
#[test]
fn tags_edited_outside_the_app_do_not_cost_the_data_after_a_reinstall() {
    let (music, old_dir, new_dir) = (Scratch::new(), Scratch::new(), Scratch::new());
    let song = music.0.join("Music").join("Song.mp3");
    fs::create_dir_all(song.parent().unwrap()).unwrap();
    fs::copy(fixture("plinth-mp3.mp3"), &song).unwrap();
    let uri = song.to_string_lossy().into_owned();

    let old = Core::open(old_dir.path()).unwrap();
    scan(&old, &music);
    let track = old.track_at(uri.clone()).unwrap().unwrap();
    old.like(track).unwrap();
    assert_eq!(liked(&old), ["Тишина"]);

    fs::copy(fixture("plinth-untagged.mp3"), &song).unwrap();
    let before = mark(&old);
    let report = scan(&old, &music);

    assert_eq!(report.changed, 1);
    let after = mark(&old);
    assert_eq!(old.track_at(uri.clone()).unwrap(), Some(track), "ID прежний, теги новые");
    assert_eq!(liked(&old), ["Song"]);
    let copy = old.mirror_copy().unwrap();
    drop(old);

    // Переустановка: другой каталог данных, скан выдал новые ID, копия возвращает лайк.
    let new = Core::open(new_dir.path()).unwrap();
    scan(&new, &music);
    assert_ne!(new.track_at(uri.clone()).unwrap(), Some(track), "новая установка — новый ID");
    let restored = new.restore_mirror(vec![copy]).unwrap();

    assert_eq!(restored.relinked, 1);
    assert_eq!(new.track_at(uri).unwrap(), Some(track), "трек вернул себе ID из журнала");
    assert_eq!(liked(&new), ["Song"]);
    assert_eq!(after.seq, before.seq + 1, "паспорт обновлён одной правкой журнала");
}

/// Скан без правок тегов журнал не трогает: паспорта и так свежие, лишних
/// правок нет, а значит, нет и лишней перезаписи копии в папке.
#[test]
fn a_scan_without_edits_leaves_the_journal_as_it_was() {
    let (music, dir) = (Scratch::new(), Scratch::new());
    let song = music.0.join("Music").join("Song.mp3");
    fs::create_dir_all(song.parent().unwrap()).unwrap();
    fs::copy(fixture("plinth-mp3.mp3"), &song).unwrap();
    let core = Core::open(dir.path()).unwrap();
    scan(&core, &music);
    let track = core.track_at(song.to_string_lossy().into_owned()).unwrap().unwrap();
    core.like(track).unwrap();
    let before = mark(&core);

    let report = scan(&core, &music);

    assert_eq!((report.added, report.changed, report.missing), (0, 0, 0));
    assert_eq!(mark(&core), before);
}
