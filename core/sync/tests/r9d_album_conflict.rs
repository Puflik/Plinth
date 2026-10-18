//! Р9г (`docs/work/r9d.md`, «Ответы Opus», п. 9): паспорт журнала и трек каталога
//! — пара, только если альбомы не противоречат. Две установки с разными библиотеками
//! — обычный случай: потерянный «Intro» альбома One не должен отдавать лайк «Intro»
//! альбома Two, который случайно совпал ключом и длительностью. Альбом известен у обоих
//! и различается — другая песня; известен у одного или ни у кого — пара возможна:
//! паспорт мог отстать от тегов. Правило действует в обоих шагах перепривязки.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r9d_support;

use common::Song;
use plinth_types::TrackId;
use r9d_support::Installation;

const INTRO_ONE: Song = Song {
    path: "/storage/emulated/0/Music/One/Intro.mp3",
    title: "Intro",
    artist: "Band",
    album: Some("One"),
    duration_ms: Some(60_000),
};

const INTRO_TWO: Song = Song { path: "/storage/emulated/0/Music/Two/Intro.mp3", album: Some("Two"), ..INTRO_ONE };

/// Тот же «Intro», но в тегах нет альбома.
const INTRO_BARE: Song = Song { path: "/storage/emulated/0/Music/Singles/Intro.mp3", album: None, ..INTRO_ONE };

/// Старая установка с лайком на `song`: копия её журнала и ID трека. Новая установка
/// создаётся после неё, поэтому её ID новее.
fn old_copy(song: Song) -> (Vec<u8>, TrackId) {
    let mut old = Installation::scanned(&[song]);
    let track = old.track(song);
    old.like(track);
    (old.copy(), track)
}

/// Шаг 2: потерянный «Intro» (One, с лайком) и занятый «Intro» (Two, со своим лайком) не
/// сливаются; оба паспорта остаются, лайки порознь.
#[test]
fn busy_tracks_of_different_albums_are_not_merged() {
    let (copy, lost) = old_copy(INTRO_ONE);
    let mut new = Installation::scanned(&[INTRO_TWO]);
    let local = new.track(INTRO_TWO);
    new.like(local);
    assert!(lost < local, "ID растут со временем создания");

    let restored = new.restore(&copy);

    assert_eq!(restored.relinked, 0, "перепривязка ничего не нашла");
    assert_eq!(new.relink(), 0, "и повтор тоже");
    assert_eq!(new.track(INTRO_TWO), local, "трек каталога под своим ID");
    assert!(new.journal.passport(lost).is_some(), "паспорт потерянного на месте");
    assert!(new.journal.passport(local).is_some(), "паспорт занятого на месте");
    let likes = new.journal.state().likes;
    assert!(likes.contains(&lost) && likes.contains(&local), "лайки порознь: {likes:?}");
    assert_eq!(new.liked_titles(), ["Intro"], "в каталоге лайк только у своего «Intro»");
}

/// Шаг 1: потерянный «Intro» (One) и свободный «Intro» (Two) — трек каталога остаётся под
/// своим ID, паспорт — потерянным.
#[test]
fn a_free_track_of_another_album_keeps_its_own_id() {
    let (copy, lost) = old_copy(INTRO_ONE);
    let mut new = Installation::scanned(&[INTRO_TWO]);
    let local = new.track(INTRO_TWO);

    let restored = new.restore(&copy);

    assert_eq!(restored.relinked, 0, "перепривязка ничего не нашла");
    assert_eq!(new.track(INTRO_TWO), local, "трек каталога остаётся под своим ID");
    assert!(new.journal.passport(lost).is_some(), "паспорт остаётся потерянным");
    assert!(new.liked_titles().is_empty(), "лайка потерянного трека в каталоге нет");
}

/// Страж, шаг 1: альбом известен только у одного — пара, как раньше; в обе стороны.
#[test]
fn a_free_track_with_an_album_known_on_one_side_only_still_pairs() {
    for (lost_song, new_song) in [(INTRO_ONE, INTRO_BARE), (INTRO_BARE, INTRO_ONE)] {
        let (copy, lost) = old_copy(lost_song);
        let mut new = Installation::scanned(&[new_song]);

        let restored = new.restore(&copy);

        assert_eq!(restored.relinked, 1, "{lost_song:?} → {new_song:?}");
        assert_eq!(new.track(new_song), lost, "каталог взял ID журнала");
        assert_eq!(new.liked_titles(), ["Intro"]);
    }
}

/// Страж, шаг 2: то же для занятого трека — данные встают на меньший ID (потерянного),
/// каталог переходит на него, паспорт слитого трека снят.
#[test]
fn a_busy_track_with_an_album_known_on_one_side_only_still_merges() {
    for (lost_song, new_song) in [(INTRO_ONE, INTRO_BARE), (INTRO_BARE, INTRO_ONE)] {
        let (copy, lost) = old_copy(lost_song);
        let mut new = Installation::scanned(&[new_song]);
        let local = new.track(new_song);
        new.like(local);
        assert!(lost < local, "ID растут со временем создания");

        let restored = new.restore(&copy);

        assert_eq!(restored.relinked, 1, "{lost_song:?} → {new_song:?}");
        assert_eq!(new.track(new_song), lost, "каталог взял меньший ID — журнала");
        assert!(new.journal.passport(local).is_none(), "паспорт слитого трека снят");
        assert_eq!(new.liked_titles(), ["Intro"]);
    }
}
