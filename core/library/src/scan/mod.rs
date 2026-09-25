//! Сканер библиотеки (D1, D2): обход папок, сравнение с каталогом, чтение
//! тегов, запись каталога — треков, версий, источников, артистов и альбомов.
//!
//! Этапы разделены, чтобы долгий скан не держал базу: обход и чтение
//! тегов идут без неё, запись — пачками, каждая своей транзакцией. Доступ к
//! базе даёт [`DbAccess`]: в ядре он берёт замок, и между пачками успевают
//! пройти чтения экранов.
//!
//! Каталог пересобирается сканом; пользовательское — в журнале (ADR 0007).
//! Поэтому пропавший файл только помечается недоступным: ID трека, на
//! который ссылаются лайки и плейлисты, остаётся.

mod catalog;
mod diff;
mod folder_config;
mod progress;
mod tags;
mod walker;

use std::path::{Path, PathBuf};

use plinth_types::{Availability, CoreError, SourceId, Timestamp, TrackId, VersionId};

pub use diff::{ScanDiff, diff};
pub use folder_config::FolderConfig;
pub use progress::{Progress, ScanPhase, ScanProgress};
pub use tags::{
    ArtistSplit, Artwork, FileNameOnly, FileTags, RawTags, TagReader, Tags, artwork, embedded_artwork, normalized,
    read_raw,
};
pub use walker::{FoundFile, Walk, walk};

use crate::db::Database;
use crate::db::repo::KnownFile;
use crate::model::{AudioSpec, Explicitness, Source, SourceLocation, Track, Version, VersionKind};
use progress::{REPORT_EVERY, count};

/// Сколько файлов пишется одной транзакцией.
const BATCH: usize = 200;

/// Доступ к базе на время одной работы: в ядре — под его замком.
pub trait DbAccess {
    fn run(&self, work: &mut dyn FnMut(&Database) -> Result<(), CoreError>) -> Result<(), CoreError>;
}

impl DbAccess for Database {
    fn run(&self, work: &mut dyn FnMut(&Database) -> Result<(), CoreError>) -> Result<(), CoreError> {
        work(self)
    }
}

/// Файл с тегами, готовый к записи в каталог.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ScannedFile {
    pub file: FoundFile,
    /// Каким каталог знал файл; `None` — новый.
    pub known: Option<KnownFile>,
    pub tags: Tags,
    /// Прочлись ли теги. Изменённый файл с битыми тегами каталог не трогает.
    pub readable: bool,
}

/// Итог скана — для лога и экрана.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ScanReport {
    pub found: u32,
    pub added: u32,
    pub changed: u32,
    pub returned: u32,
    pub missing: u32,
    /// Файлы, теги которых не прочлись: новые добавлены под именем файла,
    /// изменённые остались какими были и перечитаются следующим сканом.
    pub unreadable_files: u32,
    pub unreadable_folders: u32,
    pub missing_volumes: u32,
    /// Остановлен через колбэк хода; записанное до остановки остаётся.
    pub stopped: bool,
}

/// Весь скан: томa `roots`, папки `config`, теги — `reader`.
pub fn scan(
    db: &dyn DbAccess,
    roots: &[PathBuf],
    config: &FolderConfig,
    reader: &dyn TagReader,
    progress: Progress<'_>,
    now: Timestamp,
) -> Result<ScanReport, CoreError> {
    let stopped = ScanReport { stopped: true, ..ScanReport::default() };
    let Some(walked) = walk(roots, config, progress) else { return Ok(stopped) };
    let mut known = Default::default();
    db.run(&mut |db| {
        known = db.known_local_files()?;
        Ok(())
    })?;
    let diff = diff(&known, &walked);
    let mut report = ScanReport {
        found: count(walked.files.len()),
        added: count(diff.added.len()),
        changed: count(diff.changed.len()),
        returned: count(diff.returned.len()),
        missing: count(diff.missing.len()),
        unreadable_folders: count(walked.unreadable.len()),
        missing_volumes: count(walked.missing_roots.len()),
        ..ScanReport::default()
    };
    let Some((files, unreadable)) = read(&diff, reader, progress) else { return Ok(stopped) };
    report.unreadable_files = unreadable;

    let total = count(files.len());
    for (index, batch) in files.chunks(BATCH).enumerate() {
        db.run(&mut |db| db.in_transaction(|db| write(db, batch, now)))?;
        let done = count((index + 1) * BATCH).min(total);
        if !progress(ScanProgress { phase: ScanPhase::Writing, done, total }) {
            return Ok(ScanReport { stopped: true, ..report });
        }
    }
    db.run(&mut |db| db.in_transaction(|db| mark(db, &diff, now)))?;
    log::info!("scan: {report:?}");
    Ok(report)
}

/// Теги новых и изменённых файлов; сколько не прочлось. `None` — остановлен.
pub fn read(diff: &ScanDiff, reader: &dyn TagReader, progress: Progress<'_>) -> Option<(Vec<ScannedFile>, u32)> {
    let queue: Vec<(Option<KnownFile>, &FoundFile)> = diff
        .added
        .iter()
        .map(|file| (None, file))
        .chain(diff.changed.iter().map(|(known, file)| (Some(*known), file)))
        .collect();
    let total = count(queue.len());
    let mut unreadable = 0;
    let mut files = Vec::with_capacity(queue.len());
    for (index, (known, file)) in queue.into_iter().enumerate() {
        let (tags, readable) = match reader.read(Path::new(&file.uri)) {
            Ok(tags) => (tags, true),
            Err(error) => {
                log::warn!("scan: tags are unreadable: {error}");
                unreadable += 1;
                (Tags::default(), false)
            }
        };
        files.push(ScannedFile { file: file.clone(), known, tags, readable });
        let done = count(index + 1);
        if (done.is_multiple_of(REPORT_EVERY) || done == total)
            && !progress(ScanProgress { phase: ScanPhase::Reading, done, total })
        {
            return None;
        }
    }
    Some((files, unreadable))
}

/// Пишет файлы в каталог: новые — трек, версия и источник; изменённые —
/// поверх прежних, с теми же ID. Артисты и альбом — найденные по имени или
/// новые.
pub fn write(db: &Database, files: &[ScannedFile], now: Timestamp) -> Result<(), CoreError> {
    for scanned in files {
        let source = match scanned.known {
            None => add(db, scanned, now)?,
            // Без отметки скана файл перечитается в следующий раз.
            Some(_) if !scanned.readable => continue,
            Some(known) => update(db, scanned, &known, now)?,
        };
        db.save_file_stamp(source, scanned.file.modified_at, scanned.file.size, &scanned.file.folder)?;
    }
    Ok(())
}

/// Вернувшиеся — снова доступны, пропавшие — недоступны. Артисты и альбомы,
/// которых сменившиеся теги оставили без треков, уходят из каталога.
pub fn mark(db: &Database, diff: &ScanDiff, now: Timestamp) -> Result<(), CoreError> {
    for file in &diff.returned {
        db.set_availability(file.source, Availability::Available, now)?;
    }
    for file in &diff.missing {
        db.set_availability(file.source, Availability::Unavailable, now)?;
    }
    db.prune_catalog()
}

fn add(db: &Database, scanned: &ScannedFile, now: Timestamp) -> Result<SourceId, CoreError> {
    let tags = &scanned.tags;
    let track = Track {
        id: TrackId::new(),
        title: title(scanned),
        artist_credit: tags.artist.clone().unwrap_or_default(),
        artists: catalog::artist_ids(db, &tags.artists)?,
        mbid_work: None,
        added_at: now,
    };
    db.save_track(&track)?;
    let version = Version {
        id: VersionId::new(),
        track: track.id,
        kind: VersionKind::Original,
        explicitness: Explicitness::Unknown,
        duration: tags.duration,
        album: catalog::placement(db, tags)?,
        release_year: tags.year,
        mbid_recording: None,
        fingerprint: None,
    };
    db.save_version(&version)?;
    let source = local_source(SourceId::new(), version.id, scanned, now);
    db.save_source(&source)?;
    Ok(source.id)
}

fn update(db: &Database, scanned: &ScannedFile, known: &KnownFile, now: Timestamp) -> Result<SourceId, CoreError> {
    let tags = &scanned.tags;
    if let Some(mut track) = db.track(known.track)? {
        track.title = title(scanned);
        track.artist_credit = tags.artist.clone().unwrap_or_default();
        track.artists = catalog::artist_ids(db, &tags.artists)?;
        db.save_track(&track)?;
    }
    if let Some(mut version) = db.versions_of(known.track)?.into_iter().find(|v| v.id == known.version) {
        version.duration = tags.duration.or(version.duration);
        version.album = catalog::placement(db, tags)?;
        version.release_year = tags.year;
        db.save_version(&version)?;
    }
    db.save_source(&local_source(known.source, known.version, scanned, now))?;
    Ok(known.source)
}

/// Название из тегов, без него — имя файла без расширения.
fn title(scanned: &ScannedFile) -> String {
    scanned.tags.title.clone().filter(|t| !t.trim().is_empty()).unwrap_or_else(|| file_stem(&scanned.file.uri))
}

/// Звук — по содержимому файла, если теги прочлись; иначе формат по расширению.
fn local_source(id: SourceId, version: VersionId, scanned: &ScannedFile, now: Timestamp) -> Source {
    let file = &scanned.file;
    Source {
        id,
        version,
        location: SourceLocation::Local { uri: file.uri.clone() },
        audio: scanned.tags.audio.unwrap_or(AudioSpec {
            format: file.format,
            bitrate: None,
            sample_rate_hz: None,
            bit_depth: None,
        }),
        availability: Availability::Available,
        last_checked_at: Some(now),
    }
}

fn file_stem(uri: &str) -> String {
    Path::new(uri).file_stem().map_or_else(|| uri.to_owned(), |stem| stem.to_string_lossy().into_owned())
}
