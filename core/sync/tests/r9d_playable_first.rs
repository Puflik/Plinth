//! Р9г (`docs/work/r9d.md`), №17 ревью R1: в каталоге новой установки песня дважды,
//! файл одной копии уже удалён (или она на вынутой карте). Раньше «Восстановить»
//! отдавало лайк и плейлист первой по ID — недоступной: в списках их не видно, хотя
//! играемая копия есть. Теперь при равном сходстве первым идёт трек, который можно
//! сыграть; недоступность — только ранг при равенстве, а не условие.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r9d_support;

use common::Song;
use plinth_types::{Availability, TrackId};
use r9d_support::{Installation, SONG, stars};

/// Две копии песни — теги те же, папки разные.
const COPY_A: Song = Song { path: "/storage/emulated/0/Music/A/Song.mp3", ..SONG };
const COPY_B: Song = Song { path: "/storage/emulated/0/Music/B/Song.mp3", ..SONG };
/// Копия без альбома: к песне журнала подходит хуже, чем копия с альбомом.
const COPY_NO_ALBUM: Song = Song { path: "/storage/emulated/0/Music/C/Song.mp3", album: None, ..SONG };

/// Прошлая установка: песня с лайком и оценкой.
fn old_installation() -> (Installation, TrackId) {
    let mut old = Installation::scanned(&[SONG]);
    let song = old.track(SONG);
    old.like(song);
    old.rate(song, 4);
    (old, song)
}

/// Сценарий ревью: файл первой копии (меньший ID) удалён, скан его пометил. Данные идут
/// играемой копии: лайк виден в «Любимом».
#[test]
fn the_data_goes_to_the_copy_that_plays() {
    let (old, song) = old_installation();
    let mut new = Installation::scanned(&[COPY_A, COPY_B]);
    new.make_unavailable(COPY_A);
    let (a, b) = (new.track(COPY_A), new.track(COPY_B));
    assert!(a < b, "ID растут со временем создания");

    let restored = new.restore(&old.copy());

    assert_eq!(restored.relinked, 1);
    assert_eq!(new.track(COPY_B), song, "играемая копия взяла ID журнала");
    assert_eq!(new.track(COPY_A), a, "удалённая осталась со своим");
    assert_eq!(new.liked_titles(), ["Song"], "лайк виден в списке");
    assert_eq!(new.rating(song), Some(stars(4)));
}

/// То же для занятых треков: на обеих копиях уже есть данные этой установки — лайк,
/// — и данные журнала складываются на играемую.
#[test]
fn the_busy_copy_that_plays_is_preferred_too() {
    let (old, song) = old_installation();
    let mut new = Installation::scanned(&[COPY_A, COPY_B]);
    new.make_unavailable(COPY_A);
    let (a, b) = (new.track(COPY_A), new.track(COPY_B));
    assert!(a < b, "ID растут со временем создания");
    new.like(a);
    new.like(b);

    new.restore(&old.copy());

    assert_eq!(new.track(COPY_B), song, "играемая копия перешла на ID журнала");
    assert_eq!(new.track(COPY_A), a, "удалённая осталась со своим");
    assert_eq!(new.rating(song), Some(stars(4)), "оценка журнала — на играемой");
    assert_eq!(new.liked_titles(), ["Song"]);
}

/// Недоступность — только ранг при равном сходстве. Недоступная копия с тем же альбомом
/// подходит больше играемой без альбома — и берёт данные, хотя ID у неё больше.
#[test]
fn only_a_tie_goes_to_the_playable_copy() {
    let (old, song) = old_installation();
    let mut new = Installation::scanned(&[COPY_NO_ALBUM, COPY_A]);
    new.make_unavailable(COPY_A);
    let (no_album, a) = (new.track(COPY_NO_ALBUM), new.track(COPY_A));
    assert!(no_album < a, "ID растут со временем создания");

    new.restore(&old.copy());

    assert_eq!(new.track(COPY_A), song, "похожая копия взяла данные, хотя недоступна");
    assert_eq!(new.track(COPY_NO_ALBUM), no_album);
}

/// Единственная копия недоступна (карта вынута): данные всё равно ложатся на неё и
/// появятся, когда файл вернётся.
#[test]
fn the_only_copy_takes_the_data_even_when_gone() {
    let (old, song) = old_installation();
    let mut new = Installation::scanned(&[COPY_A]);
    new.make_unavailable(COPY_A);

    new.restore(&old.copy());

    assert_eq!(new.track(COPY_A), song);
    assert!(new.liked_titles().is_empty(), "пока файла нет, в списках её нет");
    new.set_availability(COPY_A, Availability::Available);
    assert_eq!(new.liked_titles(), ["Song"], "файл вернулся — лайк на месте");
}
