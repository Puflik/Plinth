//! Р9е (`docs/work/r9f.md`): защищённый юнит-тест `Store`. Копию рядом, оборвавшуюся
//! посреди записи (полный диск), через файловую систему не вызвать, поэтому в `Store` есть
//! проверочный крючок (только `#[cfg(test)]`, сигнатура — в файле задачи):
//! `fail_next_aside_midway`.

use std::fs;
use std::path::PathBuf;

use plinth_types::DeviceId;

use super::{HEADER_LEN, Store, TAIL};

/// Пустой каталог журнала для теста; стирается в `Drop`.
struct Scratch(PathBuf);

impl Scratch {
    fn new() -> Self {
        Self(std::env::temp_dir().join(format!("plinth-journal-{}", DeviceId::new())))
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// Всё, что лежит в папке копий `journal/aside/`; нет папки — пусто.
fn aside(dir: &Scratch) -> Vec<String> {
    let mut found: Vec<String> = fs::read_dir(dir.0.join("aside"))
        .into_iter()
        .flatten()
        .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    found.sort();
    found
}

/// Хвост из трёх кадров, у второго не сходится сумма. Возвращает байты хвоста.
fn damaged_tail(dir: &Scratch) -> Vec<u8> {
    let (mut store, _) = Store::open(&dir.0).unwrap();
    for frame in [b"one".as_slice(), b"two", b"three"] {
        store.persist(frame, Vec::new).unwrap();
    }
    drop(store);
    let path = dir.0.join(TAIL);
    let mut bytes = fs::read(&path).unwrap();
    bytes[HEADER_LEN + (8 + 3) + 4] ^= 0xff;
    fs::write(&path, &bytes).unwrap();
    bytes
}

/// Копия испорченного хвоста оборвалась посреди записи, как `fs::copy` на полном диске:
/// ни готового имени, ни недописанного файла не остаётся, хвост цел, а когда место
/// появилось, копия получается целой и одной.
#[test]
fn a_copy_cut_short_leaves_nothing_behind() {
    let dir = Scratch::new();
    let damaged = damaged_tail(&dir);
    let (mut store, loaded) = Store::open(&dir.0).unwrap();
    assert!(loaded.repair);

    store.fail_next_aside_midway();
    store.compact(b"state").unwrap();

    assert_eq!(fs::read(dir.0.join(TAIL)).unwrap(), damaged, "копии нет — хвост цел");
    assert!(aside(&dir).is_empty(), "обрывка нет ни под своим именем, ни временным: {:?}", aside(&dir));

    store.persist(b"again", || b"state again".to_vec()).unwrap();

    let copies = aside(&dir);
    assert_eq!(copies.len(), 1, "копия сделана, как только это стало возможно: {copies:?}");
    assert_eq!(fs::read(dir.0.join("aside").join(&copies[0])).unwrap(), damaged, "копия — хвост как он был");
}
