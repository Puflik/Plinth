//! Р1.15 (находка 4 ревью FFI/сканера), сторона `diff`: обход сообщает пути,
//! которые не смог прочесть (`Walk::unseen`), и файл на таком пути или под ним
//! не пропал — ни в `missing`, ни в `gone`, а значит, и сведение перенесённых
//! (Р1.2) его не берёт. Как обход собирает `unseen` из настоящей файловой
//! системы, проверяет `r1_15_unreadable_entries.rs`.
//!
//! Поле `unseen` — новое (сигнатура в `docs/work/r1-15.md`): пока его нет, файл
//! не собирается.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::collections::{BTreeSet, HashMap};
use std::path::MAIN_SEPARATOR_STR as SEP;
use std::time::{Duration, Instant};

use plinth_library::db::repo::KnownFile;
use plinth_library::scan::{Walk, diff};
use plinth_types::{SourceId, Timestamp, TrackId, VersionId};

/// Путь с разделителями платформы: `at("m/dir/a.mp3")`.
fn at(path: &str) -> String {
    format!("{SEP}{}", path.replace('/', SEP))
}

fn known(available: bool) -> KnownFile {
    KnownFile {
        source: SourceId::new(),
        version: VersionId::new(),
        track: TrackId::new(),
        modified_at: Some(Timestamp::from_millis(1)),
        size: Some(10),
        available,
    }
}

/// Пути файлов `files` по каталогу `catalog`.
fn uris(catalog: &HashMap<String, KnownFile>, files: &[KnownFile]) -> BTreeSet<String> {
    files.iter().map(|file| catalog.iter().find(|(_, known)| known.source == file.source).unwrap().0.clone()).collect()
}

/// Неизвестный путь закрывает себя и всё под собой — по целым сегментам пути:
/// `a.mp3.bak` не «под» `a.mp3`, `dirx` не «под» `dir`. Папка, которую не
/// прочесть (`unreadable`), работает как прежде.
#[test]
fn files_at_or_below_an_unseen_path_are_neither_missing_nor_gone() {
    let catalog: HashMap<String, KnownFile> = [
        (at("m/a.mp3"), known(true)),          // сам неизвестный путь
        (at("m/dir/b.mp3"), known(true)),      // под записью, о которой неизвестно, файл ли она
        (at("m/dir/deep/c.mp3"), known(true)), // ещё глубже
        (at("m/dir/d.mp3"), known(false)),     // недоступный там же — не «давно пропавший»
        (at("m/locked/e.mp3"), known(true)),   // в папке, которую не прочесть, — как раньше
        (at("m/a.mp3.bak"), known(true)),      // имя начинается так же, но это другой файл
        (at("m/dirx/f.mp3"), known(true)),     // соседняя папка с похожим именем
        (at("m/other.mp3"), known(true)),      // обычный пропавший
        (at("m/old.mp3"), known(false)),       // обычный давно недоступный
    ]
    .into_iter()
    .collect();
    let walk = Walk { unseen: vec![at("m/a.mp3"), at("m/dir")], unreadable: vec![at("m/locked/")], ..Walk::default() };

    let diff = diff(&catalog, &walk);

    assert_eq!(
        uris(&catalog, &diff.missing),
        BTreeSet::from([at("m/a.mp3.bak"), at("m/dirx/f.mp3"), at("m/other.mp3")]),
    );
    assert_eq!(uris(&catalog, &diff.gone), BTreeSet::from([at("m/old.mp3")]));
}

/// Папка, где не прочлась каждая запись (нет права поиска, сбой карты), даёт
/// тысячи неизвестных путей, а в каталоге — тысячи файлов. Решение о каждом
/// файле не должно перебирать все неизвестные пути: иначе квадрат, и скан
/// большой библиотеки виснет на минуты ровно тогда, когда карта и так больна.
#[test]
fn a_folder_where_every_entry_failed_costs_one_pass() {
    const FILES: usize = 50_000;
    let (listed, gone) = (known(true), known(true));
    let mut catalog = HashMap::with_capacity(2 * FILES);
    let mut unseen = Vec::with_capacity(FILES);
    for index in 0..FILES {
        let uri = at(&format!("m/Music/track-{index}.mp3"));
        catalog.insert(uri.clone(), listed);
        unseen.push(uri);
        catalog.insert(at(&format!("m/Gone/track-{index}.mp3")), gone);
    }
    let walk = Walk { unseen, ..Walk::default() };

    let started = Instant::now();
    let diff = diff(&catalog, &walk);
    let elapsed = started.elapsed();

    assert_eq!(diff.missing.len(), FILES, "Gone/ пропала на деле, Music/ неизвестна");
    assert!(elapsed < Duration::from_secs(10), "{elapsed:?}: по каждому файлу перебираются все неизвестные пути?");
}
