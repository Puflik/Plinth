//! Р9г (`docs/work/r9d.md`), №6 ревью R1: две установки восстанавливают копии друг
//! друга. На каждой у песни свой ID каталога, и обе «заняты» данными. Раньше данные
//! шли только с более нового ID на более старый: у установки с более старыми ID
//! данные второй по общим песням оставались на чужом ID, которого в каталоге нет, —
//! итог зависел от того, чей скан был раньше. Теперь данные двух треков складываются
//! на тот, чей ID меньше, на обеих установках, и две установки сходятся на одном треке.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r9d_support;

use plinth_types::TrackId;
use r9d_support::{Installation, OTHER, SONG, stars};

/// Телефон сканировал раньше планшета: ID его треков меньше.
fn phone_and_tablet() -> (Installation, Installation) {
    let phone = Installation::scanned(&[SONG, OTHER]);
    let tablet = Installation::scanned(&[SONG, OTHER]);
    assert!(phone.track(SONG) < tablet.track(SONG), "ID растут со временем создания");
    assert!(phone.track(OTHER) < tablet.track(OTHER), "ID растут со временем создания");
    (phone, tablet)
}

/// Обе установки восстановили копию друг друга, снятые до восстановления.
fn exchange(phone: &mut Installation, tablet: &mut Installation) -> [usize; 2] {
    let (from_phone, from_tablet) = (phone.copy(), tablet.copy());
    [phone.restore(&from_tablet).relinked, tablet.restore(&from_phone).relinked]
}

/// Что видит установка: трек «Song», «Любимое», оценка и число прослушиваний «Song».
type Seen = (TrackId, Vec<String>, Option<plinth_library::model::Rating>, usize);

fn seen(install: &Installation) -> Seen {
    let song = install.track(SONG);
    (song, install.liked_titles(), install.rating(song), install.plays_of(song))
}

/// Сценарий ревью: на телефоне лайк «Song», на планшете 5★ «Song» и лайк «Other»; взаимные
/// «Восстановить». Обе установки видят всё, и у обеих «Song» — один и тот же трек, с меньшим ID.
#[test]
fn each_installation_sees_what_the_other_wrote() {
    let (mut phone, mut tablet) = phone_and_tablet();
    let song = phone.track(SONG);
    phone.like(song);
    let tablet_song = tablet.track(SONG);
    tablet.rate(tablet_song, 5);
    let tablet_other = tablet.track(OTHER);
    tablet.like(tablet_other);

    exchange(&mut phone, &mut tablet);

    for (name, install) in [("телефон", &phone), ("планшет", &tablet)] {
        assert_eq!(install.liked_titles(), ["Other", "Song"], "{name}: «Любимое»");
        assert_eq!(install.rating(install.track(SONG)), Some(stars(5)), "{name}: оценка «Song»");
    }
    assert_eq!(phone.track(SONG), song, "у телефона трек остался: его ID меньше");
    assert_eq!(tablet.track(SONG), song, "планшет перешёл на меньший ID");
    assert_eq!(phone.track(OTHER), tablet.track(OTHER), "у «Other» ID один на обеих");
}

/// Второй обмен ничего не меняет: установки не меняются ID туда и обратно и не
/// пересчитывают друг за другом данные.
#[test]
fn a_second_exchange_changes_nothing() {
    let (mut phone, mut tablet) = phone_and_tablet();
    let song = phone.track(SONG);
    phone.like(song);
    phone.play(song, 1);
    let tablet_song = tablet.track(SONG);
    tablet.rate(tablet_song, 4);
    tablet.play(tablet_song, 2);
    tablet.play(tablet_song, 3);
    exchange(&mut phone, &mut tablet);
    let before = (seen(&phone), seen(&tablet));
    assert_eq!(before.0, before.1, "после первого обмена установки видят одно и то же");

    let relinked = exchange(&mut phone, &mut tablet);

    assert_eq!(relinked, [0, 0], "второй обмен перепривязывать нечего");
    assert_eq!((seen(&phone), seen(&tablet)), before);
}

/// Оценки разошлись: побеждает оценка трека с более новым ID — правило прежнее (новая
/// установка перебивает старую), но теперь оно одинаково на обеих установках.
#[test]
fn a_disputed_rating_ends_the_same_on_both() {
    let (mut phone, mut tablet) = phone_and_tablet();
    let song = phone.track(SONG);
    phone.rate(song, 3);
    let tablet_song = tablet.track(SONG);
    tablet.rate(tablet_song, 5);

    exchange(&mut phone, &mut tablet);

    assert_eq!(phone.rating(song), Some(stars(5)), "телефон");
    assert_eq!(tablet.rating(song), Some(stars(5)), "планшет");
    exchange(&mut phone, &mut tablet);
    assert_eq!((phone.rating(song), tablet.rating(song)), (Some(stars(5)), Some(stars(5))), "и после второго обмена");
}

/// Обе установки перекладывают данные «Song» порознь, не видя, что делает другая, —
/// каждая восстановила копию до обмена. Когда они обменялись ещё раз, прослушивания и
/// записи плейлистов не задвоились: перепривязка заводит на меньшем ID одни и те же записи.
#[test]
fn independent_moves_of_the_same_data_do_not_double_it() {
    let (mut phone, mut tablet) = phone_and_tablet();
    let song = phone.track(SONG);
    phone.list("Road", song);
    phone.play(song, 1);
    phone.play(song, 2);
    let tablet_song = tablet.track(SONG);
    tablet.list("Gym", tablet_song);
    for n in 3..=5 {
        tablet.play(tablet_song, n);
    }

    exchange(&mut phone, &mut tablet);

    for (name, install) in [("телефон", &phone), ("планшет", &tablet)] {
        assert_eq!(install.plays_of(song), 5, "{name}: прослушивания обеих установок, после первого обмена");
        assert_eq!(install.tracks_in("Road"), [song], "{name}: «Road»");
        assert_eq!(install.tracks_in("Gym"), [song], "{name}: «Gym»");
    }
    exchange(&mut phone, &mut tablet);
    for (name, install) in [("телефон", &phone), ("планшет", &tablet)] {
        assert_eq!(install.plays_of(song), 5, "{name}: прослушивания не задвоились");
        assert_eq!(install.tracks_in("Road"), [song], "{name}: «Road» без дублей");
        assert_eq!(install.tracks_in("Gym"), [song], "{name}: «Gym» без дублей");
        assert_eq!(install.db.user_data(song).unwrap().play_count, 5, "{name}: счётчик");
    }
}
