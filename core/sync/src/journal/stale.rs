//! Прежние копии в папке (Р9д, ADR 0007). Журнал начат заново из базы (`start_over`) — копия установки,
//! которая была до того, данных не вливает: база показывала всё, что человек оставил, и в новый журнал
//! это уже перенесено. Из такой копии берутся только паспорта треков, на которые в журнале есть данные, а
//! паспорта нет (трек, которого нет в каталоге, живёт в журнале одним паспортом, а в базе паспортов нет).
//!
//! Здесь же — «вложена ли копия в другие» ([`contained_in`]): предок прежней копии (старая установка,
//! чью копию та когда-то восстановила) лежит в папке навсегда и воскресил бы всё, что прежняя успела
//! удалить, поэтому тоже считается прежним.

use std::collections::BTreeSet;

use plinth_library::model::TrackPassport;
use plinth_types::{CoreError, TrackId};
use yrs::updates::decoder::Decode;
use yrs::{Doc, Transact, Update};

use super::doc::Roots;
use super::passport::referenced;
use super::{Journal, Op, apply_v2, unreadable};

/// Вложен ли снимок `snapshot` в снимки `container` вместе: влив его в их состояние, нельзя ничего ни
/// добавить, ни удалить. Сам он не меняется и ничего не пишет — сливается копия в памяти (как
/// [`Journal::news_in`]). Корни документа не нужны: смотрятся только множества вставок и удалений.
pub(crate) fn contained_in(container: &[&[u8]], snapshot: &[u8]) -> Result<bool, CoreError> {
    let update = Update::decode_v2(snapshot).map_err(|e| unreadable("snapshot", &e))?;
    let held = Doc::new();
    {
        let mut txn = held.transact_mut();
        for known in container {
            apply_v2(&mut txn, known)?;
        }
    }
    let mut txn = held.transact_mut();
    txn.apply_update(update).map_err(|e| unreadable("snapshot", &e))?;
    Ok(txn.insert_set().is_empty() && txn.delete_set().is_empty())
}

/// Паспорта, которые журналу не хватает и которые есть в прежних копиях `stale`: для треков, на которые в
/// журнале есть данные (`referenced`) и нет паспорта. Копии читаются по порядку, пока есть чего искать;
/// паспорт трека берётся из первой, где он нашёлся (передай свежие первыми). Паспорт трека без данных не
/// берётся. По возрастанию `track`. Журнал не меняется.
pub(crate) fn absorbable_passports(journal: &Journal, stale: &[&[u8]]) -> Result<Vec<TrackPassport>, CoreError> {
    let state = journal.state();
    let described: BTreeSet<TrackId> = state.passports.iter().map(|passport| passport.track).collect();
    let mut wanted: BTreeSet<TrackId> = referenced(&state).difference(&described).copied().collect();
    let mut found = Vec::new();
    for snapshot in stale {
        if wanted.is_empty() {
            break;
        }
        let doc = Doc::new();
        let roots = Roots::new(&doc);
        apply_v2(&mut doc.transact_mut(), snapshot)?;
        for (track, passport) in roots.passports_of(&doc.transact(), &wanted) {
            wanted.remove(&track);
            found.push(passport);
        }
    }
    found.sort_by_key(|passport| passport.track);
    Ok(found)
}

/// Дописывает в журнал паспорта из [`absorbable_passports`] одной правкой; сколько дописано, ноль — писать
/// нечего, журнал не тронут. Проекцию не трогает: паспорт в базу не проецируется, а после слияния копий
/// проекцию пересобирает `catch_up`.
pub(crate) fn absorb_passports(journal: &mut Journal, stale: &[&[u8]]) -> Result<usize, CoreError> {
    let ops: Vec<Op> = absorbable_passports(journal, stale)?.into_iter().map(Op::Describe).collect();
    if ops.is_empty() {
        return Ok(0);
    }
    journal.record_all(&ops)?;
    Ok(ops.len())
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::PathBuf;
    use std::time::Duration;

    use plinth_library::model::TrackPassport;
    use plinth_types::{DeviceId, TrackId};

    use super::{absorb_passports, absorbable_passports, contained_in};
    use crate::journal::{Journal, Op};

    struct Scratch(PathBuf);

    impl Scratch {
        fn new() -> Self {
            Self(std::env::temp_dir().join(format!("plinth-stale-{}", DeviceId::new())))
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

    /// Журнал с лайками `liked`, снимок которого можно брать.
    fn with_likes(dir: &Scratch, liked: &[TrackId]) -> Journal {
        let mut journal = dir.journal();
        for &track in liked {
            journal.record(&Op::Like { track }).unwrap();
        }
        journal
    }

    /// Журнал, в который влит снимок `base`: то, что вырастает из копии, когда её восстановили.
    fn grown_from(dir: &Scratch, base: &[u8]) -> Journal {
        let mut journal = dir.journal();
        journal.merge_snapshot(base).unwrap();
        journal
    }

    #[test]
    fn a_copy_is_contained_in_itself_and_in_what_grew_from_it() {
        let (older, newer) = (Scratch::new(), Scratch::new());
        let first = with_likes(&older, &[TrackId::new()]).snapshot();
        let mut grown = grown_from(&newer, &first);
        grown.record(&Op::Like { track: TrackId::new() }).unwrap();
        let second = grown.snapshot();

        assert!(contained_in(&[&first], &first).unwrap());
        assert!(contained_in(&[&second], &first).unwrap());
        assert!(!contained_in(&[&first], &second).unwrap(), "the descendant has more");
    }

    #[test]
    fn a_copy_is_not_contained_in_a_stranger() {
        let (a, b) = (Scratch::new(), Scratch::new());
        let mine = with_likes(&a, &[TrackId::new()]).snapshot();
        let other = with_likes(&b, &[TrackId::new()]).snapshot();

        assert!(!contained_in(&[&other], &mine).unwrap());
        assert!(!contained_in(&[], &mine).unwrap(), "nothing holds nothing");
    }

    #[test]
    fn an_empty_copy_is_contained_in_everything() {
        let (a, b) = (Scratch::new(), Scratch::new());
        let full = with_likes(&a, &[TrackId::new()]).snapshot();
        let empty = b.journal().snapshot();

        assert!(contained_in(&[&full], &empty).unwrap());
        assert!(contained_in(&[], &empty).unwrap());
    }

    /// Контейнер — снимки вместе: то, что целиком лежит в их объединении, вложено, а в одном из них — нет.
    #[test]
    fn the_container_is_the_union_of_its_snapshots() {
        let (a, b, joined) = (Scratch::new(), Scratch::new(), Scratch::new());
        let from_a = with_likes(&a, &[TrackId::new()]).snapshot();
        let from_b = with_likes(&b, &[TrackId::new()]).snapshot();
        let mut both = grown_from(&joined, &from_a);
        both.merge_snapshot(&from_b).unwrap();
        let union = both.snapshot();

        assert!(contained_in(&[&from_a, &from_b], &union).unwrap());
        assert!(!contained_in(&[&from_a], &union).unwrap());
        assert!(!contained_in(&[&from_b], &union).unwrap());
    }

    /// Потомок, который удалил то, что принёс предок, вмещает предка; предок потомка — нет: удаление новое.
    #[test]
    fn a_deletion_the_container_does_not_know_is_not_contained() {
        let (a, b) = (Scratch::new(), Scratch::new());
        let track = TrackId::new();
        let ancestor = with_likes(&a, &[track]).snapshot();
        let mut descendant = grown_from(&b, &ancestor);
        descendant.record(&Op::Unlike { track }).unwrap();
        let descendant = descendant.snapshot();

        assert!(contained_in(&[&descendant], &ancestor).unwrap());
        assert!(!contained_in(&[&ancestor], &descendant).unwrap());
    }

    /// Нужны только паспорта треков, на которые в журнале есть данные, а паспорта нет; журнал не меняется.
    #[test]
    fn only_passports_of_tracks_with_data_and_without_a_passport_are_absorbable() {
        let (here, there) = (Scratch::new(), Scratch::new());
        let (described, missing, unused) = (TrackId::new(), TrackId::new(), TrackId::new());
        let mut journal = with_likes(&here, &[described, missing]);
        journal.record(&Op::Describe(passport(described, "Группа крови"))).unwrap();
        let mut old = there.journal();
        let titles = [(described, "Старое описание"), (missing, "Кукушка"), (unused, "Без данных")];
        for (track, title) in titles {
            old.record(&Op::Describe(passport(track, title))).unwrap();
        }
        let old = old.snapshot();
        let (state, mark) = (journal.state(), journal.mark());

        let found = absorbable_passports(&journal, &[&old]).unwrap();

        assert_eq!(found, vec![passport(missing, "Кукушка")]);
        assert_eq!((journal.state(), journal.mark()), (state, mark), "the journal is not changed");
    }

    #[test]
    fn the_first_copy_that_has_a_passport_gives_it() {
        let (here, first, second) = (Scratch::new(), Scratch::new(), Scratch::new());
        let (track, other) = (TrackId::new(), TrackId::new());
        let journal = with_likes(&here, &[track, other]);
        let mut newer = first.journal();
        newer.record(&Op::Describe(passport(track, "Новее"))).unwrap();
        let mut older = second.journal();
        older.record(&Op::Describe(passport(track, "Старее"))).unwrap();
        older.record(&Op::Describe(passport(other, "Только в старой"))).unwrap();
        let (newer, older) = (newer.snapshot(), older.snapshot());

        let found = absorbable_passports(&journal, &[&newer, &older]).unwrap();
        let reversed = absorbable_passports(&journal, &[&older, &newer]).unwrap();

        let mut titles: Vec<(TrackId, &str)> = found.iter().map(|p| (p.track, p.title.as_str())).collect();
        titles.sort();
        let mut expected = vec![(track, "Новее"), (other, "Только в старой")];
        expected.sort();
        assert_eq!(titles, expected);
        assert_eq!(reversed.iter().find(|p| p.track == track).map(|p| p.title.as_str()), Some("Старее"));
    }

    /// Паспорта по возрастанию трека; ни одного нужного — пусто.
    #[test]
    fn nothing_to_absorb_means_an_empty_answer() {
        let (here, there) = (Scratch::new(), Scratch::new());
        let journal = here.journal();
        let mut old = there.journal();
        old.record(&Op::Describe(passport(TrackId::new(), "Без данных"))).unwrap();

        assert!(absorbable_passports(&journal, &[&old.snapshot()]).unwrap().is_empty());
        assert!(absorbable_passports(&journal, &[]).unwrap().is_empty());
    }

    /// Паспорта ложатся одной правкой журнала, число совпадает; второй раз писать нечего.
    #[test]
    fn absorbing_writes_one_edit_and_only_once() {
        let (here, there) = (Scratch::new(), Scratch::new());
        let (a, b) = (TrackId::new(), TrackId::new());
        let mut journal = with_likes(&here, &[a, b]);
        let mut old = there.journal();
        old.record(&Op::Describe(passport(a, "Кукушка"))).unwrap();
        old.record(&Op::Describe(passport(b, "Группа крови"))).unwrap();
        let old = old.snapshot();
        let seq = journal.mark().seq;

        let absorbed = absorb_passports(&mut journal, &[&old]).unwrap();

        assert_eq!(absorbed, 2);
        assert_eq!(journal.mark().seq, seq + 1, "one edit for all passports");
        assert_eq!(journal.passport(a), Some(passport(a, "Кукушка")));
        assert_eq!(journal.passport(b), Some(passport(b, "Группа крови")));
        let state = journal.state();
        assert_eq!(absorb_passports(&mut journal, &[&old]).unwrap(), 0);
        assert_eq!((journal.state(), journal.mark().seq), (state, seq + 1), "the second time writes nothing");
    }
}
