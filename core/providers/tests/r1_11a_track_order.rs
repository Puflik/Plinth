//! Р1.11а (`docs/work/r1-11a.md`, находка 11 ревью v0.2): порядок треков
//! элемента Internet Archive — по диску и номеру, без номера и при равных
//! номерах — в естественном порядке имён («2» раньше «10»). Диск `/metadata`
//! не называет (в 116 просмотренных настоящих элементах поля нет), его видно
//! по имени файла (`…d2t05…` у etree) и по папке (`CD2/`, `Disc 2/`).
//!
//! Тесты идут через `InternetArchive::resolve` — публичный вход; названием
//! трека служит имя его файла, так что порядок читается из названий.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

use std::sync::Arc;

use plinth_providers::http::HttpResponse;
use plinth_providers::model::ExternalId;
use plinth_providers::testing::FixtureTransport;
use plinth_providers::{InternetArchive, Provider};

/// Файл элемента: имя и номер трека в тегах (`""` — тега нет).
type File = (&'static str, &'static str);

/// Треки элемента `show` из FLAC-файлов `files`: (название = имя файла, диск, номер).
fn tracks(files: &[File]) -> Vec<(String, Option<u16>, Option<u16>)> {
    let entries: Vec<String> = files
        .iter()
        .map(|(name, track)| {
            let tag = if track.is_empty() { String::new() } else { format!(r#", "track": "{track}""#) };
            format!(r#"{{ "name": "{name}", "format": "Flac", "title": "{name}"{tag} }}"#)
        })
        .collect();
    let body = format!(r#"{{ "metadata": {{ "title": "Show" }}, "files": [{}] }}"#, entries.join(","));
    let transport = FixtureTransport::new().on("https://archive.org/metadata/show", [Ok(HttpResponse::ok(body))]);
    let archive = InternetArchive::new(Arc::new(transport)).unwrap().with_pause(|_| {});
    let found = archive.resolve(&ExternalId::new("show").unwrap()).unwrap();
    found.into_iter().map(|track| (track.title, track.disc, track.number)).collect()
}

/// Названия по порядку. Порядок не зависит от того, в каком порядке файлы
/// перечислены в ответе: тот же список задом наперёд даёт то же самое.
fn order(files: &[File]) -> Vec<String> {
    let forward: Vec<String> = tracks(files).into_iter().map(|(title, ..)| title).collect();
    let mut reversed_files = files.to_vec();
    reversed_files.reverse();
    let backward: Vec<String> = tracks(&reversed_files).into_iter().map(|(title, ..)| title).collect();
    assert_eq!(forward, backward, "the order must not depend on the order of the files in the answer");
    forward
}

#[test]
fn discs_numbered_each_on_its_own_stay_apart() {
    let files = [
        ("gd77d1t01.flac", "01"),
        ("gd77d2t01.flac", "01"),
        ("gd77d1t02.flac", "02"),
        ("gd77d2t02.flac", "02"),
        ("gd77d1t03.flac", "03"),
        ("gd77d2t03.flac", "03"),
    ];

    assert_eq!(
        order(&files),
        ["gd77d1t01.flac", "gd77d1t02.flac", "gd77d1t03.flac", "gd77d2t01.flac", "gd77d2t02.flac", "gd77d2t03.flac"]
    );
    let found = tracks(&files);
    let discs: Vec<(Option<u16>, Option<u16>)> = found.iter().map(|(_, disc, number)| (*disc, *number)).collect();
    assert_eq!(
        discs,
        [
            (Some(1), Some(1)),
            (Some(1), Some(2)),
            (Some(1), Some(3)),
            (Some(2), Some(1)),
            (Some(2), Some(2)),
            (Some(2), Some(3))
        ]
    );
}

/// Страж: у концерта сквозная нумерация (так лежит большинство etree, например
/// Cornell 5/8/77) — порядок тот же, что и по номерам.
#[test]
fn numbers_running_through_the_discs_keep_their_order() {
    let files = [("s-d2t01.flac", "3"), ("s-d1t02.flac", "2"), ("s-d2t02.flac", "4"), ("s-d1t01.flac", "1")];

    assert_eq!(order(&files), ["s-d1t01.flac", "s-d1t02.flac", "s-d2t01.flac", "s-d2t02.flac"]);
}

#[test]
fn etree_names_spell_the_disc_in_several_ways() {
    let files = [
        ("show.2006.D2T1.flac", "1"),
        ("gd77-05-08d1t01.flac", "1"),
        ("set_d4t10.flac", "1"),
        ("show-d3t001.flac", "1"),
    ];

    assert_eq!(order(&files), ["gd77-05-08d1t01.flac", "show.2006.D2T1.flac", "show-d3t001.flac", "set_d4t10.flac"]);
    let discs: Vec<Option<u16>> = tracks(&files).into_iter().map(|(_, disc, _)| disc).collect();
    assert_eq!(discs, [Some(1), Some(2), Some(3), Some(4)]);
}

#[test]
fn folders_name_the_disc() {
    let files = [
        ("CD2/01 Reprise.flac", "1"),
        ("CD1/02 Song.flac", "2"),
        ("CD1/01 Intro.flac", "1"),
        ("CD2/02 Finale.flac", "2"),
    ];

    assert_eq!(order(&files), ["CD1/01 Intro.flac", "CD1/02 Song.flac", "CD2/01 Reprise.flac", "CD2/02 Finale.flac"]);
    let discs: Vec<Option<u16>> = tracks(&files).into_iter().map(|(_, disc, _)| disc).collect();
    assert_eq!(discs, [Some(1), Some(1), Some(2), Some(2)]);
}

#[test]
fn folder_spellings_of_a_disc() {
    let files = [("Disk-4/a.flac", "1"), ("cd1/a.flac", "1"), ("disc_3/a.flac", "1"), ("CD 2 - Live/a.flac", "1")];

    assert_eq!(order(&files), ["cd1/a.flac", "CD 2 - Live/a.flac", "disc_3/a.flac", "Disk-4/a.flac"]);
}

#[test]
fn disc_folders_compare_as_numbers() {
    let files = [("Disc 10/01.flac", "1"), ("Disc 2/01.flac", "1"), ("Disc 1/01.flac", "1")];

    assert_eq!(order(&files), ["Disc 1/01.flac", "Disc 2/01.flac", "Disc 10/01.flac"]);
}

/// Диск — в папке выше: `Album/CD2/…` — второй диск, и он важнее номера.
#[test]
fn the_disc_folder_may_be_deeper_in_the_path() {
    let files = [("Album/CD2/01 Reprise.flac", "1"), ("Album/CD1/02 Song.flac", "2")];

    assert_eq!(order(&files), ["Album/CD1/02 Song.flac", "Album/CD2/01 Reprise.flac"]);
}

#[test]
fn tracks_with_a_disc_come_before_the_ones_without_one() {
    let files = [("Aardvark bonus.flac", "1"), ("CD2/01.flac", "1"), ("CD1/01.flac", "1")];

    assert_eq!(order(&files), ["CD1/01.flac", "CD2/01.flac", "Aardvark bonus.flac"]);
}

/// Безномерной трек остаётся на своём диске, а не уезжает в конец альбома.
#[test]
fn a_track_without_a_number_stays_on_its_disc() {
    let files = [("CD2/01 Reprise.flac", "1"), ("CD1/Interlude.flac", ""), ("CD1/01 Intro.flac", "1")];

    assert_eq!(order(&files), ["CD1/01 Intro.flac", "CD1/Interlude.flac", "CD2/01 Reprise.flac"]);
}

/// Что только похоже на диск, диском не считается: порядок идёт по номерам.
#[test]
fn names_that_only_look_like_discs_are_not_discs() {
    let files = [
        ("Odd1t3.flac", "2"),
        ("Discography/Zulu.flac", "4"),
        ("cd2 intro.flac", "1"),
        ("CD 2007 Remaster/Mike.flac", "3"),
    ];

    assert_eq!(order(&files), ["cd2 intro.flac", "Odd1t3.flac", "CD 2007 Remaster/Mike.flac", "Discography/Zulu.flac"]);
    assert!(tracks(&files).iter().all(|(_, disc, _)| disc.is_none()));
}

#[test]
fn files_without_numbers_go_in_natural_name_order() {
    let files =
        [("10 Finale.flac", ""), ("3 Song.flac", ""), ("1 Intro.flac", ""), ("11 Bonus.flac", ""), ("2 Song.flac", "")];

    assert_eq!(order(&files), ["1 Intro.flac", "2 Song.flac", "3 Song.flac", "10 Finale.flac", "11 Bonus.flac"]);
}

/// Равные номера (тег с номером диска вместо номера трека, как у настоящего
/// `ird059`) — не порядок: решает естественный порядок имён.
#[test]
fn equal_numbers_fall_back_to_natural_names() {
    let files = [("track_10.flac", "1"), ("track_2.flac", "1"), ("track_1.flac", "1"), ("track_02b.flac", "1")];

    assert_eq!(order(&files), ["track_1.flac", "track_2.flac", "track_02b.flac", "track_10.flac"]);
}

#[test]
fn natural_order_ignores_case_and_leaves_a_total_order() {
    let files = [("b.flac", ""), ("B.flac", ""), ("a2.flac", ""), ("A10.flac", ""), ("a1.flac", "")];

    assert_eq!(order(&files), ["a1.flac", "a2.flac", "A10.flac", "B.flac", "b.flac"]);
}

/// Страж: номера в тегах важнее имён, а безномерные идут после.
#[test]
fn numbered_tracks_keep_their_numbers_whatever_the_names_say() {
    let files = [("Mike.flac", "3"), ("Zulu.flac", "1"), ("0 hidden.flac", ""), ("Alpha.flac", "2"), ("Echo.flac", "")];

    assert_eq!(order(&files), ["Zulu.flac", "Alpha.flac", "Mike.flac", "0 hidden.flac", "Echo.flac"]);
}
