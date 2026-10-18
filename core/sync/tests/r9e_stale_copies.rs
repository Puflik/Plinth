//! Р9д (`docs/work/r9e.md`, ревью R1, находки 5 и 20): как «Восстановить» читает копии из папки, когда
//! журнал начат заново (`start_over`). Копия сменённой установки — и всякая копия, целиком вложенная в
//! неё (предок: старая установка, чью копию сменённая когда-то восстановила), — прежняя: её данные не
//! вливаются и не предлагаются, потому что журнал из базы уже включает всё, что человек оставил. Из
//! прежней берутся только паспорта треков, на которые в журнале есть данные, а паспорта нет. Копия
//! другой, живой установки вливается как обычно.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod common;
mod r9e_support;

use plinth_sync::journal::Op;
use r9e_support::{FOURTH, Installation, OTHER, SONG, THIRD};

/// Находка 5, сценарий ревью: человек удалил плейлист, снял оценку и лайк после того, как журнал
/// начался заново, — копия прежней установки не возвращает их и не предлагается; журнал после
/// «Восстановить» тот же.
#[test]
fn a_replaced_installations_copy_is_neither_news_nor_merged() {
    let mut old = Installation::scanned(&[SONG, OTHER]);
    let (song, other) = (old.track(SONG), old.track(OTHER));
    old.like(song);
    old.rate(other, 4);
    let road = old.list("Road", other);
    let (stale_name, stale) = (old.name(), old.copy());
    let mut new = old.started_over();
    new.record(&Op::DeletePlaylist { playlist: road.id });
    new.record(&Op::Rate { track: other, rating: None });
    new.record(&Op::Unlike { track: song });
    let before = new.journal.state();

    let found = new.inspect(&[(&stale_name, &stale)]);
    let restored = new.restore(&[(&stale_name, &stale)]);

    assert!(!found.news, "{found:?}");
    assert_eq!((restored.merged, restored.unreadable), (0, 0));
    assert_eq!(new.journal.state(), before, "the journal changed");
    assert!(new.playlist_names().is_empty(), "«Road» is back");
    assert_eq!(new.rating(other), None, "the rating is back");
    assert!(new.liked_titles().is_empty(), "the like is back");
}

/// Предок сменённой копии — прежний тоже: установка `c0` восстановила копию старой `z`, потом сама удалила
/// плейлист, потом её журнал начат заново. Копия `z` лежит в папке навсегда и целиком вложена в копию
/// `c0` — без этого она воскрешала бы всё, что `c0` успела удалить. В любом порядке.
#[test]
fn an_ancestor_of_a_replaced_copy_is_stale_too() {
    let mut z = Installation::scanned(&[SONG]);
    let song = z.track(SONG);
    z.like(song);
    let old = z.list("Old", song);
    let (z_name, z_copy) = (z.name(), z.copy());
    let mut c0 = Installation::scanned(&[SONG]);
    c0.restore(&[(&z_name, &z_copy)]);
    assert_eq!(c0.playlist_names(), ["Old"], "the reinstall brought the playlist back");
    c0.record(&Op::DeletePlaylist { playlist: old.id });
    let (c0_name, c0_copy) = (c0.name(), c0.copy());
    let mut new = c0.started_over();
    let before = new.journal.state();

    for files in [[(&z_name, &z_copy), (&c0_name, &c0_copy)], [(&c0_name, &c0_copy), (&z_name, &z_copy)]] {
        let files = files.map(|(name, bytes)| (name.as_str(), bytes.as_slice()));
        let found = new.inspect(&files);
        let restored = new.restore(&files);

        assert!(!found.news, "{found:?}");
        assert_eq!(restored.merged, 0);
        assert_eq!(new.journal.state(), before, "the journal changed");
        assert!(new.playlist_names().is_empty(), "«Old» is back");
    }
}

/// Копия другой установки, которая не сменена и не вложена в сменённую, вливается как раньше —
/// рядом с копией сменённой, которая остаётся в стороне.
#[test]
fn another_installations_copy_still_merges() {
    let mut a = Installation::scanned(&[SONG, OTHER]);
    let (song, other) = (a.track(SONG), a.track(OTHER));
    a.like(song);
    a.rate(other, 3);
    let (a_name, a_copy) = (a.name(), a.copy());
    let mut b = a.started_over();
    let other_b = b.track(OTHER);
    b.record(&Op::Rate { track: other_b, rating: None });
    // Второй телефон: свой каталог, свой лайк «Other».
    let mut phone = Installation::scanned(&[SONG, OTHER]);
    let other_phone = phone.track(OTHER);
    phone.like(other_phone);
    let (phone_name, phone_copy) = (phone.name(), phone.copy());
    let files = [(a_name.as_str(), a_copy.as_slice()), (phone_name.as_str(), phone_copy.as_slice())];

    let found = b.inspect(&files);
    let restored = b.restore(&files);

    assert!(found.news, "{found:?}");
    assert_eq!(restored.merged, 1, "only the live copy brings something");
    assert_eq!(b.liked_titles(), ["Other", "Song"], "the phone's like came, the replaced copy's data did not");
    assert_eq!(b.rating(other_b), None, "the rating removed after the damage is back");
}

/// Находка 20: трек, которого нет в каталоге (карта не вставлена), живёт в журнале одним паспортом и
/// данными. Журнал начат заново из базы — паспорта трека в нём нет. Копия сменённой установки отдаёт
/// паспорт обратно, и только для треков, на которые в журнале есть данные; когда файл появится, скан
/// возвращает треку его лайк. Данные копии не вливаются. Повторное «Восстановить» ничего не меняет.
#[test]
fn a_replaced_copy_gives_back_passports_only_for_tracks_that_have_data() {
    // Старая установка: четыре песни; у «Fourth» лайк снят — паспорт остался, данных нет.
    let mut z = Installation::scanned(&[SONG, OTHER, THIRD, FOURTH]);
    for song in [SONG, OTHER, THIRD, FOURTH] {
        let track = z.track(song);
        z.like(track);
    }
    let fourth = z.track(FOURTH);
    z.record(&Op::Unlike { track: fourth });
    let (z_name, z_copy) = (z.name(), z.copy());
    // Переустановка: карты с «Third» и «Fourth» нет. Копия возвращает данные и паспорта; Song и Other узнаны.
    let mut c0 = Installation::scanned(&[SONG, OTHER]);
    c0.restore(&[(&z_name, &z_copy)]);
    let third = z.track(THIRD);
    assert!(c0.db.user_data(third).unwrap().liked, "the data of the missing track came over");
    assert!(c0.journal.passport(third).is_some() && c0.journal.passport(fourth).is_some());
    let (c0_name, c0_copy) = (c0.name(), c0.copy());
    let mut new = c0.started_over();
    assert!(new.journal.passport(third).is_none(), "nothing describes a track the catalog lacks");
    let other = new.track(OTHER);
    new.record(&Op::Unlike { track: other });
    // Карту вставили, скан нашёл файл: без паспорта он не узнан.
    new.scan(&[THIRD]);
    assert_eq!(new.relink(), 0);
    assert_eq!(new.liked_titles(), ["Song"]);
    let files = [(z_name.as_str(), z_copy.as_slice()), (c0_name.as_str(), c0_copy.as_slice())];

    let found = new.inspect(&files);
    let restored = new.restore(&files);

    assert!(found.news, "a passport that can come back is news: {found:?}");
    assert!(new.journal.passport(third).is_some(), "the passport did not come back");
    assert!(new.journal.passport(fourth).is_none(), "a passport of a track without data is not taken");
    assert_eq!(restored.merged, 1, "only the replaced copy that has the passport brings something");
    assert_eq!(new.liked_titles(), ["Song", "Third"], "the file got its old like back; «Other» stayed unliked");
    let state = new.journal.state();
    assert!(!new.inspect(&files).news, "nothing left to bring");
    assert_eq!(new.restore(&files).merged, 0);
    assert_eq!(new.journal.state(), state, "the second restore changed the journal");
}

/// Папка могла переименовать файл (`… (1)`): копия сменённой установки узнаётся по началу имени — так же,
/// как своя копия в `Core`, которую восстановление пропускает.
#[test]
fn a_renamed_file_of_a_replaced_installation_is_still_stale() {
    let mut old = Installation::scanned(&[SONG]);
    let song = old.track(SONG);
    old.like(song);
    let road = old.list("Road", song);
    let name = format!("{} (1)", old.name());
    let stale = old.copy();
    let mut new = old.started_over();
    new.record(&Op::DeletePlaylist { playlist: road.id });
    let before = new.journal.state();

    let found = new.inspect(&[(&name, &stale)]);
    let restored = new.restore(&[(&name, &stale)]);

    assert!(!found.news, "{found:?}");
    assert_eq!(restored.merged, 0);
    assert_eq!(new.journal.state(), before, "the journal changed");
}
