//! Р9б (`docs/work/r9b.md`, «Не проверено» ревью R1, журнал и FFI): ядро не
//! запирается из-за того, что журнал не удаётся записать.
//!
//! - Паника посреди вызова отравляет замок; следующий вызов сворачивает журнал в
//!   снимок и пересобирает проекцию (`Core::with`). Свёртка не удалась (кончилось
//!   место) — раньше отказывал каждый вызов, включая чтения, до перезапуска.
//! - Хвост журнала испорчен, а снимок записать нельзя: открытие не требует места.
//!
//! Отказ записи имитирован каталогом на месте `snapshot.tmp`.

use std::fs;
use std::path::PathBuf;

use plinth_types::{CoreError, TrackId};

use crate::session::{Core, JOURNAL};
use crate::testing::Scratch;

/// Снимок записать нельзя: на месте временного файла каталог.
fn block_snapshots(dir: &Scratch) -> PathBuf {
    let blocker = dir.0.join(JOURNAL).join("snapshot.tmp");
    fs::create_dir(&blocker).unwrap();
    blocker
}

/// Паника посреди вызова, и сразу после неё записать журнал нельзя: чтения
/// работают, записи честно отвечают ошибкой, а когда место появилось, первая же
/// запись кладёт на диск всё, в том числе правку, о которой сказали «ошибка».
#[test]
fn reads_keep_working_after_a_panic_when_the_journal_cannot_be_saved() {
    let dir = Scratch::new();
    let (kept, refused, after) = (TrackId::new(), TrackId::new(), TrackId::new());
    let core = Core::open(dir.path()).unwrap();
    core.like(kept).unwrap();
    let blocker = block_snapshots(&dir);

    let panicked = core.panic_inside_the_lock();

    assert!(matches!(panicked, Err(CoreError::Internal { .. })));
    let read = core.user_data(kept);
    assert!(read.as_ref().is_ok_and(|data| data.liked), "чтение после паники должно работать: {read:?}");
    let _ = core.like(refused);

    fs::remove_dir(&blocker).unwrap();
    core.like(after).unwrap();
    drop(core);
    let core = Core::open(dir.path()).unwrap();
    for (track, what) in
        [(kept, "лайк до паники"), (refused, "правка, пережившая отказ в памяти"), (after, "лайк после")]
    {
        assert!(core.user_data(track).unwrap().liked, "{what}");
    }
}

/// Хвост испорчен (третий кадр), а снимок записать нельзя: ядро открывается, лайки до
/// порчи на месте. Раньше открытие падало на свёртке, которой нужно место.
#[test]
fn the_core_opens_with_a_damaged_tail_when_nothing_can_be_written() {
    let dir = Scratch::new();
    let tracks: Vec<TrackId> = (0..4).map(|_| TrackId::new()).collect();
    {
        let core = Core::open(dir.path()).unwrap();
        for track in &tracks {
            core.like(*track).unwrap();
        }
    }
    let tail = dir.0.join(JOURNAL).join("tail");
    let mut bytes = fs::read(&tail).unwrap();
    // Заголовок хвоста — 22 байта, кадр — длина, CRC32 и данные: портим данные третьего кадра.
    let mut at = 22;
    for _ in 0..2 {
        at += 8 + u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
    }
    bytes[at + 8] ^= 0xff;
    fs::write(&tail, &bytes).unwrap();
    block_snapshots(&dir);

    let opened = Core::open(dir.path());

    let core = opened.expect("ядро должно открыться: данные до порчи целы, а место не нужно");
    assert!(core.user_data(tracks[0]).unwrap().liked);
    assert!(core.user_data(tracks[1]).unwrap().liked);
    assert!(!core.user_data(tracks[3]).unwrap().liked, "за порчей читать нечего");
}
