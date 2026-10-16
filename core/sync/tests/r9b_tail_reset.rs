//! Р9б (`docs/work/r9b.md`, находка 3 ревью R1): сбой сброса хвоста после
//! свёртки. Свёртка записала новый снимок, а `reset_tail` не смог начать хвост
//! заново; ошибка свёртки — только предупреждение, `persist` отвечает `Ok`, и
//! следующие правки пишутся кадрами в старый хвост. Его заголовок называет
//! прошлый снимок, поэтому при открытии такой хвост считается «хвостом прошлого
//! снимка» и молча отбрасывается: правки, о которых журнал сказал «записано»,
//! пропадают, а человек ничего не видел.
//!
//! Сбой имитирован файловой системой: хвост — «только для чтения», так что
//! `File::create` в `reset_tail` отказывает, а открытый дескриптор дописывает;
//! каталог на месте `snapshot.tmp` не даёт записать следующий снимок (почти
//! полный телефон: кадр в сотню байт помещается, снимок в сотни килобайт — нет).

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r9b_support;

use std::fs;

use common::Scratch;
use plinth_sync::journal::{Journal, Op};
use plinth_types::{DeviceId, TrackId};
use r9b_support::{AllowWritesOnDrop, COMPACT_TAIL_BYTES, deny_writes, like_many, liked, tail_len};

/// Журнал, у которого свёртка записала снимок, а хвост сбросить не вышло, и
/// второй снимок записать уже нельзя.
struct Stuck {
    dir: Scratch,
    journal: Journal,
    /// Лайки, записанные до сбоя и вместе с ним: все они в снимке свёртки.
    before: Vec<TrackId>,
    _unlock: AllowWritesOnDrop,
}

impl Stuck {
    /// `None` — окружение не соблюдает права файлов (запуск от root): сбоя не получить.
    fn new() -> Option<Self> {
        let dir = Scratch::new();
        let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
        let mut before = Vec::new();
        while tail_len(&dir) < 120 * 1024 {
            before.extend(like_many(&mut journal, 300));
        }
        assert!(tail_len(&dir) < COMPACT_TAIL_BYTES, "хвост ещё не должен быть свёрнут");

        let tail = dir.journal().join("tail");
        let unlock = AllowWritesOnDrop(tail.clone());
        if !deny_writes(&tail) {
            eprintln!("skipped: права файла здесь не соблюдаются (root?), сбой записи не получить");
            return None;
        }
        // Эта пачка переводит хвост за предел: свёртка пишет снимок, `reset_tail` отказывает.
        before.extend(like_many(&mut journal, 3000));
        assert!(
            fs::metadata(dir.journal().join("snapshot")).unwrap().len() > 4096,
            "свёртка должна была записать снимок"
        );
        assert!(tail_len(&dir) > COMPACT_TAIL_BYTES, "хвост должен был остаться прежним: его не сбросили");

        // Больше снимок не записать.
        fs::create_dir(dir.journal().join("snapshot.tmp")).unwrap();
        Some(Self { dir, journal, before, _unlock: unlock })
    }

    /// Хвост снова можно начать заново (места на 22 байта и кадр в сотню байт хватает).
    fn free_tail(&self) {
        r9b_support::allow_writes(&self._unlock.0);
    }

    /// Снимок снова можно записать.
    fn free_snapshots(&self) {
        fs::remove_dir(self.dir.journal().join("snapshot.tmp")).unwrap();
    }

    /// Снимает оба препятствия: «диск» снова принимает запись.
    fn recover(&self) {
        self.free_snapshots();
        self.free_tail();
    }
}

/// Правка после сбоя сброса, пока снимок записать нельзя. Журнал отвечает `Ok` —
/// значит, правка переживёт перезапуск; отвечает ошибкой — значит, человек
/// знает, что она не записана. Молча пропасть она не вправе.
#[test]
fn an_edit_after_a_failed_tail_reset_is_either_refused_or_kept() {
    let Some(mut stuck) = Stuck::new() else { return };
    let edit = TrackId::new();

    let recorded = stuck.journal.record(&Op::Like { track: edit });

    stuck.recover();
    let Stuck { dir, journal, before, .. } = stuck;
    drop(journal);
    let likes = liked(&dir);
    assert!(before.iter().all(|track| likes.contains(track)), "всё, что записано до сбоя, цело");
    assert!(
        recorded.is_err() || likes.contains(&edit),
        "журнал ответил «записано», а правка пропала при открытии: хвост со старым заголовком не читается"
    );
}

/// Страж: правка, о которой сказали «не записано», остаётся в памяти, и первая же
/// успешная запись кладёт её вместе с собой.
#[test]
fn once_the_disk_takes_a_write_nothing_is_missing() {
    let Some(mut stuck) = Stuck::new() else { return };
    let edit = TrackId::new();
    let _ = stuck.journal.record(&Op::Like { track: edit });
    stuck.recover();

    let next = TrackId::new();
    stuck.journal.record(&Op::Like { track: next }).unwrap();

    let Stuck { dir, journal, before, .. } = stuck;
    drop(journal);
    let likes = liked(&dir);
    assert!(before.iter().all(|track| likes.contains(track)));
    assert!(likes.contains(&edit), "правка, пережившая сбой в памяти, записана со следующей");
    assert!(likes.contains(&next));
    assert!(tail_len(&dir) < 64 * 1024, "хвост после успешной записи начат заново");
}

/// Хвост снова можно начать заново, а снимок записать по-прежнему нельзя (почти полный
/// телефон: 22 байта заголовка и кадр в сотню байт помещаются, снимок в сотни
/// килобайт — нет). Правка принимается: журнал начинает хвост под записанный снимок и
/// ложится за ним кадром, а не требует целого снимка и не пишет в хвост прошлого.
#[test]
fn a_write_that_fits_is_accepted_even_when_a_snapshot_does_not() {
    let Some(mut stuck) = Stuck::new() else { return };
    stuck.free_tail();
    let edit = TrackId::new();

    stuck
        .journal
        .record(&Op::Like { track: edit })
        .expect("кадр помещается: журнал не должен просить места под снимок");

    let Stuck { dir, journal, before, .. } = stuck;
    drop(journal);
    let _ = fs::remove_dir(dir.journal().join("snapshot.tmp"));
    let likes = liked(&dir);
    assert!(before.iter().all(|track| likes.contains(track)));
    assert!(likes.contains(&edit), "правка, принятая журналом, переживает перезапуск");
}

/// Наоборот: хвост всё ещё не начать заново, а снимок записать можно. Ни одна правка не
/// отказывает из-за хвоста (и не через одну): каждая уходит целым снимком и переживает
/// перезапуск.
#[test]
fn a_snapshot_takes_every_edit_when_only_the_tail_is_stuck() {
    let Some(mut stuck) = Stuck::new() else { return };
    stuck.free_snapshots();
    let edits: Vec<TrackId> = (0..3).map(|_| TrackId::new()).collect();

    for edit in &edits {
        stuck
            .journal
            .record(&Op::Like { track: *edit })
            .expect("снимок записать можно: правка не должна отказывать из-за хвоста");
    }

    stuck.free_tail();
    let Stuck { dir, journal, before, .. } = stuck;
    drop(journal);
    let likes = liked(&dir);
    assert!(before.iter().all(|track| likes.contains(track)));
    assert!(edits.iter().all(|edit| likes.contains(edit)), "каждая принятая правка переживает перезапуск");
}
