//! Двойник отдаёт файл (Р9в, находка 2 ревью R1): сведение перенесённых за
//! пределами одного скана.
//!
//! Файл, который скан завёл своим треком, пока оригинал был жив (копия на SD-карту
//! между шагами «скопировать» и «удалить»; перенос посреди обхода) или читался не до
//! конца, дальше идёт как «знакомый» и к сведению перенесённых ([`super::moved`])
//! не допускается. Когда оригинал пропадает, а на нём лайки, плейлисты и история,
//! они остаются на скрытом треке. Здесь пропавший трек с данными пользователя ищет
//! среди живых файлов каталога **двойника** — трек без данных с тем же ключом
//! паспорта, подходящим сходством и альбомом — и забирает его файл: тот же источник
//! получает новый путь (ID трека, ID источника, дата добавления прежние), трек
//! двойника уходит из каталога. Журнал не пишется.
//!
//! Условия: пропавший лежал под корнем тома, который скан обошёл (вынутая карта и
//! «перенесли» неотличимы — данные карты давно лежащей копии не отдаются; корень, на
//! который обход не попал, не обойдён); двойник — файл, который скан видел, теги
//! которого в этом скане прочлись, с того же альбома, что и пропавший (нормализованное
//! название равно, либо альбома нет у обоих): кандидаты — весь каталог, а не файлы
//! этого скана, и «Intro» с другого альбома не должен забирать чужой лайк; у трека
//! двойника нет данных пользователя, одна версия и один источник — проверяется заново
//! внутри транзакции, в которой он уходит.

use std::collections::{HashMap, HashSet};
use std::path::{MAIN_SEPARATOR, Path};

use plinth_types::{CoreError, SourceId, TrackId};

use super::walker::{FoundFile, Walk, bare_root};
use super::{BATCH, DbAccess, Progress, ScanDiff, ScanPhase, ScanProgress, ScannedFile, TagReader, Tags, Volumes};
use super::{count, write};
use crate::db::Database;
use crate::db::repo::KnownFile;
use crate::model::{Pairing, TrackPassport};
use crate::text::normalize;

/// Всё, что этапу нужно от скана.
pub(super) struct Context<'a> {
    pub db: &'a dyn DbAccess,
    pub reader: &'a dyn TagReader,
    pub volumes: &'a Volumes,
    /// Знакомые файлы на начало скана: по ним находится путь пропавшего.
    pub known: &'a HashMap<String, KnownFile>,
    pub walked: &'a Walk,
    /// Новые и изменённые файлы этого скана.
    pub files: &'a [ScannedFile],
    pub now: plinth_types::Timestamp,
}

#[derive(Debug, Default)]
pub(super) struct Adopted {
    /// Сколько пар сведено.
    pub moved: usize,
    /// Из них пропавших в этом скане (`diff.missing`).
    pub from_missing: usize,
    pub stopped: bool,
}

/// Одна пара: пропавший трек с данными и файл двойника.
struct Plan {
    owner: KnownFile,
    vanished_now: bool,
    twin: KnownFile,
    file: FoundFile,
    tags: Tags,
}

/// Состояние каталога после основной записи скана.
#[derive(Default)]
struct Fresh {
    known: HashMap<String, KnownFile>,
    passports: HashMap<TrackId, TrackPassport>,
    with_data: HashSet<TrackId>,
}

/// Отдаёт пропавшим трекам с данными файлы их двойников; сведённые снимаются с
/// `diff`, чтобы `mark` не скрыл их заново. Зовётся после основной записи, до `mark`.
pub(super) fn adopt(ctx: &Context<'_>, diff: &mut ScanDiff, progress: Progress<'_>) -> Result<Adopted, CoreError> {
    // Ворота в памяти. Скан, в котором ничего не пропало и не изменилось, этап не
    // запускает: пропавший с данными, которому двойника нет, иначе стоил бы лишних
    // запросов каждому скану. Прочее: пропавшие, чей том скан обошёл.
    let anything_vanished = !diff.missing.is_empty() || !diff.gone.is_empty();
    let anything_moved_or_changed = !diff.missing.is_empty() || !diff.changed.is_empty();
    if !anything_vanished || !anything_moved_or_changed {
        return Ok(Adopted::default());
    }
    // Пропавшие, чей том скан обошёл: корень из `volumes.ready`, который обход нашёл
    // (корня, которого не оказалось, обход не видел — вынутая карта).
    let absent: HashSet<String> = ctx.walked.missing_roots.iter().map(|root| bare_root(Path::new(root))).collect();
    let roots: Vec<String> =
        ctx.volumes.ready.iter().map(|root| bare_root(root)).filter(|root| !absent.contains(root)).collect();
    let uri_of: HashMap<SourceId, &str> = ctx.known.iter().map(|(uri, file)| (file.source, uri.as_str())).collect();
    let vanished: Vec<(KnownFile, bool)> = diff
        .missing
        .iter()
        .map(|known| (*known, true))
        .chain(diff.gone.iter().map(|known| (*known, false)))
        .filter(|(known, _)| uri_of.get(&known.source).is_some_and(|uri| under_a_root(uri, &roots)))
        .collect();
    if vanished.is_empty() {
        return Ok(Adopted::default());
    }

    let mut fresh = Fresh::default();
    ctx.db.run(&mut |db| {
        fresh = Fresh {
            known: db.known_local_files()?,
            passports: db.track_passports()?.into_iter().map(|passport| (passport.track, passport)).collect(),
            with_data: db.tracks_with_user_data()?,
        };
        Ok(())
    })?;
    let plan = plan(ctx, diff, &vanished, &fresh);
    if plan.is_empty() {
        return Ok(Adopted::default());
    }

    let total = count(plan.len());
    let mut adopted = Adopted::default();
    let mut taken: HashSet<SourceId> = HashSet::new();
    for batch in plan.chunks(BATCH) {
        let mut done: Vec<bool> = Vec::new();
        ctx.db.run(&mut |db| {
            done = db.in_transaction(|db| {
                let with_data = db.tracks_with_user_data()?;
                batch.iter().map(|item| apply(db, item, &with_data, ctx.now)).collect::<Result<Vec<_>, _>>()
            })?;
            Ok(())
        })?;
        for (item, _) in batch.iter().zip(&done).filter(|(_, done)| **done) {
            taken.insert(item.owner.source);
            adopted.moved += 1;
            adopted.from_missing += usize::from(item.vanished_now);
        }
        if !progress(ScanProgress { phase: ScanPhase::Writing, done: total, total }) {
            adopted.stopped = true;
            break;
        }
    }
    // Сведённые не должны попасть в `mark`: он скрыл бы их заново. Набор и один проход,
    // а не проход по `diff` на каждую пару.
    diff.missing.retain(|known| !taken.contains(&known.source));
    diff.gone.retain(|known| !taken.contains(&known.source));
    Ok(adopted)
}

/// Пары «пропавший с данными ↔ двойник» с прочитанными тегами двойников.
fn plan(ctx: &Context<'_>, diff: &ScanDiff, vanished: &[(KnownFile, bool)], fresh: &Fresh) -> Vec<Plan> {
    let by_source: HashMap<SourceId, &KnownFile> = fresh.known.values().map(|file| (file.source, file)).collect();
    let mut owners: Vec<(KnownFile, bool, &TrackPassport)> = vanished
        .iter()
        .filter_map(|(known, now)| {
            let current = by_source.get(&known.source)?;
            if !fresh.with_data.contains(&current.track) {
                return None;
            }
            Some((**current, *now, fresh.passports.get(&current.track)?))
        })
        .collect();
    // Исчезнувшие в этом скане — первыми, внутри — по ID трека.
    owners.sort_by_key(|(known, now, _)| (!*now, known.track));
    if owners.is_empty() {
        return Vec::new();
    }

    let keys: HashSet<(String, String)> = owners.iter().map(|(_, _, passport)| passport.key()).collect();
    let seen: HashSet<&str> = ctx.walked.files.iter().map(|file| file.uri.as_str()).collect();
    let unreadable: HashSet<&str> =
        ctx.files.iter().filter(|file| !file.readable).map(|file| file.file.uri.as_str()).collect();
    let vanished_now: HashSet<SourceId> = diff.missing.iter().chain(&diff.gone).map(|known| known.source).collect();
    let mut files_of: HashMap<TrackId, usize> = HashMap::new();
    for file in fresh.known.values() {
        *files_of.entry(file.track).or_default() += 1;
    }
    let twins: Vec<(&str, KnownFile, &TrackPassport)> = fresh
        .known
        .iter()
        .filter(|(uri, file)| {
            file.available
                && seen.contains(uri.as_str())
                && !unreadable.contains(uri.as_str())
                && !vanished_now.contains(&file.source)
                && !fresh.with_data.contains(&file.track)
                && files_of.get(&file.track) == Some(&1)
        })
        .filter_map(|(uri, file)| {
            let passport = fresh.passports.get(&file.track)?;
            keys.contains(&passport.key()).then_some((uri.as_str(), *file, passport))
        })
        .collect();

    let wanted: Vec<TrackPassport> = owners.iter().map(|(_, _, passport)| (*passport).clone()).collect();
    let candidates: Vec<TrackPassport> = twins.iter().map(|(_, _, passport)| (*passport).clone()).collect();
    let pairs = pair_by_album(&wanted, &candidates, |index| twins[index].1.track);

    let found: HashMap<&str, &FoundFile> = ctx.walked.files.iter().map(|file| (file.uri.as_str(), file)).collect();
    let mut plan = Vec::with_capacity(pairs.len());
    for pair in pairs {
        let (uri, twin, _) = &twins[pair.candidate];
        let (owner, vanished_now, _) = &owners[pair.wanted];
        let Some(file) = found.get(uri) else { continue };
        match ctx.reader.read(Path::new(uri)) {
            Ok(tags) => {
                plan.push(Plan { owner: *owner, vanished_now: *vanished_now, twin: *twin, file: (*file).clone(), tags })
            }
            Err(error) => log::warn!("scan: tags of a twin are unreadable: {error}"),
        }
    }
    plan
}

/// [`TrackPassport::pair_up`] в пределах одного альбома: двойник совпадает с пропавшим
/// по нормализованному названию альбома, либо альбома нет ни у того, ни у другого —
/// «Intro» с другого альбома двойником не бывает. Условие делит желающих и кандидатов
/// на классы по альбому, а `pair_up` раздаёт пары в каждом классе: пара, которой
/// условие не позволяет, не занимает ни желающего, ни кандидата, и ничьей законной
/// пары не отнимает. Порядок результата — по желающим.
fn pair_by_album(
    wanted: &[TrackPassport],
    candidates: &[TrackPassport],
    rank: impl Fn(usize) -> TrackId,
) -> Vec<Pairing> {
    let mut classes: HashMap<Option<String>, (Vec<usize>, Vec<usize>)> = HashMap::new();
    for (index, passport) in wanted.iter().enumerate() {
        classes.entry(album_of(passport)).or_default().0.push(index);
    }
    for (index, passport) in candidates.iter().enumerate() {
        classes.entry(album_of(passport)).or_default().1.push(index);
    }
    let mut pairs = Vec::new();
    for (wanted_at, candidates_at) in classes.into_values() {
        let class_wanted: Vec<TrackPassport> = wanted_at.iter().map(|&at| wanted[at].clone()).collect();
        let class_candidates: Vec<TrackPassport> = candidates_at.iter().map(|&at| candidates[at].clone()).collect();
        for pair in TrackPassport::pair_up(&class_wanted, &class_candidates, |at| rank(candidates_at[at])) {
            pairs.push(Pairing { wanted: wanted_at[pair.wanted], candidate: candidates_at[pair.candidate], ..pair });
        }
    }
    pairs.sort_by_key(|pair| pair.wanted);
    pairs
}

/// Альбом паспорта для сверки — как в `likeness`: без регистра и знаков; `None` — альбома нет.
fn album_of(passport: &TrackPassport) -> Option<String> {
    passport.album.as_deref().map(normalize)
}

/// Одна пара внутри транзакции: ID треков — заново по версиям; двойник без данных, с
/// одной версией и одним источником уходит, и файл ложится на источник пропавшего.
/// `false` — пара не сведена, каталог не тронут.
fn apply(
    db: &Database,
    item: &Plan,
    with_data: &HashSet<TrackId>,
    now: plinth_types::Timestamp,
) -> Result<bool, CoreError> {
    let (Some(owner), Some(twin)) = (db.track_of_version(item.owner.version)?, db.track_of_version(item.twin.version)?)
    else {
        return Ok(false);
    };
    if owner == twin || with_data.contains(&twin) {
        return Ok(false);
    }
    let versions = db.versions_of(twin)?;
    if versions.len() != 1 || db.sources_of(versions[0].id)?.len() != 1 {
        return Ok(false);
    }
    db.delete_track(twin)?;
    let scanned = ScannedFile {
        file: item.file.clone(),
        known: Some(KnownFile { track: owner, ..item.owner }),
        tags: item.tags.clone(),
        readable: true,
    };
    write(db, &[scanned], now)?;
    Ok(true)
}

/// Путь лежит под корнем одного из обойденных томов; граница — целый сегмент.
fn under_a_root(uri: &str, roots: &[String]) -> bool {
    roots.iter().any(|root| uri.strip_prefix(root.as_str()).is_some_and(|rest| rest.starts_with(MAIN_SEPARATOR)))
}
