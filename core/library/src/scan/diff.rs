//! Что изменилось с прошлого скана (D1): по пути, времени изменения и
//! размеру файла. Теги перечитываются только у новых и изменённых файлов.
//!
//! Пропавший файл не удаляется, а становится недоступным (план 17.6,
//! решение 16): место в плейлистах, лайк и история остаются, файл может
//! вернуться — SD-карту вставят обратно.
//!
//! «Не увидел» — не «нет» (Р1.15): файл пропал, только когда скан знает, что его
//! нет. Файл в папке, которую не удалось прочесть (`unreadable`), на записи
//! папки, которая не прочлась (`unseen`), под такой записью, на томе, который
//! система знает, но ещё не отдала (`unseen`, дописывает `scan_volumes`), не
//! пропал: о нём просто ничего не известно. Он не попадает ни в `missing`, ни в
//! `gone`, а значит, и к сведению перенесённых его не допускают.
//!
//! Недоступный с прошлых сканов файл, которого по-прежнему нет, — не новость
//! (`gone`), но он может найтись на новом месте: файл вынесли из папок
//! библиотеки, а потом вернули (Р1.2).

use std::collections::{HashMap, HashSet};
use std::path::MAIN_SEPARATOR;

use super::walker::{FoundFile, Walk};
use crate::db::repo::KnownFile;

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct ScanDiff {
    /// Файлы, которых каталог не знал.
    pub added: Vec<FoundFile>,
    /// Знакомые файлы с другим временем изменения или размером — теги перечитать.
    pub changed: Vec<(KnownFile, FoundFile)>,
    /// Недоступные файлы, которые вернулись нетронутыми.
    pub returned: Vec<KnownFile>,
    /// Доступные файлы, которых больше нет.
    pub missing: Vec<KnownFile>,
    /// Недоступные с прошлых сканов файлы, которых по-прежнему нет, — кроме тех,
    /// о которых скан ничего не знает (папка, запись, том). Не пропали сейчас, но,
    /// как [`missing`](Self::missing), могут оказаться новым файлом на другом месте.
    pub gone: Vec<KnownFile>,
    pub unchanged: usize,
}

pub fn diff(known: &HashMap<String, KnownFile>, walk: &Walk) -> ScanDiff {
    let mut diff = ScanDiff::default();
    let mut seen: HashSet<&str> = HashSet::new();
    for file in &walk.files {
        seen.insert(&file.uri);
        match known.get(&file.uri) {
            None => diff.added.push(file.clone()),
            Some(was) if was.modified_at != Some(file.modified_at) || was.size != Some(file.size) => {
                diff.changed.push((*was, file.clone()));
            }
            Some(was) if !was.available => diff.returned.push(*was),
            Some(_) => diff.unchanged += 1,
        }
    }
    // Набор, а не перебор: папка, где не прочлась каждая запись, даёт тысячи
    // путей, а в каталоге тысячи файлов — перебор был бы квадратом.
    let unseen: HashSet<&str> = walk.unseen.iter().map(String::as_str).collect();
    for (uri, was) in known {
        // Файл, который обход видел, ничего нового не считает; неизвестность
        // проверяется только у тех, кого обход не видел.
        if seen.contains(uri.as_str()) || is_unknown(uri, walk, &unseen) {
            continue;
        }
        if was.available {
            diff.missing.push(*was);
        } else {
            diff.gone.push(*was);
        }
    }
    diff.missing.sort_by_key(|file| file.source);
    diff.gone.sort_by_key(|file| file.source);
    diff.returned.sort_by_key(|file| file.source);
    diff
}

/// Скан ничего не знает о файле `uri`, которого обход не видел: он лежит в папке,
/// которую не прочесть, равен неизвестному пути или лежит под ним. Граница —
/// целый сегмент пути: `a.mp3.bak` не «под» `a.mp3`, `dirx` не «под» `dir`.
fn is_unknown(uri: &str, walk: &Walk, unseen: &HashSet<&str>) -> bool {
    walk.unreadable.iter().any(|dir| uri.starts_with(dir.as_str()))
        || (!unseen.is_empty()
            && (unseen.contains(uri)
                || uri
                    .match_indices(MAIN_SEPARATOR)
                    .any(|(at, _)| uri.get(..at).is_some_and(|parent| unseen.contains(parent)))))
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use plinth_types::{Format, SourceId, Timestamp, TrackId, VersionId};

    use super::diff;
    use crate::db::repo::KnownFile;
    use crate::scan::walker::{FoundFile, Walk};

    fn found(uri: &str, modified: i64, size: u64) -> FoundFile {
        FoundFile {
            uri: uri.to_owned(),
            folder: "Music/".to_owned(),
            format: Format::Mp3,
            modified_at: Timestamp::from_millis(modified),
            size,
        }
    }

    fn known(modified: i64, size: u64, available: bool) -> KnownFile {
        KnownFile {
            source: SourceId::new(),
            version: VersionId::new(),
            track: TrackId::new(),
            modified_at: Some(Timestamp::from_millis(modified)),
            size: Some(size),
            available,
        }
    }

    #[test]
    fn files_are_sorted_into_added_changed_returned_missing_and_unchanged() {
        let (same, edited, back, gone) =
            (known(1, 10, true), known(1, 10, true), known(1, 10, false), known(1, 10, true));
        let catalog: HashMap<String, KnownFile> = [
            ("/m/same.mp3", same),
            ("/m/edited.mp3", edited),
            ("/m/back.mp3", back),
            ("/m/gone.mp3", gone),
            ("/m/still-gone.mp3", known(1, 10, false)),
        ]
        .into_iter()
        .map(|(uri, file)| (uri.to_owned(), file))
        .collect();
        let walk = Walk {
            files: vec![
                found("/m/same.mp3", 1, 10),
                found("/m/edited.mp3", 1, 11),
                found("/m/back.mp3", 1, 10),
                found("/m/new.mp3", 5, 5),
            ],
            ..Walk::default()
        };

        let diff = diff(&catalog, &walk);

        assert_eq!(diff.added, vec![found("/m/new.mp3", 5, 5)]);
        assert_eq!(diff.changed, vec![(edited, found("/m/edited.mp3", 1, 11))]);
        assert_eq!(diff.returned, vec![back]);
        assert_eq!(diff.missing, vec![gone]);
        assert_eq!(diff.unchanged, 1);
    }

    /// Файл без отметки прошлого скана перечитывается.
    #[test]
    fn a_file_never_scanned_is_changed() {
        let never = KnownFile { modified_at: None, size: None, ..known(0, 0, true) };
        let catalog = HashMap::from([("/m/a.mp3".to_owned(), never)]);
        let walk = Walk { files: vec![found("/m/a.mp3", 1, 1)], ..Walk::default() };

        assert_eq!(diff(&catalog, &walk).changed.len(), 1);
    }

    #[test]
    fn files_in_an_unreadable_folder_are_not_missing() {
        let catalog = HashMap::from([("/m/locked/a.mp3".to_owned(), known(1, 1, true))]);
        let walk = Walk { unreadable: vec!["/m/locked/".to_owned()], ..Walk::default() };

        assert!(diff(&catalog, &walk).missing.is_empty());
    }
}
