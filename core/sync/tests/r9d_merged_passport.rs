//! Р9г (`docs/work/r9d.md`), №18 ревью R1: когда данные двух треков сложены на один,
//! паспорт слитого трека остаётся в журнале потерянным и позже забирает дубль песни:
//! дубль получает ID слитого трека и его прослушивания, уже скопированные на основной, —
//! в «Недавнем» песня дважды, счётчики двоятся. Теперь слитый трек теряет паспорт.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r9d_support;

use plinth_types::TrackId;
use r9d_support::{Installation, SONG, SONG_COPY};

fn recent(install: &Installation) -> Vec<TrackId> {
    install.db.recent_tracks(10).unwrap().into_iter().map(|row| row.id).collect()
}

/// Сценарий ревью: после переустановки песню сыграли до перепривязки; перепривязка сложила
/// данные на ID журнала (меньший). Потом в библиотеке появился дубль той же песни.
#[test]
fn a_merged_track_does_not_come_back_as_a_duplicate() {
    let mut old = Installation::scanned(&[SONG]);
    let old_song = old.track(SONG);
    old.like(old_song);
    old.play(old_song, 1);
    let mut new = Installation::scanned(&[SONG]);
    let fresh = new.track(SONG);
    assert!(old_song < fresh, "ID растут со временем создания");
    new.play(fresh, 2);

    new.restore(&old.copy());

    assert_eq!(new.track(SONG), old_song, "каталог перешёл на ID журнала");
    assert_eq!(new.plays_of(old_song), 2, "прослушивания обеих установок — на треке журнала");
    assert!(new.journal.passport(fresh).is_none(), "паспорт слитого трека снят");

    new.scan(&[SONG_COPY]);
    assert_eq!(new.relink(), 0, "дубль ничей: перепривязывать нечего");

    let copy = new.track(SONG_COPY);
    assert_ne!(copy, fresh, "дубль не получает ID слитого трека");
    assert_ne!(copy, old_song);
    assert_eq!(new.plays_of(copy), 0, "и его прослушиваний");
    assert_eq!(new.db.user_data(copy).unwrap().play_count, 0);
    assert_eq!(new.plays_of(old_song), 2, "у основного ничего не прибавилось");
    assert_eq!(recent(&new), [old_song], "в «Недавнем» песня один раз");
}

/// То же, когда ID потерянного больше: данные встают на трек каталога, и паспорт
/// потерянного снимается — иначе он забрал бы дубль вместе с прослушиваниями.
#[test]
fn a_track_folded_onto_a_local_one_does_not_come_back_as_a_duplicate() {
    let mut phone = Installation::scanned(&[SONG]);
    let mut tablet = Installation::scanned(&[SONG]);
    let (song, tablet_song) = (phone.track(SONG), tablet.track(SONG));
    assert!(song < tablet_song, "ID растут со временем создания");
    phone.like(song);
    tablet.play(tablet_song, 1);
    tablet.play(tablet_song, 2);

    phone.restore(&tablet.copy());

    assert_eq!(phone.track(SONG), song, "трек телефона остался");
    assert_eq!(phone.plays_of(song), 2, "прослушивания планшета встали на него");
    assert!(phone.journal.passport(tablet_song).is_none(), "паспорт слитого трека снят");

    phone.scan(&[SONG_COPY]);
    assert_eq!(phone.relink(), 0, "дубль ничей: перепривязывать нечего");

    let copy = phone.track(SONG_COPY);
    assert_ne!(copy, tablet_song, "дубль не получает ID слитого трека");
    assert_eq!(phone.plays_of(copy), 0, "и его прослушиваний");
    assert_eq!(phone.plays_of(song), 2);
    assert_eq!(recent(&phone), [song], "в «Недавнем» песня один раз");
}
