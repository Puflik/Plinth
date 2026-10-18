//! Копия журнала в папке человека (C4, план 17.5, уровень 1) — главный
//! способ пережить переустановку: без Google-аккаунта и системной копии.
//!
//! Файлы пишет и читает Kotlin через SAF: ядро отдаёт копию байтами
//! ([`copy_of`]), осматривает найденные ([`inspect`]) и вливает их в журнал
//! ([`restore`]). Слияние — CRDT: сделанное до восстановления не теряется, а
//! одна и та же копия, влитая дважды, ничего не меняет.
//!
//! Копия сменённой установки (Р9д) — *прежняя*: журнал начат заново из базы
//! (`start_over`), и всё, что человек оставил, уже в нём, а чего нет — он
//! удалил. Данных такая копия не отдаёт и новостью не считается, отдаёт только
//! паспорта треков, которых нет в каталоге. Узнаёт её по имени файла тот, кому
//! имена известны: [`inspect_named`] и [`restore_named`]; [`inspect`] и
//! [`restore`] имён не знают, и все копии у них живые.

mod format;

use std::cmp::Reverse;
use std::collections::BTreeSet;

use plinth_library::db::Database;
use plinth_types::{CoreError, DeviceId, Timestamp};

pub use format::{Copy, decode, encode};

use crate::journal::{
    Journal, absorb_passports, absorbable_passports, catch_up, check_snapshot, contained_in, relink, state_of,
};

/// Что лежит в найденных копиях — для вопроса «Восстановить?». Лайки, плейлисты и
/// прослушивания считаются по всем прочитанным копиям, прежние тоже: это «что лежит в
/// папке», а предлагать ли — решает `news`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Found {
    pub likes: usize,
    pub playlists: usize,
    pub plays: usize,
    /// Время самой свежей копии; прочитанных нет — `None`.
    pub written_at: Option<Timestamp>,
    /// Есть что восстанавливать: живая копия с тем, чего журнал не знает, или паспорт
    /// трека из прежней копии. Одна прежняя копия данных новостью не делает.
    pub news: bool,
    /// Файлы, которые не прочлись: испорчены, обрезаны или не копии.
    pub unreadable: usize,
}

/// Итог [`restore`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Restored {
    /// Копии, принёсшие новое: живые, влитые в журнал, и прежние, отдавшие хотя бы один паспорт.
    pub merged: usize,
    pub unreadable: usize,
    /// Треки каталога, перешедшие на ID журнала (`relink`).
    pub relinked: usize,
}

/// Файл из папки: содержимое и, если его знает тот, кто читал папку, имя.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Candidate<'a> {
    /// Имя файла в папке. Копия установки называется `<установка>.journal`, папка могла
    /// дописать хвост (` (1)`): сменённую установку ядро узнаёт по началу имени. `None` —
    /// имени нет, копия живая.
    pub name: Option<&'a str>,
    pub bytes: &'a [u8],
}

/// Копия журнала для файла, записанная в `written_at`.
pub fn copy_of(journal: &Journal, written_at: Timestamp) -> Vec<u8> {
    encode(&journal.snapshot(), written_at)
}

/// Осматривает копии `files`: что в них вместе и есть ли новое для `journal`. Имён у
/// копий нет — все живые; с именами — [`inspect_named`].
pub fn inspect(journal: &Journal, files: &[&[u8]]) -> Result<Found, CoreError> {
    inspect_named(journal, &unnamed(files))
}

/// Осматривает копии `files` из папки: что в них вместе и есть ли новое для `journal`.
/// Прежние копии (сменённой установки и её предки, см. `classify`) новостью не считаются,
/// если не отдают паспорта, которых журналу не хватает.
pub fn inspect_named(journal: &Journal, files: &[Candidate<'_>]) -> Result<Found, CoreError> {
    let (copies, unreadable) = readable(files);
    let snapshots: Vec<&[u8]> = copies.iter().map(|named| named.copy.snapshot).collect();
    let state = state_of(&snapshots)?;
    let written_at = copies.iter().map(|named| named.copy.written_at).max();
    let sorted = classify(journal, copies, &state.superseded)?;
    let mut news = false;
    for copy in &sorted.live {
        news |= journal.news_in(copy.snapshot)?;
    }
    if !news && !sorted.replaced.is_empty() {
        let replaced: Vec<&[u8]> = sorted.replaced.iter().map(|copy| copy.snapshot).collect();
        news = !absorbable_passports(journal, &replaced)?.is_empty();
    }
    Ok(Found {
        likes: state.likes.len(),
        playlists: state.playlists.len(),
        plays: state.plays.len(),
        written_at,
        news,
        unreadable,
    })
}

/// Вливает копии `files` в журнал, пересобирает проекцию и переводит треки
/// каталога на ID журнала. Испорченные копии пропускаются и считаются. Имён у
/// копий нет — все живые; с именами — [`restore_named`].
pub fn restore(journal: &mut Journal, db: &Database, files: &[&[u8]]) -> Result<Restored, CoreError> {
    restore_named(journal, db, &unnamed(files))
}

/// Вливает копии `files` из папки в журнал, пересобирает проекцию и переводит
/// треки каталога на ID журнала. Живые копии вливаются целиком. Прежние данных не
/// отдают: из них берутся паспорта треков, на которые в журнале есть данные, а
/// паспорта нет, — свежие копии первыми. Испорченные копии пропускаются и считаются.
pub fn restore_named(journal: &mut Journal, db: &Database, files: &[Candidate<'_>]) -> Result<Restored, CoreError> {
    let (copies, unreadable) = readable(files);
    // Имён нет ни у одной — сменённых не узнать, и вычислять пометки незачем.
    let marked = if copies.iter().any(|named| named.name.is_some()) {
        let snapshots: Vec<&[u8]> = copies.iter().map(|named| named.copy.snapshot).collect();
        state_of(&snapshots)?.superseded
    } else {
        Vec::new()
    };
    let sorted = classify(journal, copies, &marked)?;
    let mut merged = 0;
    for copy in &sorted.live {
        if journal.merge_snapshot(copy.snapshot)? {
            merged += 1;
        }
    }
    // По одной: следующая прежняя копия видит уже дописанные паспорта.
    for copy in &sorted.replaced {
        if absorb_passports(journal, &[copy.snapshot])? > 0 {
            merged += 1;
        }
    }
    catch_up(journal, db)?;
    let relinked = relink(journal, db)?;
    log::info!(
        "mirror: restored {merged} of {} copies, {unreadable} unreadable, {relinked} tracks relinked",
        files.len()
    );
    Ok(Restored { merged, unreadable, relinked })
}

/// Копия, которая прочлась, вместе с именем её файла.
struct Named<'a> {
    name: Option<&'a str>,
    copy: Copy<'a>,
}

/// Копии папки по роли в «Восстановить?» и «Восстановить».
struct Sorted<'a> {
    /// Вливаются целиком, в порядке файлов.
    live: Vec<Copy<'a>>,
    /// Прежние: данных не отдают, отдают паспорта; свежие первыми.
    replaced: Vec<Copy<'a>>,
}

/// Делит прочитанные копии на живые и прежние (Р9д). `marked` — сменённые установки,
/// о которых знают сами копии: копия нового журнала помнит, что прежняя сменена, — так
/// на переустановке, которая берёт обе, и после двух порч подряд, где о первой знает
/// только копия второго журнала. Сменёнными считаются они и те, что помнит `journal`.
///
/// Прежняя копия — (а) та, имя файла которой *начинается* с `DeviceId` сменённой
/// установки (так же узнаётся своя копия в `Core`: папка могла дописать ` (1)`); (б) и та,
/// что целиком вложена в прежние по (а) ([`contained_in`]): предок — старая установка,
/// чью копию сменённая когда-то восстановила; её копия лежит в папке навсегда и воскресила
/// бы всё, что сменённая успела удалить. Предок паспортов не отдаёт — они в его потомке.
/// Остальные копии живые. Ложное срабатывание (живая копия, целиком лежащая в прежних)
/// безвредно: нового в ней нет по определению.
///
/// Без имён сменённых не узнать: все копии живые, и ничего не раскодируется.
fn classify<'a>(journal: &Journal, copies: Vec<Named<'a>>, marked: &[DeviceId]) -> Result<Sorted<'a>, CoreError> {
    if copies.iter().all(|named| named.name.is_none()) {
        return Ok(all_alive(copies));
    }
    let mut retired: BTreeSet<DeviceId> = journal.superseded();
    retired.extend(marked);
    let retired: Vec<String> = retired.iter().map(ToString::to_string).collect();
    let (mut replaced, rest): (Vec<_>, Vec<_>) = copies.into_iter().partition(|named| {
        named.name.is_some_and(|name| retired.iter().any(|device| name.starts_with(device.as_str())))
    });
    if replaced.is_empty() {
        return Ok(all_alive(rest));
    }
    replaced.sort_by_key(|named| Reverse(named.copy.written_at));
    let held: Vec<&[u8]> = replaced.iter().map(|named| named.copy.snapshot).collect();
    let mut live = Vec::new();
    for named in rest {
        if !contained_in(&held, named.copy.snapshot)? {
            live.push(named.copy);
        }
    }
    Ok(Sorted { live, replaced: replaced.into_iter().map(|named| named.copy).collect() })
}

fn all_alive(copies: Vec<Named<'_>>) -> Sorted<'_> {
    Sorted { live: copies.into_iter().map(|named| named.copy).collect(), replaced: Vec::new() }
}

fn unnamed<'a>(files: &[&'a [u8]]) -> Vec<Candidate<'a>> {
    files.iter().map(|&bytes| Candidate { name: None, bytes }).collect()
}

/// Прочитанные копии и сколько файлов не прочлось.
fn readable<'a>(files: &[Candidate<'a>]) -> (Vec<Named<'a>>, usize) {
    let mut copies = Vec::with_capacity(files.len());
    let mut unreadable = 0;
    for file in files {
        match decode(file.bytes).and_then(|copy| check_snapshot(copy.snapshot).map(|()| copy)) {
            Ok(copy) => copies.push(Named { name: file.name, copy }),
            Err(error) => {
                log::warn!("mirror: skipped a file: {error}");
                unreadable += 1;
            }
        }
    }
    (copies, unreadable)
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::PathBuf;
    use std::time::Duration;

    use plinth_library::db::Database;
    use plinth_library::model::TrackPassport;
    use plinth_types::{DeviceId, Timestamp, TrackId};

    use super::{Candidate, copy_of, inspect, inspect_named, restore_named};
    use crate::journal::{Journal, Op};

    const AT: Timestamp = Timestamp::from_millis(1_790_307_000_000);

    struct Scratch(PathBuf);

    impl Scratch {
        fn new() -> Self {
            Self(std::env::temp_dir().join(format!("plinth-mirror-{}", DeviceId::new())))
        }

        fn journal(&self) -> Journal {
            Journal::open(&self.0, DeviceId::new()).unwrap()
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn passport(track: TrackId, title: &str) -> TrackPassport {
        TrackPassport {
            track,
            title: title.to_owned(),
            artist: "Кино".to_owned(),
            album: None,
            duration: Some(Duration::from_secs(398)),
            mbid: None,
            sources: Vec::new(),
        }
    }

    /// Журнал, который помнит сменённые установки `gone` и держит лайк `track`.
    fn remembering(dir: &Scratch, gone: &[DeviceId], track: TrackId) -> Journal {
        let mut journal = dir.journal();
        for &device in gone {
            journal.record(&Op::Supersede { device }).unwrap();
        }
        journal.record(&Op::Like { track }).unwrap();
        journal
    }

    /// Имя файла копии установки в папке.
    fn file(device: DeviceId) -> String {
        format!("{device}.journal")
    }

    /// Копия, в которой есть лайк, которого журнал не знает.
    fn copy_with_a_like(dir: &Scratch) -> Vec<u8> {
        let mut journal = dir.journal();
        journal.record(&Op::Like { track: TrackId::new() }).unwrap();
        copy_of(&journal, AT)
    }

    /// Без имени ядро не узнаёт сменённых: все копии живые, как до Р9д.
    #[test]
    fn a_copy_without_a_name_is_live_whatever_the_journal_remembers() {
        let (here, there) = (Scratch::new(), Scratch::new());
        let gone = DeviceId::new();
        let journal = remembering(&here, &[gone], TrackId::new());
        let copy = copy_with_a_like(&there);
        let name = file(gone);

        let named = inspect_named(&journal, &[Candidate { name: Some(&name), bytes: &copy }]).unwrap();
        let unnamed = inspect(&journal, &[&copy]).unwrap();

        assert!(!named.news, "{named:?}");
        assert!(unnamed.news, "{unnamed:?}");
        assert_eq!((named.likes, unnamed.likes), (1, 1), "both count what lies in the folder");
    }

    /// Сменённую установку узнаёт начало имени: хвост папки (` (1)`) её не прячет, а то же имя
    /// посередине не делает копию прежней.
    #[test]
    fn a_replaced_copy_is_known_by_the_start_of_its_name() {
        let (here, there) = (Scratch::new(), Scratch::new());
        let gone = DeviceId::new();
        let journal = remembering(&here, &[gone], TrackId::new());
        let copy = copy_with_a_like(&there);
        let news =
            |name: String| inspect_named(&journal, &[Candidate { name: Some(&name), bytes: &copy }]).unwrap().news;

        assert!(!news(file(gone)));
        assert!(!news(format!("{} (1)", file(gone))));
        assert!(news(format!("a copy of {}", file(gone))), "only the start of the name counts");
        assert!(news("notes.journal".to_owned()));
    }

    /// Паспорта берутся из прежних копий свежими первыми — в каком бы порядке ни лежали файлы.
    #[test]
    fn the_freshest_replaced_copy_gives_the_passport() {
        let db = Database::open_in_memory().unwrap();
        let (first, second) = (Scratch::new(), Scratch::new());
        let (gone_old, gone_new, track) = (DeviceId::new(), DeviceId::new(), TrackId::new());
        let mut older = first.journal();
        older.record(&Op::Describe(passport(track, "Старое"))).unwrap();
        let older = copy_of(&older, AT);
        let mut newer = second.journal();
        newer.record(&Op::Describe(passport(track, "Новое"))).unwrap();
        let newer = copy_of(&newer, Timestamp::from_millis(AT.as_millis() + 60_000));
        let (old_name, new_name) = (file(gone_old), file(gone_new));
        let old_file = Candidate { name: Some(&old_name), bytes: &older };
        let new_file = Candidate { name: Some(&new_name), bytes: &newer };

        for files in [[old_file, new_file], [new_file, old_file]] {
            let here = Scratch::new();
            let mut journal = remembering(&here, &[gone_old, gone_new], track);

            let restored = restore_named(&mut journal, &db, &files).unwrap();

            assert_eq!(restored.merged, 1, "only the freshest copy gives something");
            assert_eq!(journal.passport(track).map(|found| found.title), Some("Новое".to_owned()));
        }
    }
}
