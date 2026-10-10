//! Копия журнала в папке человека (C4): Kotlin выбирает папку через SAF,
//! пишет и читает файлы, ядро отдаёт копию байтами, осматривает найденные и
//! вливает их в журнал.
//!
//! Файл — на установку: имя — идентификатор установки (`device`). Он новый
//! после переустановки и не попадает в Auto Backup, поэтому копия прошлой
//! установки для новой — чужая, и новая её не перезапишет.

use plinth_sync::journal::{describe_changed, describe_missing, relink};
use plinth_sync::mirror::{copy_of, inspect, restore};
use plinth_types::{CoreError, Timestamp};

use crate::panic;
use crate::session::{Core, State};

/// Файл копии: имя в папке и содержимое.
#[derive(uniffi::Record)]
pub struct MirrorFile {
    pub name: String,
    pub content: Vec<u8>,
}

/// Что нашлось в чужих копиях — для вопроса «Восстановить?».
#[derive(uniffi::Record, Debug, PartialEq, Eq)]
pub struct MirrorFound {
    pub likes: u32,
    pub playlists: u32,
    pub plays: u32,
    /// Время самой свежей копии; прочитанных нет — `None`.
    pub written_at: Option<Timestamp>,
    /// Есть то, чего журнал ещё не знает, — есть что восстанавливать.
    pub news: bool,
    /// Файлы, которые не прочлись.
    pub unreadable: u32,
}

#[derive(uniffi::Record, Debug, PartialEq, Eq)]
pub struct MirrorRestore {
    /// Копии, принёсшие новое.
    pub merged: u32,
    pub unreadable: u32,
    /// Треки библиотеки, узнанные по паспорту.
    pub relinked: u32,
}

#[uniffi::export]
impl Core {
    /// Копия журнала этой установки. Трекам с данными, но без паспорта
    /// (журнал до C4), паспорт дописывается перед копией.
    pub fn mirror_copy(&self) -> Result<MirrorFile, CoreError> {
        panic::guard(|| {
            self.with(|state| {
                describe_missing(&mut state.journal, &state.db)?;
                Ok(MirrorFile { name: own_name(state), content: copy_of(&state.journal, Timestamp::now()) })
            })
        })
    }

    /// Осматривает копии `files` из папки; своя пропускается.
    pub fn inspect_mirror(&self, files: Vec<MirrorFile>) -> Result<MirrorFound, CoreError> {
        panic::guard(|| {
            self.with(|state| {
                let found = inspect(&state.journal, &others(state, &files))?;
                Ok(MirrorFound {
                    likes: count(found.likes),
                    playlists: count(found.playlists),
                    plays: count(found.plays),
                    written_at: found.written_at,
                    news: found.news,
                    unreadable: count(found.unreadable),
                })
            })
        })
    }

    /// Вливает копии `files` в журнал, кроме своей; база пересобирается,
    /// треки библиотеки узнаются по паспортам.
    pub fn restore_mirror(&self, files: Vec<MirrorFile>) -> Result<MirrorRestore, CoreError> {
        panic::guard(|| {
            self.with(|state| {
                let others = others(state, &files);
                let restored = restore(&mut state.journal, &state.db, &others)?;
                Ok(MirrorRestore {
                    merged: count(restored.merged),
                    unreadable: count(restored.unreadable),
                    relinked: count(restored.relinked),
                })
            })
        })
    }
}

impl Core {
    /// Каталог изменился (скан, тестовое наполнение): треки журнала, которых
    /// в нём нет, узнаются по паспортам, а паспорта оставшихся догоняют теги
    /// каталога (`describe_changed`) — сначала перепривязка, ей нужны паспорта
    /// потерянных как есть. Каждый шаг не зависит от исхода другого; не вышло —
    /// скан уже записан, в лог.
    pub(crate) fn relink_catalog(&self) {
        if let Err(error) = self.with(|state| relink(&mut state.journal, &state.db)) {
            log::error!("mirror: relink after a catalog change failed: {error}");
        }
        if let Err(error) = self.with(|state| describe_changed(&mut state.journal, &state.db)) {
            log::error!("mirror: refreshing passports after a catalog change failed: {error}");
        }
    }
}

/// Имя своего файла: `<установка>.journal`. Временный файл при записи —
/// с тем же началом.
fn own_name(state: &State) -> String {
    format!("{}.journal", state.journal.device())
}

fn others<'a>(state: &State, files: &'a [MirrorFile]) -> Vec<&'a [u8]> {
    let own = state.journal.device().to_string();
    files.iter().filter(|file| !file.name.starts_with(&own)).map(|file| file.content.as_slice()).collect()
}

fn count(value: usize) -> u32 {
    u32::try_from(value).unwrap_or(u32::MAX)
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::sync::Arc;

    use plinth_library::db::query::TrackSort;
    use plinth_library::scan::FolderConfig;

    use super::{MirrorFile, MirrorRestore};
    use crate::api::scan_api::ScanListener;
    use crate::api::test_api::TestFile;
    use crate::session::Core;
    use crate::testing::Scratch;

    const KINO: &str = "/storage/emulated/0/Music/Кино/Кукушка.mp3";
    const QUEEN: &str = "/storage/emulated/0/Music/Queen/Bohemian Rhapsody.mp3";

    fn file(uri: &str, artist: &str, title: &str) -> TestFile {
        TestFile {
            uri: uri.to_owned(),
            folder: "Music/".to_owned(),
            title: Some(title.to_owned()),
            artist: Some(artist.to_owned()),
            album: None,
            album_artist: None,
            disc: None,
            number: None,
            duration_ms: Some(300_000),
        }
    }

    fn seed(core: &Core) {
        core.seed_for_test(vec![file(KINO, "Кино", "Кукушка"), file(QUEEN, "Queen", "Bohemian Rhapsody")]).unwrap();
    }

    fn at(core: &Core, path: &str) -> plinth_types::TrackId {
        core.track_at(path.to_owned()).unwrap().unwrap()
    }

    fn liked(core: &Core) -> Vec<String> {
        core.liked_tracks().unwrap().into_iter().map(|row| row.title).collect()
    }

    fn playlist_titles(core: &Core) -> Vec<String> {
        let road = &core.playlists().unwrap()[0];
        core.playlist_tracks(road.id).unwrap().into_iter().map(|row| row.track.title).collect()
    }

    /// Прошлая установка: лайк Кино и плейлист «Road» с Queen.
    fn old_installation(dir: &Scratch) -> MirrorFile {
        let core = Core::open(dir.path()).unwrap();
        seed(&core);
        core.like(at(&core, KINO)).unwrap();
        let road = core.create_playlist("Road".to_owned()).unwrap();
        core.add_to_playlist(road.id, at(&core, QUEEN), None).unwrap();
        core.mirror_copy().unwrap()
    }

    /// DoD эпика C в ядре: переустановка — новый каталог данных, скан выдал
    /// новые ID; копия из папки возвращает лайки и плейлисты на свои треки.
    #[test]
    fn a_copy_brings_everything_back_after_a_reinstall() {
        let (old, new) = (Scratch::new(), Scratch::new());
        let copy = old_installation(&old);
        let core = Core::open(new.path()).unwrap();
        seed(&core);

        let found = core.inspect_mirror(vec![copy_of(&copy)]).unwrap();
        let restored = core.restore_mirror(vec![copy]).unwrap();

        assert_eq!((found.likes, found.playlists, found.news, found.unreadable), (1, 1, true, 0));
        assert!(found.written_at.is_some());
        assert_eq!(restored, MirrorRestore { merged: 1, unreadable: 0, relinked: 2 });
        assert_eq!(liked(&core), ["Кукушка"]);
        assert_eq!(playlist_titles(&core), ["Bohemian Rhapsody"]);
    }

    /// Восстановили в мастере, до скана: треки узнаются, когда скан их найдёт.
    #[test]
    fn a_restore_before_the_scan_is_relinked_by_the_scan() {
        let (old, new, volume) = (Scratch::new(), Scratch::new(), Scratch::new());
        let music = volume.0.join("Music");
        fs::create_dir_all(&music).unwrap();
        fs::write(music.join("Creep.mp3"), b"audio").unwrap();
        let copy = {
            let core = Core::open(old.path()).unwrap();
            core.scan(vec![volume.path()], FolderConfig::default(), Arc::new(Quiet)).unwrap();
            core.like(core.tracks(TrackSort::Title, None).unwrap()[0].id).unwrap();
            core.mirror_copy().unwrap()
        };
        let core = Core::open(new.path()).unwrap();
        core.restore_mirror(vec![copy]).unwrap();
        assert!(liked(&core).is_empty());

        core.scan(vec![volume.path()], FolderConfig::default(), Arc::new(Quiet)).unwrap();

        assert_eq!(liked(&core), ["Creep"]);
    }

    /// Своя копия — не новость и не считается: её пишет эта установка.
    #[test]
    fn the_own_copy_is_skipped() {
        let dir = Scratch::new();
        let copy = old_installation(&dir);
        let core = Core::open(dir.path()).unwrap();
        let temporary = MirrorFile { name: format!("{}.tmp", copy.name), content: copy.content.clone() };

        let found = core.inspect_mirror(vec![copy, temporary]).unwrap();

        assert_eq!((found.likes, found.news, found.written_at), (0, false, None));
    }

    /// Имя файла — по установке: то же после перезапуска, другое у другой
    /// установки, даже если журнал вернула системная копия.
    #[test]
    fn the_file_name_belongs_to_the_installation() {
        let (dir, restored_elsewhere) = (Scratch::new(), Scratch::new());
        let first = Core::open(dir.path()).unwrap().mirror_copy().unwrap().name;
        let again = Core::open(dir.path()).unwrap().mirror_copy().unwrap().name;
        copy_dir(&dir.0.join("journal"), &restored_elsewhere.0.join("journal"));

        let other = Core::open(restored_elsewhere.path()).unwrap().mirror_copy().unwrap().name;

        assert_eq!(first, again);
        assert!(first.ends_with(".journal"), "{first}");
        assert_ne!(first, other);
    }

    #[test]
    fn junk_in_the_folder_is_counted_not_fatal() {
        let dir = Scratch::new();
        let core = Core::open(dir.path()).unwrap();
        let junk = MirrorFile { name: "notes.txt".to_owned(), content: b"hello".to_vec() };

        let found = core.inspect_mirror(vec![junk]).unwrap();
        let restored = core.restore_mirror(vec![MirrorFile { name: "x".to_owned(), content: Vec::new() }]).unwrap();

        assert_eq!((found.unreadable, found.news), (1, false));
        assert_eq!(restored, MirrorRestore { merged: 0, unreadable: 1, relinked: 0 });
    }

    struct Quiet;

    impl ScanListener for Quiet {
        fn progress(&self, _progress: plinth_library::scan::ScanProgress) -> bool {
            true
        }
    }

    fn copy_of(file: &MirrorFile) -> MirrorFile {
        MirrorFile { name: file.name.clone(), content: file.content.clone() }
    }

    fn copy_dir(from: &std::path::Path, to: &std::path::Path) {
        fs::create_dir_all(to).unwrap();
        for entry in fs::read_dir(from).unwrap() {
            let entry = entry.unwrap();
            fs::copy(entry.path(), to.join(entry.file_name())).unwrap();
        }
    }
}
