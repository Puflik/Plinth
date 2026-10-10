//! Р1.6 (`docs/work/r1-6.md`): время записи регистра — `OpMeta.at` — решает
//! спор детерминированно. Здесь время задают сами: `Journal::record_all_at`
//! (новый API, до его появления файл не собирается).
//!
//! Правила: побеждает запись с большим `at`; при равенстве — с большим
//! `DeviceId`; запись, которая видела прежнее значение ключа, получает `at`
//! не меньше его `at` + 1 мс — медленные часы не дают старому победить новое.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r1_6_registers;

use plinth_library::model::{Setting, VersionPreference};
use plinth_sync::journal::Op;
use plinth_types::{DeviceId, Timestamp};

use r1_6_registers::{KINDS, Kind, Pick, Replica, entry, exchange, pair, passport, playlist, position, rating, t1, t2};

fn at(ms: i64) -> Timestamp {
    Timestamp::from_millis(ms)
}

fn write(replica: &mut Replica, kind: &Kind, pick: Pick, ms: i64) {
    assert!(replica.journal.record_all_at(&(kind.ops)(pick), at(ms)).unwrap().is_some(), "{}", kind.name);
}

fn read(replica: &Replica, kind: &Kind) -> String {
    (kind.read)(&replica.journal)
}

/// Побеждает запись с большим временем — а не та, чей клиент `yrs` «больше»,
/// и не та, что приехала последней. Оба порядка обмена, обе раскладки времени.
#[test]
fn the_later_time_wins_in_every_register_and_every_order() {
    for kind in &KINDS {
        for round in 0..12 {
            let (mut a, mut b) = pair(kind);
            let (first, second) = if round % 2 == 0 { (Pick::A, Pick::B) } else { (Pick::B, Pick::A) };
            write(&mut a, kind, first, 1_000);
            write(&mut b, kind, second, 2_000);
            if round % 4 < 2 {
                exchange(&mut a, &mut b);
            } else {
                exchange(&mut b, &mut a);
            }
            let expected = (kind.shown)(second);
            assert_eq!(read(&a, kind), expected, "{}: replica a, round {round}", kind.name);
            assert_eq!(read(&b, kind), expected, "{}: replica b, round {round}", kind.name);
        }
    }
}

/// Одно и то же время: решает установка с большим `DeviceId`, не содержимое.
#[test]
fn equal_times_go_to_the_greater_installation() {
    let lo: DeviceId = "00000000-0000-7000-8000-000000000001".parse().unwrap();
    let hi: DeviceId = "00000000-0000-7000-8000-000000000002".parse().unwrap();
    assert!(lo < hi);
    for kind in &KINDS {
        for (on_lo, on_hi) in [(Pick::A, Pick::B), (Pick::B, Pick::A)] {
            let mut base = Replica::new();
            (kind.base)(&mut base.journal);
            let (mut a, mut b) = (Replica::with_device(lo), Replica::with_device(hi));
            a.take_from(&base);
            b.take_from(&base);
            write(&mut a, kind, on_lo, 5_000);
            write(&mut b, kind, on_hi, 5_000);

            exchange(&mut a, &mut b);

            let expected = (kind.shown)(on_hi);
            assert_eq!((read(&a, kind), read(&b, kind)), (expected.clone(), expected), "{}", kind.name);
        }
    }
}

/// Часы убежали вперёд (или отстали): значение, которое установка уже видела,
/// всё равно перекрывается её следующей записью — иначе запись «из будущего»
/// побеждала бы навсегда. Третья реплика сходится к тому же в любом порядке.
#[test]
fn a_write_that_saw_a_value_beats_it_even_with_a_slow_clock() {
    for kind in &KINDS {
        let (mut a, mut b) = pair(kind);
        write(&mut a, kind, Pick::A, 5_000_000_000_000);
        b.take_from(&a);

        write(&mut b, kind, Pick::B, 1_000);

        let expected = (kind.shown)(Pick::B);
        assert_eq!(read(&b, kind), expected, "{}: the writer itself", kind.name);
        a.take_from(&b);
        assert_eq!(read(&a, kind), expected, "{}: the replica of the future write", kind.name);
        for order in [[&a, &b], [&b, &a]] {
            let mut c = Replica::new();
            c.take_from(order[0]);
            c.take_from(order[1]);
            assert_eq!(read(&c, kind), expected, "{}: a third replica", kind.name);
        }
    }
}

/// Журнал до Р1.6 спорит по тому же времени: запись новой установки, сделанная
/// раньше копии, проигрывает ей, позже — побеждает; а следующая запись после
/// слияния побеждает при любых часах.
#[test]
fn the_old_layout_competes_by_time_too() {
    let edit = || {
        vec![
            Op::Rate { track: t1(), rating: rating(3) },
            Op::CreatePlaylist(playlist("New")),
            Op::AddEntry(entry(position(2))),
            Op::Set(Setting::VersionPreference(VersionPreference::Original)),
            Op::Describe(passport("Кукушка")),
        ]
    };
    for round in 0..10 {
        let mut earlier = Replica::new();
        earlier.journal.record_all_at(&edit(), at(1_700_000_000_000)).unwrap();
        earlier.take_from(&Replica::legacy());
        let old = earlier.journal.state();
        assert_eq!(old.ratings, vec![(t1(), rating(5).unwrap()), (t2(), rating(2).unwrap())], "round {round}");
        assert_eq!(old.playlists[0].name, "Road", "round {round}");
        assert_eq!(old.entries[0].position, position(1), "round {round}");
        assert_eq!(format!("{:?}", old.settings), "[VersionPreference(Clean)]", "round {round}");
        assert_eq!(earlier.journal.passport(t1()).unwrap().title, "Кукушка (демо)", "round {round}");

        let mut later = Replica::new();
        later.journal.record_all_at(&edit(), at(2_000_000_000_000)).unwrap();
        later.take_from(&Replica::legacy());
        let new = later.journal.state();
        assert_eq!(new.ratings, vec![(t1(), rating(3).unwrap()), (t2(), rating(2).unwrap())], "round {round}");
        assert_eq!(new.playlists[0].name, "New", "round {round}");
        assert_eq!(new.entries[0].position, position(2), "round {round}");
        assert_eq!(format!("{:?}", new.settings), "[VersionPreference(Original)]", "round {round}");
        assert_eq!(later.journal.passport(t1()).unwrap().title, "Кукушка", "round {round}");

        // Копия уже влита; часы отстали, но запись её видела — и побеждает.
        earlier.journal.record_all_at(&[Op::Rate { track: t1(), rating: rating(4) }], at(1_000)).unwrap();
        assert_eq!(earlier.journal.state().ratings[0], (t1(), rating(4).unwrap()), "round {round}");
    }
}

/// Удаление стирает то, что видело; запись, которой оно не видело, остаётся
/// (как у множеств: параллельное изменение сильнее удаления).
#[test]
fn a_removal_erases_what_it_saw_and_spares_an_unseen_write() {
    let (mut a, mut b) = pair(&KINDS[0]);
    let mut c = Replica::new();
    a.journal.record_at(&Op::Rate { track: t1(), rating: rating(5) }, at(1_000)).unwrap();
    b.take_from(&a);
    c.take_from(&a);

    b.journal.record_at(&Op::Rate { track: t1(), rating: None }, at(2_000)).unwrap();
    assert!(b.journal.state().ratings.is_empty());
    exchange(&mut a, &mut b);
    assert!(a.journal.state().ratings.is_empty() && b.journal.state().ratings.is_empty());

    c.journal.record_at(&Op::Rate { track: t1(), rating: rating(4) }, at(3_000)).unwrap();
    exchange(&mut c, &mut b);
    exchange(&mut a, &mut c);
    for replica in [&a, &b, &c] {
        assert_eq!(replica.journal.state().ratings, vec![(t1(), rating(4).unwrap())]);
    }
}

/// Модель: три установки пишут оценки четырёх треков вразнобой и иногда
/// обмениваются; время записей растёт, поэтому после полного обмена у каждой
/// установки у каждого трека — значение самой поздней записи.
#[test]
fn replicas_converge_to_the_latest_write() {
    for seed in [1_u64, 7, 42, 2026] {
        let mut state = seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1;
        let mut next = |bound: usize| {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            (state % bound as u64) as usize
        };
        let tracks = [
            t1(),
            t2(),
            "55555555-0000-7000-8000-000000000001".parse().unwrap(),
            "55555555-0000-7000-8000-000000000002".parse().unwrap(),
        ];
        let mut replicas = [Replica::new(), Replica::new(), Replica::new()];
        let mut latest = std::collections::HashMap::new();
        for step in 0..60_i64 {
            let (who, track) = (next(3), tracks[next(4)]);
            // Оценка, отличная от действующей у пишущего: повтор того же значения журнал не пишет.
            let current = replicas[who].journal.state().ratings.iter().find(|(t, _)| *t == track).map(|(_, r)| *r);
            let mut stars = u8::try_from(1 + next(5)).unwrap();
            if rating(stars) == current {
                stars = stars % 5 + 1;
            }
            replicas[who].journal.record_at(&Op::Rate { track, rating: rating(stars) }, at(1_000 + step)).unwrap();
            latest.insert(track, rating(stars).unwrap());
            if next(4) == 0 {
                let (from, to) = (next(3), next(3));
                if from != to {
                    let update = replicas[from].journal.updates_since(&replicas[to].journal.state_vector()).unwrap();
                    replicas[to].journal.merge(&update).unwrap();
                }
            }
        }
        for _ in 0..2 {
            for (from, to) in [(0, 1), (1, 2), (2, 0), (0, 2), (2, 1), (1, 0)] {
                let update = replicas[from].journal.updates_since(&replicas[to].journal.state_vector()).unwrap();
                replicas[to].journal.merge(&update).unwrap();
            }
        }
        let expected = replicas[0].journal.state();
        for replica in &replicas {
            assert_eq!(replica.journal.state(), expected, "seed {seed}");
        }
        for (track, stars) in &latest {
            let found = expected.ratings.iter().find(|(t, _)| t == track).map(|(_, r)| *r);
            assert_eq!(found, Some(*stars), "seed {seed}: the latest write of {track} wins");
        }
    }
}
