//! Обход файловой системы (D1). В отличие от `MediaStore` в v0.1 видит
//! файлы в любых папках тома: на Android 11+ разрешение на музыку открывает
//! прямые пути к аудиофайлам общего хранилища.
//!
//! - Обход — с включённых папок каждого тома; исключённые не заходятся.
//! - Папки с точкой в начале пропускаются: служебные (`.thumbnails`,
//!   `.trash`) и `Music/.plinth` — зеркало журнала (C4).
//! - По символьным ссылкам не ходит: петля ссылок обошлась бы вечно.
//! - Аудио — по расширению; что внутри, решает чтение тегов (D2).
//! - Папка, которую не прочесть, запоминается: её файлы не «пропали», о них
//!   просто ничего не известно.

use std::fs;
use std::path::{MAIN_SEPARATOR, Path, PathBuf};
use std::time::UNIX_EPOCH;

use plinth_types::{Format, Timestamp};

use super::folder_config::FolderConfig;
use super::progress::{Progress, REPORT_EVERY, ScanPhase, ScanProgress, count};

/// Аудиофайл, найденный обходом.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FoundFile {
    /// Абсолютный путь — так файл лежит в `source.local_uri` и так его играет Media3.
    pub uri: String,
    /// Папка от корня тома: `Music/Queen/`.
    pub folder: String,
    pub format: Format,
    pub modified_at: Timestamp,
    pub size: u64,
}

/// Итог обхода.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Walk {
    /// Найденные файлы по пути.
    pub files: Vec<FoundFile>,
    /// Папки, которые не прочесть, — путь с разделителем в конце.
    pub unreadable: Vec<String>,
    /// Тома, которых нет: SD-карту вынули.
    pub missing_roots: Vec<String>,
}

/// Обходит `roots` — корни томов — по правилам `config`. `None` — остановлен
/// через `progress`.
pub fn walk(roots: &[PathBuf], config: &FolderConfig, progress: Progress<'_>) -> Option<Walk> {
    let mut walk = Walk::default();
    let mut reported = 0;
    for root in roots {
        if !root.is_dir() {
            walk.missing_roots.push(root.to_string_lossy().into_owned());
            continue;
        }
        // По сегментам, а не строкой `Music/`: путь файла — его ключ в
        // каталоге, разделители в нём должны быть одни.
        let mut stack: Vec<(PathBuf, String)> = config
            .starts()
            .into_iter()
            .map(|start| (start.split('/').filter(|s| !s.is_empty()).fold(root.clone(), |dir, s| dir.join(s)), start))
            .collect();
        while let Some((dir, folder)) = stack.pop() {
            if !visit(&dir, &folder, config, &mut stack, &mut walk) {
                continue;
            }
            let found = count(walk.files.len());
            if found - reported >= REPORT_EVERY {
                reported = found;
                if !progress(ScanProgress { phase: ScanPhase::Walking, done: found, total: 0 }) {
                    return None;
                }
            }
        }
    }
    walk.files.sort_by(|a, b| a.uri.cmp(&b.uri));
    let done = count(walk.files.len());
    progress(ScanProgress { phase: ScanPhase::Walking, done, total: 0 }).then_some(walk)
}

/// Читает одну папку: файлы — в `walk`, подпапки — в `stack`. `false` — папки нет.
fn visit(dir: &Path, folder: &str, config: &FolderConfig, stack: &mut Vec<(PathBuf, String)>, walk: &mut Walk) -> bool {
    let entries = match fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return false,
        Err(error) => {
            log::warn!("scan: a folder is unreadable: {error}");
            walk.unreadable.push(format!("{}{MAIN_SEPARATOR}", dir.to_string_lossy()));
            return false;
        }
    };
    for entry in entries.flatten() {
        let Ok(kind) = entry.file_type() else { continue };
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            log::warn!("scan: skipped a name that is not UTF-8");
            continue;
        };
        if kind.is_dir() {
            let sub = format!("{folder}{name}/");
            if !name.starts_with('.') && config.includes(&sub) {
                stack.push((entry.path(), sub));
            }
        } else if kind.is_file()
            && let Some(format) = format_of(&name)
            && let Ok(meta) = entry.metadata()
        {
            let modified = meta.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).unwrap_or_default();
            walk.files.push(FoundFile {
                uri: entry.path().to_string_lossy().into_owned(),
                folder: folder.to_owned(),
                format,
                modified_at: Timestamp::from_millis(i64::try_from(modified.as_millis()).unwrap_or(i64::MAX)),
                size: meta.len(),
            });
        }
    }
    true
}

/// Формат по расширению: восемь, которые играет Media3 (B2.4, v0.1). ALAC
/// и AAC в M4A различит чтение тегов (D2).
fn format_of(name: &str) -> Option<Format> {
    let (_, extension) = name.rsplit_once('.')?;
    match extension.to_ascii_lowercase().as_str() {
        "flac" => Some(Format::Flac),
        "mp3" => Some(Format::Mp3),
        "m4a" | "m4b" | "aac" => Some(Format::Aac),
        "ogg" | "oga" => Some(Format::Vorbis),
        "opus" => Some(Format::Opus),
        "wav" => Some(Format::Wav),
        "aif" | "aiff" => Some(Format::Aiff),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::{Path, PathBuf};

    use plinth_types::{DeviceId, Format};

    use super::{format_of, walk};
    use crate::scan::folder_config::FolderConfig;
    use crate::scan::progress::ScanProgress;

    struct Tree(PathBuf);

    impl Tree {
        fn new(files: &[&str]) -> Self {
            let root = std::env::temp_dir().join(format!("plinth-walk-{}", DeviceId::new()));
            for file in files {
                let path = root.join(file);
                fs::create_dir_all(path.parent().unwrap()).unwrap();
                fs::write(&path, b"audio").unwrap();
            }
            fs::create_dir_all(&root).unwrap();
            Self(root)
        }
    }

    impl Drop for Tree {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn found(root: &Path, config: &FolderConfig) -> Vec<(String, String)> {
        let walk = walk(&[root.to_owned()], config, &mut |_| true).unwrap();
        walk.files
            .iter()
            .map(|f| {
                let relative = Path::new(&f.uri).strip_prefix(root).unwrap().to_string_lossy().replace('\\', "/");
                (relative, f.folder.clone())
            })
            .collect()
    }

    #[test]
    fn audio_in_included_folders_is_found_with_its_folder() {
        let tree = Tree::new(&[
            "Music/Queen/01 Bohemian.flac",
            "Music/Queen/cover.jpg",
            "Music/loose.MP3",
            "Download/talk.opus",
            "DCIM/voice.m4a",
            "MusicVideos/clip.mp3",
        ]);

        let files = found(&tree.0, &FolderConfig::default());

        assert_eq!(
            files,
            vec![
                ("Download/talk.opus".to_owned(), "Download/".to_owned()),
                ("Music/Queen/01 Bohemian.flac".to_owned(), "Music/Queen/".to_owned()),
                ("Music/loose.MP3".to_owned(), "Music/".to_owned()),
            ]
        );
    }

    #[test]
    fn hidden_and_excluded_folders_are_skipped() {
        let tree = Tree::new(&["Music/a.mp3", "Music/.plinth/journal.mp3", "Music/Podcasts/ep.mp3"]);
        let config = FolderConfig { included: vec!["Music/".to_owned()], excluded: vec!["music/podcasts".to_owned()] };

        let files: Vec<String> = found(&tree.0, &config).into_iter().map(|(f, _)| f).collect();

        assert_eq!(files, vec!["Music/a.mp3".to_owned()]);
    }

    #[test]
    fn a_file_carries_its_size_and_format() {
        let tree = Tree::new(&["Music/a.flac"]);

        let walk = walk(std::slice::from_ref(&tree.0), &FolderConfig::default(), &mut |_| true).unwrap();

        assert_eq!(walk.files.len(), 1);
        assert_eq!((walk.files[0].format, walk.files[0].size), (Format::Flac, 5));
        assert!(walk.files[0].modified_at.as_millis() > 0);
    }

    /// Вынутая SD-карта — не пустая папка: её треки станут недоступны, а не исчезнут молча.
    #[test]
    fn a_missing_volume_is_reported() {
        let tree = Tree::new(&["Music/a.mp3"]);
        let gone = tree.0.join("no-such-volume");

        let walk = walk(&[gone.clone(), tree.0.clone()], &FolderConfig::default(), &mut |_| true).unwrap();

        assert_eq!(walk.files.len(), 1);
        assert_eq!(walk.missing_roots, vec![gone.to_string_lossy().into_owned()]);
    }

    #[test]
    fn walking_stops_when_asked() {
        let tree = Tree::new(&["Music/a.mp3"]);
        let mut seen: Vec<ScanProgress> = Vec::new();

        let result = walk(std::slice::from_ref(&tree.0), &FolderConfig::default(), &mut |p| {
            seen.push(p);
            false
        });

        assert!(result.is_none());
        assert_eq!(seen.len(), 1);
    }

    #[test]
    fn formats_come_from_the_extension() {
        for (name, format) in [
            ("a.flac", Some(Format::Flac)),
            ("a.M4A", Some(Format::Aac)),
            ("a.oga", Some(Format::Vorbis)),
            ("a.aiff", Some(Format::Aiff)),
            ("a.wma", None),
            ("flac", None),
            ("a.flac.part", None),
        ] {
            assert_eq!(format_of(name), format, "{name}");
        }
    }

    /// Петля символьных ссылок не уводит обход в бесконечность.
    #[cfg(unix)]
    #[test]
    fn symbolic_links_are_not_followed() {
        let tree = Tree::new(&["Music/a.mp3"]);
        std::os::unix::fs::symlink(tree.0.join("Music"), tree.0.join("Music/loop")).unwrap();

        assert_eq!(found(&tree.0, &FolderConfig::default()).len(), 1);
    }
}
