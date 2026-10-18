//! Р9б (`docs/work/r9b.md`, «Не проверено» ревью R1, журнал): чистка отложенных
//! файлов `*.damaged-*` и `*.foreign-*`. Их никто не стирал: каждая порча оставляла
//! копию навсегда, а правило Auto Backup берёт `core/journal/` (квота 25 МБ на
//! приложение, и перебор отключает копирование совсем). Теперь остаются только
//! новейшие: `tail.damaged-` — две, `snapshot.damaged-` и `tail.foreign-` — по одной.
//! Имя несёт время в миллисекундах: новизну решает оно. Чужие файлы папки и имена не по
//! образцу не трогаются. С Р9е копии лежат в `journal/aside/` (`r9f_aside_folder.rs`).

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r9b_support;

use std::fs;

use common::Scratch;
use plinth_sync::journal::Journal;
use plinth_types::DeviceId;
use r9b_support::{FRAME_HEADER, Pristine, spans};

fn names(dir: &Scratch) -> Vec<String> {
    let mut found: Vec<String> = fs::read_dir(dir.journal().join("aside"))
        .into_iter()
        .flatten()
        .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    found.sort();
    found
}

fn plant(dir: &Scratch, files: &[&str]) {
    fs::create_dir_all(dir.journal().join("aside")).unwrap();
    for name in files {
        fs::write(dir.journal().join("aside").join(name), b"aside").unwrap();
    }
}

#[test]
fn only_the_newest_aside_files_stay() {
    let dir = Scratch::new();
    drop(Journal::open(&dir.journal(), DeviceId::new()).unwrap());
    plant(
        &dir,
        &[
            "tail.damaged-1000",
            "tail.damaged-3000",
            "tail.damaged-2000",
            "snapshot.damaged-1000",
            "snapshot.damaged-2000",
            "tail.foreign-1000",
            "tail.foreign-2000",
            // Не по образцу — не наше, не трогать.
            "tail.damaged-notanumber",
            "tail.damaged",
            "notes.txt",
        ],
    );

    drop(Journal::open(&dir.journal(), DeviceId::new()).unwrap());

    assert_eq!(
        names(&dir),
        [
            "notes.txt",
            "snapshot.damaged-2000",
            "tail.damaged",
            "tail.damaged-2000",
            "tail.damaged-3000",
            "tail.damaged-notanumber",
            "tail.foreign-2000",
        ]
    );
}

/// Новая копия при порче тоже вытесняет старые: за раз их не больше двух.
#[test]
fn a_new_copy_pushes_the_oldest_out() {
    let pristine = Pristine::new(4);
    let mut tail = pristine.tail.clone();
    tail[spans(&pristine.tail)[2].0 + FRAME_HEADER] ^= 0xff;
    let dir = pristine.dir_with(&tail);
    plant(&dir, &["tail.damaged-1000", "tail.damaged-2000"]);

    drop(Journal::open(&dir.journal(), DeviceId::new()).unwrap());

    let copies: Vec<String> = names(&dir).into_iter().filter(|name| name.starts_with("tail.damaged-")).collect();
    assert_eq!(copies.len(), 2, "{copies:?}");
    assert!(copies.contains(&"tail.damaged-2000".to_owned()), "прежняя вторая осталась: {copies:?}");
    assert!(!copies.contains(&"tail.damaged-1000".to_owned()), "старейшая вытеснена: {copies:?}");
    let new = copies.iter().find(|name| name.as_str() != "tail.damaged-2000").unwrap();
    assert_eq!(fs::read(dir.journal().join("aside").join(new)).unwrap(), tail, "новая копия — испорченный хвост");
}
