//! Р9г (`docs/work/r9d.md`), №21 ревью R1: перепривязка жадная. Каждый потерянный
//! паспорт по очереди забирал лучшего для себя, а при равном счёте — первого по ID.
//! Две копии «Кукушки» — «сингл» без тега альбома и трек «Чёрного альбома» — менялись
//! данными: паспорт сингла шёл первым, у обоих кандидатов у него счёт один, и он
//! забирал трек альбома; паспорту альбома оставался сингл. Теперь пары раздаются
//! разом, лучшими вперёд (`TrackPassport::pair_up`), как в скане.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r9d_support;

use common::Song;
use plinth_types::TrackId;
use r9d_support::{Installation, stars};

const SINGLE: Song = Song {
    path: "/storage/emulated/0/Music/Singles/Кукушка.mp3",
    title: "Кукушка",
    artist: "Кино",
    album: None,
    duration_ms: Some(398_000),
};

const ALBUM: Song = Song {
    path: "/storage/emulated/0/Music/Чёрный альбом/Кукушка.mp3",
    title: "Кукушка",
    artist: "Кино",
    album: Some("Чёрный альбом"),
    duration_ms: Some(398_500),
};

/// Старый каталог — сингл, потом альбом (ID сингла меньше): лайк на сингле, 5★ и плейлист
/// на альбомном треке.
fn old_installation() -> (Installation, TrackId, TrackId) {
    let mut old = Installation::scanned(&[SINGLE, ALBUM]);
    let (single, album) = (old.track(SINGLE), old.track(ALBUM));
    assert!(single < album, "ID растут со временем создания");
    old.like(single);
    old.rate(album, 5);
    old.list("Road", album);
    (old, single, album)
}

/// Файлы вернулись к своим трекам: лайк на сингле, оценка и плейлист на альбомном.
fn assert_each_file_has_its_own_data(new: &Installation, single: TrackId, album: TrackId) {
    assert_eq!(new.track(SINGLE), single, "файл сингла — трек сингла");
    assert_eq!(new.track(ALBUM), album, "файл альбома — трек альбома");
    let (of_single, of_album) = (new.db.user_data(single).unwrap(), new.db.user_data(album).unwrap());
    assert_eq!((of_single.liked, of_single.rating), (true, None), "сингл: лайк");
    assert_eq!((of_album.liked, of_album.rating), (false, Some(stars(5))), "альбом: оценка");
    assert_eq!(new.tracks_in("Road"), [album], "плейлист — на альбомном треке");
}

/// Сценарий ревью: в новом каталоге альбомный файл просканирован раньше сингла.
#[test]
fn the_album_file_keeps_the_album_data() {
    let (old, single, album) = old_installation();
    let mut new = Installation::scanned(&[ALBUM, SINGLE]);

    new.restore(&old.copy());

    assert_each_file_has_its_own_data(&new, single, album);
}

/// Порядок скана не важен: сингл раньше альбома.
#[test]
fn the_scan_order_does_not_matter() {
    let (old, single, album) = old_installation();
    let mut new = Installation::scanned(&[SINGLE, ALBUM]);

    new.restore(&old.copy());

    assert_each_file_has_its_own_data(&new, single, album);
}

/// То же для занятых треков: на обоих файлах уже есть лайки этой установки, и данные
/// журнала складываются на них парами, лучшими вперёд, а не по очереди.
#[test]
fn busy_copies_are_paired_jointly_too() {
    let (old, single, album) = old_installation();
    let mut new = Installation::scanned(&[ALBUM, SINGLE]);
    let (new_album, new_single) = (new.track(ALBUM), new.track(SINGLE));
    new.like(new_album);
    new.like(new_single);

    new.restore(&old.copy());

    assert_eq!(new.track(SINGLE), single, "файл сингла — трек сингла");
    assert_eq!(new.track(ALBUM), album, "файл альбома — трек альбома");
    let (of_single, of_album) = (new.db.user_data(single).unwrap(), new.db.user_data(album).unwrap());
    assert_eq!((of_single.liked, of_single.rating), (true, None), "сингл: лайк");
    assert_eq!((of_album.liked, of_album.rating), (true, Some(stars(5))), "альбом: свой лайк и оценка журнала");
    assert_eq!(new.tracks_in("Road"), [album], "плейлист — на альбомном треке");
}
