//! Р1.6 (`docs/work/r1-6.md`, находка 6 ревью v0.2): параллельные записи одного
//! ключа регистра — оценки, имени плейлиста, позиции записи, настройки,
//! паспорта — решает время записи, а не случайный номер клиента `yrs`.
//! Человек оценил трек на новой установке, потом восстановился из копии
//! старой: новое значение должно остаться.
//!
//! Тесты зовут только прежний API: время ставит сам журнал, поэтому между
//! записями — пауза. Повторы (`ROUNDS`) нужны потому, что у каждого
//! открытия журнала свой случайный номер клиента: до Р1.6 победитель
//! выходил разным.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r1_6_registers;

use std::thread::sleep;
use std::time::Duration;

use plinth_sync::journal::Op;
use plinth_types::DeviceId;

use r1_6_registers::{
    KINDS, Pick, Replica, e1, entry, exchange, p1, pair, passport, playlist, position, rating, t1, t2,
};

const ROUNDS: usize = 30;

/// Время записи у `Timestamp::now()` — миллисекунды: между «старой» и «новой» записью пауза.
fn later() {
    sleep(Duration::from_millis(4));
}

/// Два устройства пишут один и тот же регистр порознь: позднейшая запись
/// побеждает у обоих — кто бы ни держал старую и в каком бы порядке ни
/// обменивались. `ROUNDS` раз, с разными номерами клиентов.
#[test]
fn the_later_write_wins_in_every_register() {
    for kind in &KINDS {
        for round in 0..ROUNDS {
            let (mut older, mut newer) = pair(kind);
            if round % 2 == 1 {
                std::mem::swap(&mut older, &mut newer);
            }
            older.journal.record_all(&(kind.ops)(Pick::A)).unwrap();
            later();
            newer.journal.record_all(&(kind.ops)(Pick::B)).unwrap();

            if round % 4 < 2 {
                exchange(&mut older, &mut newer);
            } else {
                exchange(&mut newer, &mut older);
            }

            let expected = (kind.shown)(Pick::B);
            assert_eq!((kind.read)(&older.journal), expected, "{}: the older replica, round {round}", kind.name);
            assert_eq!((kind.read)(&newer.journal), expected, "{}: the newer replica, round {round}", kind.name);
        }
    }
}

/// Сценарий находки 6: на новой установке оценили, потом «Восстановить» из
/// копии старой, где записи сделаны раньше. Копия — журнал в раскладке до
/// Р1.6, написанный клиентом с самым большим номером (`yrs` отдаёт спор
/// большему): до Р1.6 она выигрывала всегда. Новое значение каждого
/// регистра остаётся, а то, чего на новой установке нет, возвращается.
#[test]
fn a_restore_from_an_older_copy_keeps_what_was_written_after_it() {
    for round in 0..ROUNDS {
        let mut new = Replica::new();
        new.journal
            .record_all(&[
                Op::Rate { track: t1(), rating: rating(3) },
                Op::CreatePlaylist(playlist("New")),
                Op::AddEntry(entry(position(2))),
                Op::Set(plinth_library::model::Setting::VersionPreference(
                    plinth_library::model::VersionPreference::Original,
                )),
                Op::Describe(passport("Кукушка")),
            ])
            .unwrap();
        let copy = Replica::legacy();

        new.take_from(&copy);

        let state = new.journal.state();
        let r = |stars| rating(stars).unwrap();
        assert_eq!(
            state.ratings,
            vec![(t1(), r(3)), (t2(), r(2))],
            "round {round}: the new rating stays, the other returns"
        );
        assert_eq!(state.playlists.iter().map(|l| l.name.as_str()).collect::<Vec<_>>(), ["New"], "round {round}");
        assert_eq!(
            state.entries.iter().map(|e| e.position.clone()).collect::<Vec<_>>(),
            [position(2)],
            "round {round}"
        );
        assert_eq!(format!("{:?}", state.settings), "[VersionPreference(Original)]", "round {round}");
        assert_eq!(new.journal.passport(t1()).unwrap().title, "Кукушка", "round {round}");
        assert_eq!(state.likes, vec![t1()], "round {round}: what only the copy had comes back");
    }
}

/// Журнал в раскладке до Р1.6 читается как прежде.
#[test]
fn a_journal_of_the_old_layout_is_read_as_before() {
    let copy = Replica::legacy();
    let state = copy.journal.state();

    assert_eq!(state.ratings, vec![(t1(), rating(5).unwrap()), (t2(), rating(2).unwrap())]);
    assert_eq!(state.playlists.len(), 1);
    assert_eq!((state.playlists[0].id, state.playlists[0].name.as_str()), (p1(), "Road"));
    assert_eq!((state.entries.len(), state.entries[0].id, state.entries[0].position.clone()), (1, e1(), position(1)));
    assert_eq!(format!("{:?}", state.settings), "[VersionPreference(Clean)]");
    assert_eq!(copy.journal.passport(t1()).unwrap().title, "Кукушка (демо)");
    assert_eq!((state.likes, state.unreadable), (vec![t1()], 0));
}

/// Осознанно не меняется (ADR 0007): множества — «добавление побеждает». Лайк,
/// поставленный и снятый на новой установке, возвращается вместе с копией, где
/// он стоял. Если это когда-нибудь решат иначе, это решение автора, а не побочный
/// эффект.
#[test]
fn sets_stay_add_wins_a_like_removed_on_the_new_installation_returns() {
    let mut new = Replica::new();
    new.journal.record(&Op::Like { track: t1() }).unwrap();
    later();
    new.journal.record(&Op::Unlike { track: t1() }).unwrap();
    let copy = Replica::legacy();

    new.take_from(&copy);

    assert_eq!(new.journal.state().likes, vec![t1()]);
}

/// Две установки с одним `DeviceId` (журнал скопирован на второй телефон)
/// остаются сходимыми: спорят как раньше, но состояния совпадают.
#[test]
fn replicas_of_one_installation_still_converge() {
    let device = DeviceId::new();
    let (mut a, mut b) = (Replica::with_device(device), Replica::with_device(device));
    a.journal.record(&Op::Rate { track: t1(), rating: rating(5) }).unwrap();
    b.journal.record(&Op::Rate { track: t1(), rating: rating(3) }).unwrap();

    exchange(&mut a, &mut b);

    assert_eq!(a.journal.state(), b.journal.state());
    assert_eq!(a.journal.state().ratings.len(), 1);
}
