//! Р9б (`docs/work/r9b.md`): защищённые юнит-тесты `Store`. Две вещи нельзя вызвать
//! через `Journal::open` и файловую систему: копию рядом, которая не удалась на
//! полном диске, и сброс хвоста, оборвавшийся уже после усечения файла. Для них в
//! `Store` есть проверочные крючки (только `#[cfg(test)]`, сигнатуры — в файле
//! задачи): `fail_next_aside` и `fail_next_tail_header`.

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

/// Копии испорченного хвоста рядом.
fn aside(dir: &Scratch) -> Vec<PathBuf> {
    let mut found: Vec<PathBuf> = fs::read_dir(&dir.0)
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .filter(|path| path.file_name().unwrap().to_string_lossy().starts_with("tail.damaged-"))
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
    // Заголовок, первый кадр (8 + 3 байта), у второго — длина (4 байта) и затем сумма.
    bytes[HEADER_LEN + (8 + 3) + 4] ^= 0xff;
    fs::write(&path, &bytes).unwrap();
    bytes
}

/// Испорченный хвост не стирается, пока не сделана его копия: копия не удалась
/// (полный диск) — хвост остаётся как был, а когда копия удаётся, она делается до
/// сброса, и сброс идёт следом. Снимок за это время уже записан и ничего не теряется.
#[test]
fn a_damaged_tail_is_not_overwritten_before_it_is_copied() {
    let dir = Scratch::new();
    let damaged = damaged_tail(&dir);
    let (mut store, loaded) = Store::open(&dir.0).unwrap();
    assert!(loaded.repair);
    assert_eq!(loaded.frames, vec![b"one".to_vec()]);

    store.fail_next_aside();
    store.compact(b"state").unwrap();

    assert_eq!(fs::read(dir.0.join(TAIL)).unwrap(), damaged, "копии нет — хвост цел");
    assert!(aside(&dir).is_empty());

    store.persist(b"again", || b"state again".to_vec()).unwrap();

    let copies = aside(&dir);
    assert_eq!(copies.len(), 1, "копия сделана, как только это стало возможно");
    assert_eq!(fs::read(&copies[0]).unwrap(), damaged, "копия — хвост как он был");
    drop(store);
    let (store, loaded) = Store::open(&dir.0).unwrap();
    assert_eq!(store.seq(), 2);
    let again = loaded.frames == vec![b"again".to_vec()] && loaded.snapshot == b"state".to_vec();
    let whole = loaded.frames.is_empty() && loaded.snapshot == b"state again".to_vec();
    assert!(again || whole, "правка либо кадром за снимком, либо в новом снимке");
    assert_eq!(aside(&dir).len(), 1, "копия одна");
}

/// Сброс хвоста усёк файл и не смог записать заголовок. Снимок уже записан, в нём
/// всё; хвост без заголовка нельзя дописывать — при открытии он пуст, и правка из него
/// пропала бы. Следующая правка сначала начинает хвост заново (22 байта, место
/// нужно куда меньше, чем под снимок) и ложится за ним кадром.
#[test]
fn a_tail_reset_cut_short_after_truncation_is_started_again_before_any_write() {
    let dir = Scratch::new();
    let (mut store, _) = Store::open(&dir.0).unwrap();
    store.persist(b"one", Vec::new).unwrap();

    store.fail_next_tail_header();
    store.compact(b"state").unwrap();
    store.persist(b"two", || b"state two".to_vec()).unwrap();
    drop(store);

    let (store, loaded) = Store::open(&dir.0).unwrap();
    assert_eq!(loaded.snapshot, b"state".to_vec(), "второго снимка не было: хвост начат заново");
    assert_eq!(loaded.frames, vec![b"two".to_vec()], "правка — кадр за новым заголовком");
    assert!(!loaded.repair);
    assert_eq!(store.seq(), 2);
}

/// Журнал сказал «на диске не всё» (`mark_unsaved`: паника посреди вызова, свёртка не
/// удалась): следующая правка кладётся целым снимком, а не кадром в хвост, которого для
/// восстановления мало.
#[test]
fn after_mark_unsaved_the_next_edit_goes_as_a_whole_snapshot() {
    let dir = Scratch::new();
    let (mut store, _) = Store::open(&dir.0).unwrap();
    store.persist(b"one", Vec::new).unwrap();

    store.mark_unsaved();
    store.persist(b"two", || b"everything".to_vec()).unwrap();
    drop(store);

    let (store, loaded) = Store::open(&dir.0).unwrap();
    assert_eq!(loaded.snapshot, b"everything".to_vec());
    assert!(loaded.frames.is_empty());
    assert_eq!(store.seq(), 2);
}
