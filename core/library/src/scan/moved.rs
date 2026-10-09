//! Перенесённый файл (Р1.2): новый файл, который скан узнаёт в пропавшем.
//!
//! Файл перенесли в другую папку, переименовали его или папку, карта вышла
//! с другим именем тома — для обхода это новый путь. Если среди новых файлов
//! есть такой, что по паспорту (`TrackPassport::key`, `likeness`) совпадает с
//! пропавшим локальным файлом каталога, новый файл пишется как новое место
//! пропавшего: тот же источник получает новый путь, трек и его ID остаются.
//! Журнал при этом не пишется — пути в него не попадают (ADR 0007), а
//! лайки, плейлисты и история держатся за ID трека.
//!
//! Сводятся только новые файлы с прочитанными тегами; пропавший — кандидат,
//! если его нет на месте и он не в непрочитанной папке: исчезнувший в этом
//! скане ([`ScanDiff::missing`]) или давно недоступный ([`ScanDiff::gone`]).
//! Файл на месте своего трека не отдаёт: копия рядом с живым оригиналом —
//! новый трек.

use std::collections::{HashMap, HashSet};

use plinth_types::{CoreError, SourceId, TrackId};

use super::{DbAccess, ScanDiff, ScannedFile, title};
use crate::db::repo::KnownFile;
use crate::model::TrackPassport;

/// Пропавшие файлы, которым новое место нашлось, переходят в `known` новых
/// файлов `files`; сколько пар. Паспорта пропавших читаются из каталога одним
/// заходом в базу — и только если есть что сводить: читаемый новый файл и
/// пропавший. Обычный повторный скан базу здесь не трогает.
///
/// Паспорта читаются одним запросом `track_passports()` по всему каталогу, из
/// него берутся только треки пропавших: запрос на каждого пропавшего (два
/// запроса на трек) держал бы замок ядра тем дольше, чем больше недоступных
/// файлов накопил каталог.
pub(super) fn relocate(db: &dyn DbAccess, diff: &mut ScanDiff, files: &mut [ScannedFile]) -> Result<usize, CoreError> {
    let any_new = files.iter().any(|file| file.known.is_none() && file.readable);
    if !any_new || (diff.missing.is_empty() && diff.gone.is_empty()) {
        return Ok(0);
    }
    let wanted: HashSet<TrackId> = diff.missing.iter().chain(&diff.gone).map(|known| known.track).collect();
    let mut passports: HashMap<TrackId, TrackPassport> = HashMap::with_capacity(wanted.len());
    db.run(&mut |db| {
        passports.clear();
        for passport in db.track_passports()? {
            if wanted.contains(&passport.track) {
                passports.insert(passport.track, passport);
            }
        }
        Ok(())
    })?;
    let vanished = diff
        .missing
        .iter()
        .chain(&diff.gone)
        .filter_map(|known| passports.get(&known.track).map(|passport| (*known, passport.clone())))
        .collect();
    Ok(pair(diff, files, vanished))
}

/// Сводит новые файлы с пропавшими. Новые идут по порядку `files` (обход
/// сортирует их по пути); для каждого — кандидаты с тем же ключом паспорта и
/// лучший из них. При равном сходстве первым идёт файл, исчезнувший в этом
/// скане (`diff.missing`), и только потом давно недоступный (`diff.gone`);
/// внутри — трек, заведённый раньше (меньший ID). Пропавший берётся один раз,
/// новый файл сводится не больше чем с одним. Сведённый пропавший снимается с
/// `diff`: иначе скан скрыл бы его снова.
pub(super) fn pair(diff: &mut ScanDiff, files: &mut [ScannedFile], vanished: Vec<(KnownFile, TrackPassport)>) -> usize {
    let mut groups: HashMap<(String, String), Candidates> = HashMap::new();
    for (known, passport) in vanished {
        groups.entry(passport.key()).or_default().push(known, passport);
    }
    for group in groups.values_mut() {
        group.order();
    }

    let mut taken: HashSet<SourceId> = HashSet::new();
    for scanned in files.iter_mut().filter(|file| file.known.is_none() && file.readable) {
        let passport = passport_of(scanned);
        let Some(group) = groups.get_mut(&passport.key()) else { continue };
        if let Some(index) = passport.best_match(&group.passports) {
            let known = group.take(index);
            taken.insert(known.source);
            scanned.known = Some(known);
        }
    }
    if !taken.is_empty() {
        diff.missing.retain(|known| !taken.contains(&known.source));
        diff.gone.retain(|known| !taken.contains(&known.source));
    }
    taken.len()
}

/// Паспорт нового файла — по тем же правилам, что у каталога: название из тега
/// или имени файла, исполнитель — тег или пустая строка. MBID и источников у
/// файла нет; ID трека — заглушка, сравнение его не читает.
fn passport_of(scanned: &ScannedFile) -> TrackPassport {
    TrackPassport {
        track: TrackId::new(),
        title: title(scanned),
        artist: scanned.tags.artist.clone().unwrap_or_default(),
        album: scanned.tags.album.clone(),
        duration: scanned.tags.duration,
        mbid: None,
        sources: Vec::new(),
    }
}

/// Пропавшие одного ключа: паспорта и файлы рядом, по одному индексу.
#[derive(Default)]
struct Candidates {
    passports: Vec<TrackPassport>,
    files: Vec<KnownFile>,
}

impl Candidates {
    fn push(&mut self, known: KnownFile, passport: TrackPassport) {
        self.files.push(known);
        self.passports.push(passport);
    }

    /// Сначала пропавшие в этом скане (до скана файл был доступен), потом давно
    /// недоступные, внутри — по ID трека: при равенстве сходства `best_match`
    /// берёт первого.
    fn order(&mut self) {
        let mut both: Vec<(KnownFile, TrackPassport)> =
            std::mem::take(&mut self.files).into_iter().zip(std::mem::take(&mut self.passports)).collect();
        both.sort_by_key(|(known, _)| (!known.available, known.track));
        (self.files, self.passports) = both.into_iter().unzip();
    }

    fn take(&mut self, index: usize) -> KnownFile {
        self.passports.remove(index);
        self.files.remove(index)
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_types::{Format, Timestamp, TrackId, VersionId};

    use super::*;
    use crate::scan::{FoundFile, Tags};

    fn found(uri: &str) -> ScannedFile {
        ScannedFile {
            file: FoundFile {
                uri: uri.to_owned(),
                folder: "Music/".to_owned(),
                format: Format::Mp3,
                modified_at: Timestamp::from_millis(1),
                size: 10,
            },
            known: None,
            tags: Tags {
                title: Some("Song".to_owned()),
                artist: Some("Band".to_owned()),
                duration: Some(Duration::from_secs(180)),
                ..Tags::default()
            },
            readable: true,
        }
    }

    fn vanished(available: bool) -> (KnownFile, TrackPassport) {
        let track = TrackId::new();
        let known = KnownFile {
            source: SourceId::new(),
            version: VersionId::new(),
            track,
            modified_at: Some(Timestamp::from_millis(1)),
            size: Some(10),
            available,
        };
        let passport = TrackPassport {
            track,
            title: "Song".to_owned(),
            artist: "Band".to_owned(),
            album: None,
            duration: Some(Duration::from_secs(180)),
            mbid: None,
            sources: Vec::new(),
        };
        (known, passport)
    }

    /// Пропавший, исчезнувший в этом скане, и давно недоступный берутся оба,
    /// каждый одним новым файлом; сведённые снимаются с `diff`.
    #[test]
    fn each_vanished_file_is_taken_once_and_leaves_the_diff() {
        let (one, one_passport) = vanished(true);
        let (two, two_passport) = vanished(false);
        let mut diff = ScanDiff { missing: vec![one], gone: vec![two], ..ScanDiff::default() };
        let mut files = vec![found("/m/a.mp3"), found("/m/b.mp3"), found("/m/c.mp3")];

        let moved = pair(&mut diff, &mut files, vec![(one, one_passport), (two, two_passport)]);

        assert_eq!(moved, 2);
        assert!(diff.missing.is_empty() && diff.gone.is_empty());
        let taken: Vec<Option<KnownFile>> = files.iter().map(|file| file.known).collect();
        assert_eq!(taken.iter().flatten().count(), 2, "the third file has nobody left to take");
        assert!(taken[2].is_none());
    }

    /// Равные по сходству — берётся трек с меньшим ID, как бы ни лежали пропавшие.
    #[test]
    fn of_equal_candidates_the_older_track_wins() {
        let (a, a_passport) = vanished(true);
        let (b, b_passport) = vanished(true);
        let older = if a.track < b.track { a } else { b };
        let mut diff = ScanDiff { missing: vec![a, b], ..ScanDiff::default() };
        let mut files = vec![found("/m/new.mp3")];

        pair(&mut diff, &mut files, vec![(b, b_passport), (a, a_passport)]);

        assert_eq!(files[0].known, Some(older));
        assert_eq!(diff.missing.len(), 1);
    }

    /// Файл с нечитаемыми тегами и файл, который каталог уже знает, не сводятся.
    #[test]
    fn unreadable_and_known_files_are_left_alone() {
        let (gone, passport) = vanished(true);
        let mut unreadable = found("/m/a.mp3");
        unreadable.readable = false;
        let mut changed = found("/m/b.mp3");
        changed.known = Some(gone);
        let mut diff = ScanDiff { missing: vec![gone], ..ScanDiff::default() };
        let mut files = vec![unreadable, changed];

        let moved = pair(&mut diff, &mut files, vec![(gone, passport)]);

        assert_eq!(moved, 0);
        assert_eq!(diff.missing, vec![gone]);
        assert!(files[0].known.is_none());
    }
}
