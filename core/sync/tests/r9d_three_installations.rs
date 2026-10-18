//! Р9г (`docs/work/r9d.md`), №6 ревью R1: три установки одной библиотеки (телефон, планшет,
//! старый телефон) восстанавливают копии друг друга в разном порядке. Данные старого телефона
//! доходят до меньшего ID двумя путями — напрямую (у телефона) и через трек планшета (планшет
//! сначала сложил их у себя, потом перешёл на ID телефона). Записи, которые слияние заводит на
//! меньшем ID, обязаны получить один и тот же идентификатор на обоих путях: иначе после обмена
//! копиями прослушивания и записи плейлиста старого телефона окажутся дважды.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r9d_support;

use r9d_support::{Installation, SONG};

#[test]
fn data_that_reaches_the_smallest_id_by_two_paths_is_not_doubled() {
    let mut phone = Installation::scanned(&[SONG]);
    let mut tablet = Installation::scanned(&[SONG]);
    let mut old = Installation::scanned(&[SONG]);
    let (song, tablet_song, old_song) = (phone.track(SONG), tablet.track(SONG), old.track(SONG));
    assert!(song < tablet_song && tablet_song < old_song, "ID растут со временем создания");
    phone.play(song, 1);
    tablet.play(tablet_song, 2);
    old.play(old_song, 3);
    old.list("Old", old_song);
    let (from_phone, from_old) = (phone.copy(), old.copy());

    // Планшет: копия старого телефона (его ID больше — данные встают на трек планшета),
    // потом копия телефона (ID телефона меньше — планшет переходит на него вместе с данными).
    tablet.restore(&from_old);
    tablet.restore(&from_phone);
    // Телефон: копия старого телефона напрямую.
    phone.restore(&from_old);
    // Телефон и планшет принимают копии друг друга.
    let (from_phone, from_tablet) = (phone.copy(), tablet.copy());
    phone.restore(&from_tablet);
    tablet.restore(&from_phone);

    for (name, install) in [("телефон", &phone), ("планшет", &tablet)] {
        assert_eq!(install.track(SONG), song, "{name}: песня на меньшем ID");
        assert_eq!(install.plays_of(song), 3, "{name}: прослушивания трёх установок, по разу");
        assert_eq!(install.tracks_in("Old"), [song], "{name}: плейлист старого телефона без дублей");
    }
}
