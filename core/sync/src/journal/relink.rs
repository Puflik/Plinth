//! Перепривязка (C4, ADR 0007): трек журнала, которого нет в каталоге,
//! узнаётся по паспорту, и данные песни сходятся на одном ID. Так после
//! переустановки и нового скана лайки, плейлисты и история снова на своих
//! треках, а две установки, восстановившие копии друг друга, видят данные
//! обеих и сходятся на одном треке (Р9г).
//!
//! Пара — тот же ключ (`key`), `likeness` не `None` и альбомы не противоречат
//! (`albums_agree`: известны у обоих и различаются — это другая песня). Правила
//! сравнения общие со сканом, который узнаёт перенесённый файл (Р1.2). В обоих
//! шагах пары раздаются разом, лучшими вперёд (`TrackPassport::pair_up_where`):
//! по убыванию сходства, при равном — сначала трек, который можно сыграть, потом
//! младший ID. Недоступность — только ранг, не фильтр: единственная копия на
//! вынутой карте всё равно берёт данные.
//!
//! Два шага, свободные раньше занятых:
//!
//! 1. Потерянный паспорт и *свободный* трек каталога — без данных и паспорта в
//!    журнале: данных двигать не надо, каталог берёт ID из журнала.
//! 2. Потерянный паспорт без вариантов провайдера и *занятый* трек: на него
//!    новая установка успела поставить свои данные до перепривязки (нажали
//!    «играть» посреди скана — ревью v0.2, №1) или та же песня есть у другой
//!    установки с её ID (Р9г, №6). Данные двух треков складываются на тот, чей
//!    ID **меньше**: он один на все установки и не зависит от того, кто смотрит,
//!    поэтому две установки, восстановившие копии друг друга, независимо
//!    выбирают одного победителя. Данные трека с большим ID (`from`) переходят
//!    на меньший (`to`), а паспорт `from` снимается (`Op::Retire`): слитый трек
//!    не возвращается позже дублем песни. Если меньший ID у потерянного,
//!    каталог ещё и переходит на него.
//!
//! Журнал пишется первым, каталог вторым: оборванный шаг доделает следующий
//! `relink` (трек каталога без данных и паспорта свободен, потерянный паспорт
//! потерян — шаг 1 свяжет их).

use std::collections::{BTreeSet, HashMap, HashSet};

use plinth_library::db::Database;
use plinth_library::model::{OnlineTrack, PlayEvent, PlaylistEntry, Rating, TrackPassport};
use plinth_types::{CoreError, EntityId, Timestamp, TrackId};

use super::passport::referenced;
use super::projection::record_and_project_all;
use super::{Journal, JournalState, Op};

/// Переводит треки каталога на ID журнала по паспортам и складывает данные одной
/// песни на меньший ID; сколько треков переведено, слито и сетевых восстановлено.
/// Перевод на ID журнала — одной транзакцией базы; журнал меняется только при
/// слиянии с треком, у которого уже есть данные.
pub fn relink(journal: &mut Journal, db: &Database) -> Result<usize, CoreError> {
    let state = journal.state();
    let catalog = db.track_passports()?;
    let known: BTreeSet<TrackId> = catalog.iter().map(|p| p.track).collect();
    let lost: Vec<TrackPassport> = state.passports.iter().filter(|p| !known.contains(&p.track)).cloned().collect();
    if lost.is_empty() {
        return Ok(0);
    }
    let mut claimed = referenced(&state);
    claimed.extend(state.passports.iter().map(|p| p.track));
    // Парой потерянному бывает только трек с тем же ключом: остальные дальше не идут.
    let keys: HashSet<(String, String)> = lost.iter().map(TrackPassport::key).collect();
    let (mut free, mut held) = (Vec::new(), Vec::new());
    for track in catalog {
        if keys.contains(&track.key()) {
            if claimed.contains(&track.track) { held.push(track) } else { free.push(track) }
        }
    }
    let playable = if free.is_empty() && held.is_empty() { HashSet::new() } else { db.playable_tracks()? };
    let mut found: HashMap<usize, usize> = TrackPassport::pair_up_where(
        &lost,
        &free,
        |index| !playable.contains(&free[index].track),
        TrackPassport::albums_agree,
    )
    .into_iter()
    .map(|pair| (pair.wanted, pair.candidate))
    .collect();
    let now = Timestamp::now();
    let mut unmatched: Vec<TrackPassport> = Vec::new();
    let (moved, restored) = db.in_transaction(|db| {
        let (mut moved, mut restored) = (0, 0);
        for (index, passport) in lost.iter().enumerate() {
            if let Some(candidate) = found.remove(&index) {
                db.rekey_track(free[candidate].track, passport.track)?;
                moved += 1;
            } else if db.restore_online_track(passport.track, &online(passport), now)? {
                restored += 1;
            } else if passport.sources.is_empty() {
                unmatched.push(passport.clone());
            }
        }
        Ok((moved, restored))
    })?;
    // Пары пишутся пачками по `FOLD_CHUNK` операций, пара — целиком в одной пачке: правка на
    // пару заново разбирала бы журнал, а пар у двух установок с общей библиотекой тысячи.
    let by_track = ByTrack::of(&state);
    let (mut ops, mut adopted, mut merged) = (Vec::new(), Vec::new(), 0);
    for pair in TrackPassport::pair_up_where(
        &unmatched,
        &held,
        |index| !playable.contains(&held[index].track),
        TrackPassport::albums_agree,
    ) {
        let (gone, local) = (unmatched[pair.wanted].track, held[pair.candidate].track);
        // Данные встают на меньший ID: он один на все установки.
        let (from, to) = if gone < local { (local, gone) } else { (gone, local) };
        ops.extend(by_track.fold_ops(from, to));
        ops.push(Op::Retire { track: from });
        if from == local {
            // Меньший ID у потерянного: каталог переходит на него.
            adopted.push((local, gone));
        }
        merged += 1;
        if ops.len() >= FOLD_CHUNK {
            write_folds(journal, db, &mut ops, &mut adopted)?;
        }
    }
    write_folds(journal, db, &mut ops, &mut adopted)?;
    log::info!(
        "relink: {moved} of {} tracks of the journal found in the catalog, {merged} merged with tracks that already had data, {restored} online tracks restored",
        state.passports.len()
    );
    Ok(moved + merged + restored)
}

/// Сколько операций журнала ложится одной правкой при слиянии. `Journal::record_all`
/// растёт от размера пачки квадратом: 12 000 прослушиваний одной правкой — 5,3 с (debug),
/// пачками по 500 — 0,23 с.
const FOLD_CHUNK: usize = 500;

/// Пишет накопленные операции слияния одной правкой журнала, затем переводит каталог на
/// ID потерянных (`adopted`: трек каталога, ID журнала). Сначала журнал, потом каталог:
/// оборванный повтор доделает перепривязка — трек без данных и паспорта свободен.
fn write_folds(
    journal: &mut Journal,
    db: &Database,
    ops: &mut Vec<Op>,
    adopted: &mut Vec<(TrackId, TrackId)>,
) -> Result<(), CoreError> {
    if ops.is_empty() {
        return Ok(());
    }
    record_and_project_all(journal, db, ops)?;
    db.in_transaction(|db| adopted.iter().try_for_each(|&(local, gone)| db.rekey_track(local, gone)))?;
    ops.clear();
    adopted.clear();
    Ok(())
}

/// Данные журнала по трекам: слияние берёт данные двух треков, а не разбирает
/// журнал заново на каждую пару.
struct ByTrack<'a> {
    likes: HashSet<TrackId>,
    ratings: HashMap<TrackId, Rating>,
    entries: HashMap<TrackId, Vec<&'a PlaylistEntry>>,
    plays: HashMap<TrackId, Vec<&'a PlayEvent>>,
}

impl<'a> ByTrack<'a> {
    fn of(state: &'a JournalState) -> Self {
        let mut entries: HashMap<TrackId, Vec<&PlaylistEntry>> = HashMap::new();
        for entry in &state.entries {
            entries.entry(entry.track).or_default().push(entry);
        }
        let mut plays: HashMap<TrackId, Vec<&PlayEvent>> = HashMap::new();
        for play in &state.plays {
            plays.entry(play.track).or_default().push(play);
        }
        Self {
            likes: state.likes.iter().copied().collect(),
            ratings: state.ratings.iter().copied().collect(),
            entries,
            plays,
        }
    }

    /// Операции, которые кладут данные трека `from` на трек `to` (лайк объединяется,
    /// оценка и плейлисты `from` побеждают, прослушивания копируются) и снимают их с
    /// `from`. Записи, которые заводятся на `to`, получают идентификатор от прежней,
    /// `from` и `to` ([`derived`]). Повтор ничего не задвоит: прослушивание и запись
    /// плейлиста с теми же признаками на `to` не добавляются второй раз.
    fn fold_ops(&self, from: TrackId, to: TrackId) -> Vec<Op> {
        let mut ops = Vec::new();
        if self.likes.contains(&from) {
            ops.push(Op::Like { track: to });
            ops.push(Op::Unlike { track: from });
        }
        if let Some(rating) = self.ratings.get(&from) {
            ops.push(Op::Rate { track: to, rating: Some(*rating) });
            ops.push(Op::Rate { track: from, rating: None });
        }
        let on_to: &[&PlaylistEntry] = self.entries.get(&to).map_or(&[], Vec::as_slice);
        for entry in self.entries.get(&from).into_iter().flatten() {
            if !on_to.iter().any(|other| other.playlist == entry.playlist) {
                ops.push(Op::AddEntry(PlaylistEntry {
                    id: derived(entry.id, from, to),
                    track: to,
                    ..(*entry).clone()
                }));
            }
            ops.push(Op::RemoveEntry { entry: entry.id });
        }
        let on_to: &[&PlayEvent] = self.plays.get(&to).map_or(&[], Vec::as_slice);
        for play in self.plays.get(&from).into_iter().flatten() {
            let there =
                on_to.iter().any(|other| other.started_at == play.started_at && other.listened == play.listened);
            if !there {
                ops.push(Op::Play(PlayEvent { id: derived(play.id, from, to), track: to, ..**play }));
            }
        }
        ops
    }
}

/// Идентификатор записи, которую перепривязка заводит на треке `to` вместо записи `id`
/// с трека `from`: `id ^ from ^ to`. Зависит только от этих трёх, поэтому две установки,
/// переложившие одни и те же данные порознь, заведут одну и ту же запись, а не две. И не
/// зависит от пути: данные, прошедшие `c → b`, а потом `b → a`, получают тот же идентификатор,
/// что и пришедшие `c → a` напрямую (`id ^ c ^ b ^ b ^ a == id ^ c ^ a`). Версия 7 и вариант
/// RFC 9562 сохраняются: слагаемых всегда нечётное число, и фиксированные биты складываются
/// сами с собой.
fn derived<I: EntityId>(id: I, from: TrackId, to: TrackId) -> I {
    let mut bytes = *id.as_bytes();
    for ((byte, from), to) in bytes.iter_mut().zip(from.as_bytes()).zip(to.as_bytes()) {
        *byte ^= from ^ to;
    }
    I::from_bytes(bytes)
}

/// Сетевой трек по паспорту (E3): его варианты у провайдера и описание.
/// Без вариантов — трек устройства, заводить нечего.
fn online(passport: &TrackPassport) -> OnlineTrack {
    OnlineTrack {
        title: passport.title.clone(),
        artist: Some(passport.artist.clone()).filter(|artist| !artist.is_empty()),
        album: passport.album.clone(),
        number: None,
        year: None,
        duration: passport.duration,
        mbid: passport.mbid,
        sources: passport.sources.clone(),
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_library::model::{OutputDevice, PlayEvent, PlaylistEntry, Rating};
    use plinth_types::{EntityId, PlayEventId, PlaylistEntryId, PlaylistId, Position, Timestamp, TrackId};

    use super::{ByTrack, JournalState, Op, derived};

    fn play(track: TrackId, started_at: i64, listened_secs: u64) -> PlayEvent {
        PlayEvent {
            id: PlayEventId::new(),
            track,
            version: None,
            source: None,
            started_at: Timestamp::from_millis(started_at),
            utc_offset_minutes: 0,
            listened: Duration::from_secs(listened_secs),
            track_length: None,
            skipped_at: None,
            output: OutputDevice::Speaker,
            previous_track: None,
        }
    }

    fn entry(playlist: PlaylistId, track: TrackId) -> PlaylistEntry {
        PlaylistEntry {
            id: PlaylistEntryId::new(),
            playlist,
            track,
            position: Position::first(),
            added_at: Timestamp::from_millis(7),
        }
    }

    #[test]
    fn a_derived_id_is_stable_and_differs_by_record_and_by_both_tracks() {
        let (id, from, to) = (PlayEventId::new(), TrackId::new(), TrackId::new());

        assert_eq!(derived(id, from, to), derived(id, from, to));
        assert_ne!(derived(id, from, to), id);
        assert_ne!(derived(id, from, to), derived(PlayEventId::new(), from, to));
        assert_ne!(derived(id, from, to), derived(id, TrackId::new(), to));
        assert_ne!(derived(id, from, to), derived(id, from, TrackId::new()));
    }

    /// Данные, прошедшие `c → b → a`, и пришедшие `c → a` напрямую, получают один ID.
    #[test]
    fn a_derived_id_does_not_depend_on_the_path() {
        let (id, a, b, c) = (PlaylistEntryId::new(), TrackId::new(), TrackId::new(), TrackId::new());

        assert_eq!(derived(derived(id, c, b), b, a), derived(id, c, a));
    }

    /// UUIDv7 (версия — старшая половина байта 6) и вариант RFC 9562 (два старших бита байта 8).
    #[test]
    fn a_derived_id_stays_a_version_7_uuid() {
        let (from, to) = (TrackId::new(), TrackId::new());

        let check = |bytes: &[u8; 16]| assert_eq!((bytes[6] >> 4, bytes[8] >> 6), (7, 0b10));
        check(derived(PlayEventId::new(), from, to).as_bytes());
        check(derived(PlaylistEntryId::new(), from, to).as_bytes());
    }

    #[test]
    fn nothing_on_the_track_nothing_to_do() {
        let (from, to) = (TrackId::new(), TrackId::new());
        let mut state = JournalState::default();
        // Чужие данные не мешают.
        state.likes.push(TrackId::new());
        state.plays.push(play(TrackId::new(), 10, 100));

        assert_eq!(ByTrack::of(&state).fold_ops(from, to), []);
    }

    #[test]
    fn a_like_and_a_rating_move_to_the_other_track() {
        let (from, to) = (TrackId::new(), TrackId::new());
        let rating = Rating::new(4).unwrap();
        let mut state = JournalState::default();
        state.likes.push(from);
        state.ratings.push((from, rating));

        assert_eq!(
            ByTrack::of(&state).fold_ops(from, to),
            [
                Op::Like { track: to },
                Op::Unlike { track: from },
                Op::Rate { track: to, rating: Some(rating) },
                Op::Rate { track: from, rating: None },
            ]
        );
    }

    /// Запись плейлиста переезжает под выведенным ID, остальное при ней остаётся.
    #[test]
    fn a_playlist_entry_moves_under_a_derived_id() {
        let (from, to, playlist) = (TrackId::new(), TrackId::new(), PlaylistId::new());
        let moving = entry(playlist, from);
        let mut state = JournalState::default();
        state.entries.push(moving.clone());

        assert_eq!(
            ByTrack::of(&state).fold_ops(from, to),
            [
                Op::AddEntry(PlaylistEntry { id: derived(moving.id, from, to), track: to, ..moving.clone() }),
                Op::RemoveEntry { entry: moving.id },
            ]
        );
    }

    /// Песня уже в плейлисте у `to`: запись `from` только снимается.
    #[test]
    fn a_playlist_the_target_already_has_gets_no_second_entry() {
        let (from, to, playlist) = (TrackId::new(), TrackId::new(), PlaylistId::new());
        let (mine, theirs) = (entry(playlist, from), entry(playlist, to));
        let mut state = JournalState::default();
        state.entries.extend([mine.clone(), theirs]);

        assert_eq!(ByTrack::of(&state).fold_ops(from, to), [Op::RemoveEntry { entry: mine.id }]);
    }

    /// Песня в плейлисте дважды: переезжают обе записи.
    #[test]
    fn both_entries_of_one_playlist_move() {
        let (from, to, playlist) = (TrackId::new(), TrackId::new(), PlaylistId::new());
        let (first, second) = (entry(playlist, from), entry(playlist, from));
        let mut state = JournalState::default();
        state.entries.extend([first.clone(), second.clone()]);

        let ops = ByTrack::of(&state).fold_ops(from, to);

        assert_eq!(ops.len(), 4);
        for moving in [&first, &second] {
            let added = PlaylistEntry { id: derived(moving.id, from, to), track: to, ..moving.clone() };
            assert!(ops.contains(&Op::AddEntry(added)), "{moving:?}");
            assert!(ops.contains(&Op::RemoveEntry { entry: moving.id }), "{moving:?}");
        }
    }

    /// Прослушивания копируются (с трека их не снять), а то, что у `to` уже есть, — нет.
    #[test]
    fn plays_are_copied_unless_the_target_has_them() {
        let (from, to) = (TrackId::new(), TrackId::new());
        let (new, known) = (play(from, 10, 100), play(from, 20, 90));
        let twin = PlayEvent { id: PlayEventId::new(), track: to, ..known };
        // Время то же, но слушали иначе — другое прослушивание.
        let other = play(from, 20, 91);
        let mut state = JournalState::default();
        state.plays.extend([new, known, other, twin]);

        let ops = ByTrack::of(&state).fold_ops(from, to);

        assert_eq!(
            ops,
            [
                Op::Play(PlayEvent { id: derived(new.id, from, to), track: to, ..new }),
                Op::Play(PlayEvent { id: derived(other.id, from, to), track: to, ..other }),
            ]
        );
    }

    /// Повтор того же слияния ничего не добавит: после первого `from` пуст.
    #[test]
    fn a_second_fold_of_the_same_pair_is_empty() {
        let (from, to) = (TrackId::new(), TrackId::new());
        let heard = play(from, 10, 100);
        let mut state = JournalState::default();
        state.plays.push(heard);
        // Запись, которую слияние завело на `to`, — то, что осталось после первого раза.
        state.plays.push(PlayEvent { id: derived(heard.id, from, to), track: to, ..heard });

        assert_eq!(ByTrack::of(&state).fold_ops(from, to), []);
    }
}
