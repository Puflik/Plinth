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
//! если его нет на месте и скан не лишён сведений о нём (папка, запись, том —
//! Р1.15): исчезнувший в этом скане ([`ScanDiff::missing`]) или давно
//! недоступный ([`ScanDiff::gone`]). Файл на месте своего трека не отдаёт: копия
//! рядом с живым оригиналом — новый трек; файл, который просто не увидели, тоже
//! не отдаёт — он не в этих списках. Файл, который скан уже завёл своим треком
//! раньше, сюда не попадает — им занимается `twin` (Р9в).

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
/// файлов накопил каталог. Так же, одним запросом, читаются свежие ID треков
/// пропавших (`known_local_files()`: перепривязка могла сменить ID трека после
/// того, как скан снял список знакомых файлов, Р9в, №19) и множество треков с
/// данными пользователя (Р9в, №11).
pub(super) fn relocate(db: &dyn DbAccess, diff: &mut ScanDiff, files: &mut [ScannedFile]) -> Result<usize, CoreError> {
    let any_new = files.iter().any(|file| file.known.is_none() && file.readable);
    if !any_new || (diff.missing.is_empty() && diff.gone.is_empty()) {
        return Ok(0);
    }
    let wanted: HashSet<SourceId> = diff.missing.iter().chain(&diff.gone).map(|known| known.source).collect();
    let mut fresh: HashMap<SourceId, KnownFile> = HashMap::with_capacity(wanted.len());
    let mut passports: HashMap<TrackId, TrackPassport> = HashMap::with_capacity(wanted.len());
    let mut with_data: HashSet<TrackId> = HashSet::new();
    db.run(&mut |db| {
        fresh.clear();
        passports.clear();
        for known in db.known_local_files()?.into_values() {
            if wanted.contains(&known.source) {
                fresh.insert(known.source, known);
            }
        }
        let tracks: HashSet<TrackId> = fresh.values().map(|known| known.track).collect();
        for passport in db.track_passports()? {
            if tracks.contains(&passport.track) {
                passports.insert(passport.track, passport);
            }
        }
        with_data = db.tracks_with_user_data()?;
        Ok(())
    })?;
    let vanished = diff
        .missing
        .iter()
        .chain(&diff.gone)
        .filter_map(|known| {
            let known = fresh.get(&known.source)?;
            passports.get(&known.track).map(|passport| (*known, passport.clone()))
        })
        .collect();
    Ok(pair(diff, files, vanished, &with_data))
}

/// Сводит новые файлы с пропавшими. Новые идут по порядку `files` (обход
/// сортирует их по пути). Сначала раздаются пропавшие с данными пользователя
/// (`with_data`), потом остальные; в каждой группе пары идут лучшими вперёд
/// ([`TrackPassport::pair_up`]), а не по очереди пути; при равном сходстве первым
/// идёт исчезнувший в этом скане (`diff.missing`), затем давно недоступный
/// (`diff.gone`), затем более старый трек (меньший ID). Пропавший берётся один раз,
/// новый файл сводится не больше чем с одним. Сведённый пропавший снимается с
/// `diff`: иначе скан скрыл бы его снова.
pub(super) fn pair(
    diff: &mut ScanDiff,
    files: &mut [ScannedFile],
    vanished: Vec<(KnownFile, TrackPassport)>,
    with_data: &HashSet<TrackId>,
) -> usize {
    let eligible: Vec<usize> =
        files.iter().enumerate().filter(|(_, file)| file.known.is_none() && file.readable).map(|(i, _)| i).collect();
    let wanted: Vec<TrackPassport> = eligible.iter().map(|&index| passport_of(&files[index])).collect();
    let (owners, others): (Vec<_>, Vec<_>) =
        vanished.into_iter().partition(|(known, _)| with_data.contains(&known.track));

    let mut taken: HashSet<SourceId> = HashSet::new();
    let mut free: Vec<usize> = (0..wanted.len()).collect();
    for group in [owners, others] {
        let (vanished_files, candidates): (Vec<KnownFile>, Vec<TrackPassport>) = group.into_iter().unzip();
        let remaining: Vec<TrackPassport> = free.iter().map(|&index| wanted[index].clone()).collect();
        let pairs = TrackPassport::pair_up(&remaining, &candidates, |index| {
            (!vanished_files[index].available, vanished_files[index].track)
        });
        let mut used: HashSet<usize> = HashSet::new();
        for pair in pairs {
            let index = free[pair.wanted];
            used.insert(index);
            let known = vanished_files[pair.candidate];
            taken.insert(known.source);
            files[eligible[index]].known = Some(known);
        }
        free.retain(|index| !used.contains(index));
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

        let moved = pair(&mut diff, &mut files, vec![(one, one_passport), (two, two_passport)], &HashSet::new());

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

        pair(&mut diff, &mut files, vec![(b, b_passport), (a, a_passport)], &HashSet::new());

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

        let moved = pair(&mut diff, &mut files, vec![(gone, passport)], &HashSet::new());

        assert_eq!(moved, 0);
        assert_eq!(diff.missing, vec![gone]);
        assert!(files[0].known.is_none());
    }
}
