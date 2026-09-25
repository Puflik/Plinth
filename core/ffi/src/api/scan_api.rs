//! Скан библиотеки (A3.1, D1, D2): Kotlin отдаёт тома и папки, ядро само
//! обходит файлы, читает теги (`lofty`, ADR 0009), пишет каталог пачками и
//! сообщает о ходе колбэком. Разделители исполнителей — по умолчанию
//! (plan.md 13.2); настройка появится вместе с экраном.

use std::path::PathBuf;
use std::sync::Arc;

use plinth_library::db::Database;
use plinth_library::scan::{DbAccess, FileTags, FolderConfig, ScanPhase, ScanProgress, ScanReport, scan};
use plinth_types::{CoreError, Timestamp};

use crate::panic;
use crate::session::Core;

#[uniffi::remote(Record)]
pub struct FolderConfig {
    pub included: Vec<String>,
    pub excluded: Vec<String>,
}

#[uniffi::remote(Enum)]
pub enum ScanPhase {
    Walking,
    Reading,
    Writing,
}

#[uniffi::remote(Record)]
pub struct ScanProgress {
    pub phase: ScanPhase,
    pub done: u32,
    pub total: u32,
}

#[uniffi::remote(Record)]
pub struct ScanReport {
    pub found: u32,
    pub added: u32,
    pub changed: u32,
    pub returned: u32,
    pub missing: u32,
    pub unreadable_files: u32,
    pub unreadable_folders: u32,
    pub missing_volumes: u32,
    pub stopped: bool,
}

/// Куда ядро отдаёт ход скана; `false` — остановиться (отмена `WorkManager`).
#[uniffi::export(with_foreign)]
pub trait ScanListener: Send + Sync {
    fn progress(&self, progress: ScanProgress) -> bool;
}

#[uniffi::export]
impl Core {
    /// Сканирует `volumes` — корни томов (`/storage/emulated/0`, SD-карты) —
    /// по папкам `folders`. Блокирует до конца скана; база занята только на
    /// время записи пачек, экраны читают между ними. Второй скан, пока идёт
    /// первый, — `Unavailable`.
    pub fn scan(
        &self,
        volumes: Vec<String>,
        folders: FolderConfig,
        listener: Arc<dyn ScanListener>,
    ) -> Result<ScanReport, CoreError> {
        panic::guard(|| {
            let _scanning = self.scanning()?;
            let roots: Vec<PathBuf> = volumes.into_iter().map(PathBuf::from).collect();
            let reader = FileTags::default();
            scan(self, &roots, &folders, &reader, &mut |p| listener.progress(p), Timestamp::now())
        })
    }
}

/// База ядра — под его замком, на одну пачку за раз.
impl DbAccess for Core {
    fn run(&self, work: &mut dyn FnMut(&Database) -> Result<(), CoreError>) -> Result<(), CoreError> {
        self.with(|state| work(&state.db))
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::sync::{Arc, Mutex};

    use plinth_library::db::query::TrackSort;
    use plinth_library::scan::{FolderConfig, ScanPhase, ScanProgress};
    use plinth_types::CoreError;

    use super::ScanListener;
    use crate::session::Core;
    use crate::testing::Scratch;

    #[derive(Default)]
    struct Seen(Mutex<Vec<ScanProgress>>);

    impl ScanListener for Seen {
        fn progress(&self, progress: ScanProgress) -> bool {
            self.0.lock().unwrap().push(progress);
            true
        }
    }

    fn volume(files: &[&str]) -> Scratch {
        let volume = Scratch::new();
        for file in files {
            let path = volume.0.join(file);
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(path, b"audio").unwrap();
        }
        volume
    }

    #[test]
    fn a_scan_fills_the_library_and_reports_progress() {
        let (data, music) = (Scratch::new(), volume(&["Music/Creep.mp3", "Music/Karma Police.flac"]));
        let core = Core::open(data.path()).unwrap();
        let seen = Arc::new(Seen::default());

        let report = core.scan(vec![music.path()], FolderConfig::default(), seen.clone()).unwrap();

        assert_eq!((report.found, report.added), (2, 2));
        let titles: Vec<String> = core.tracks(TrackSort::Title, None).unwrap().into_iter().map(|t| t.title).collect();
        assert_eq!(titles, ["Creep", "Karma Police"]);
        let phases: Vec<ScanPhase> = seen.0.lock().unwrap().iter().map(|p| p.phase).collect();
        assert!(phases.contains(&ScanPhase::Walking) && phases.contains(&ScanPhase::Writing), "{phases:?}");
    }

    /// Теги читаются по-настоящему: название, исполнитель и альбом из файла.
    #[test]
    fn a_scan_reads_real_tags() {
        let (data, music) = (Scratch::new(), volume(&[]));
        let fixture = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../app/src/androidTest/assets/tags/plinth-m4a.m4a");
        fs::create_dir_all(music.0.join("Music")).unwrap();
        fs::copy(fixture, music.0.join("Music/plinth-m4a.m4a")).unwrap();
        let core = Core::open(data.path()).unwrap();

        let report = core.scan(vec![music.path()], FolderConfig::default(), Arc::new(Seen::default())).unwrap();

        assert_eq!((report.added, report.unreadable_files), (1, 0));
        let row = core.tracks(TrackSort::Title, None).unwrap().remove(0);
        assert_eq!((row.title.as_str(), row.artist_credit.as_str()), ("M4A Silence", "The Plinth"));
        assert_eq!(row.album_title.as_deref(), Some("Fixtures"));
    }

    /// Второй скан поверх идущего — отказ, а не два писателя одних файлов.
    #[test]
    fn one_scan_at_a_time() {
        let (data, music) = (Scratch::new(), volume(&["Music/a.mp3"]));
        let core = Core::open(data.path()).unwrap();
        let held = core.scanning().unwrap();

        let second = core.scan(vec![music.path()], FolderConfig::default(), Arc::new(Seen::default()));

        assert!(matches!(second, Err(CoreError::Unavailable { .. })));
        drop(held);
        assert!(core.scan(vec![music.path()], FolderConfig::default(), Arc::new(Seen::default())).is_ok());
    }
}
