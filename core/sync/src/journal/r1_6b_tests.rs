//! Р1.6б (`docs/work/r1-6b.md`): множества журнала — лайки, подписки, чёрный
//! список — лежат слотами `ключ@<установка>`, как регистры с Р1.6.
//!
//! Раньше ключ был один на всех: две установки без общей истории, добавившие один
//! элемент, `yrs` разводил по номеру клиента — оставалась вставка с большим
//! номером, даже если её потом сняли, и добавление пропадало (ADR 0007 обещает
//! обратное). Номер клиента здесь задан руками, поэтому тесты падают всегда, а не
//! через раз.

use std::sync::Arc;

use plinth_library::model::{BlockEntry, BlockTarget, Subscription};
use plinth_types::{ArtistId, DeviceId, Timestamp, TrackId};
use yrs::updates::decoder::Decode;
use yrs::{Any, Doc, Map, ReadTxn, StateVector, Transact, Update};

use super::codec::encode;
use super::doc::{JournalState, Roots};
use super::op::Op;
use super::op_meta::OpMeta;

/// Номера клиента `yrs`. До правки за ключом оставалась вставка с большим номером.
const LOW: u64 = 1;
const HIGH: u64 = (1 << 53) - 1;

fn ms(millis: u32) -> Timestamp {
    Timestamp::from_millis(i64::from(millis))
}

/// `since` у подписок и записей чёрного списка, когда он не важен.
fn since() -> Timestamp {
    ms(1_000)
}

fn track(n: usize) -> TrackId {
    format!("11111111-1111-7111-8111-{n:012}").parse().unwrap()
}

fn artist(n: usize) -> ArtistId {
    format!("55555555-5555-7555-8555-{n:012}").parse().unwrap()
}

/// Реплика журнала в памяти с заданным номером клиента `yrs`.
struct Replica {
    doc: Doc,
    roots: Roots,
    device: DeviceId,
}

impl Replica {
    fn new(client: u64) -> Self {
        let doc = Doc::with_client_id(client);
        let roots = Roots::new(&doc);
        Self { doc, roots, device: DeviceId::new() }
    }

    fn write(&self, op: &Op, at: Timestamp) -> bool {
        let mut txn = self.doc.transact_mut();
        self.roots.write(&mut txn, op, &OpMeta::new(self.device, at)).unwrap()
    }

    fn state(&self) -> JournalState {
        self.roots.state(&self.doc.transact())
    }

    fn vector(&self) -> StateVector {
        self.doc.transact().state_vector()
    }

    /// Обмен обновлениями в обе стороны.
    fn sync(&self, other: &Self) {
        for (from, to) in [(self, other), (other, self)] {
            let known = to.doc.transact().state_vector();
            let update = from.doc.transact().encode_state_as_update_v1(&known);
            to.doc.transact_mut().apply_update(Update::decode_v1(&update).unwrap()).unwrap();
        }
    }

    /// Ключи корня `root`, под которыми что-то лежит, по порядку.
    fn keys(&self, root: &str) -> Vec<String> {
        let map = self.doc.get_or_insert_map(root);
        let mut keys: Vec<String> = map.keys(&self.doc.transact()).map(str::to_owned).collect();
        keys.sort();
        keys
    }

    /// Запись журнала «до Р1.6б»: ключ без суффикса, значение как у кода `main`.
    fn put_plain(&self, set: &Set, n: usize) {
        let map = self.doc.get_or_insert_map(set.root);
        let record = (set.record)(&OpMeta::new(self.device, ms(500)));
        map.insert(&mut self.doc.transact_mut(), (set.key)(n), Any::Buffer(Arc::from(record)));
    }
}

/// Одно из трёх множеств; чёрный список — двумя строками: трек и артист.
struct Set {
    name: &'static str,
    /// Корень документа.
    root: &'static str,
    add: fn(usize) -> Op,
    remove: fn(usize) -> Op,
    /// Ключ элемента `n` без суффикса установки.
    key: fn(usize) -> String,
    /// Запись слота: заголовок и поля, как их пишет код.
    record: fn(&OpMeta) -> Vec<u8>,
    /// Элементы состояния по порядку, каждый — строкой.
    shown: fn(&JournalState) -> Vec<String>,
    /// Элемент `n`, каким его показывает состояние.
    item: fn(usize) -> String,
}

fn lines<T: std::fmt::Debug>(items: &[T]) -> Vec<String> {
    items.iter().map(|item| format!("{item:?}")).collect()
}

fn subscription(n: usize) -> Subscription {
    Subscription { artist: artist(n), since: since() }
}

fn blocked_track(n: usize) -> BlockEntry {
    BlockEntry { target: BlockTarget::Track(track(n)), since: since() }
}

fn blocked_artist(n: usize) -> BlockEntry {
    BlockEntry { target: BlockTarget::Artist(artist(n)), since: since() }
}

const SETS: [Set; 4] = [
    Set {
        name: "likes",
        root: "likes",
        add: |n| Op::Like { track: track(n) },
        remove: |n| Op::Unlike { track: track(n) },
        key: |n| track(n).to_string(),
        record: |meta| encode(meta, |_| ()),
        shown: |state| lines(&state.likes),
        item: |n| format!("{:?}", track(n)),
    },
    Set {
        name: "subscriptions",
        root: "subscriptions",
        add: |n| Op::Subscribe(subscription(n)),
        remove: |n| Op::Unsubscribe { artist: artist(n) },
        key: |n| artist(n).to_string(),
        record: |meta| encode(meta, |w| w.since(since())),
        shown: |state| lines(&state.subscriptions),
        item: |n| format!("{:?}", subscription(n)),
    },
    Set {
        name: "blocked tracks",
        root: "blocks",
        add: |n| Op::Block(blocked_track(n)),
        remove: |n| Op::Unblock { target: BlockTarget::Track(track(n)) },
        key: |n| format!("track:{}", track(n)),
        record: |meta| encode(meta, |w| w.since(since())),
        shown: |state| lines(&state.blocklist),
        item: |n| format!("{:?}", blocked_track(n)),
    },
    Set {
        name: "blocked artists",
        root: "blocks",
        add: |n| Op::Block(blocked_artist(n)),
        remove: |n| Op::Unblock { target: BlockTarget::Artist(artist(n)) },
        key: |n| format!("artist:{}", artist(n)),
        record: |meta| encode(meta, |w| w.since(since())),
        shown: |state| lines(&state.blocklist),
        item: |n| format!("{:?}", blocked_artist(n)),
    },
];

/// Находка Р1.6б. Установка добавила элемент и сняла, другая — без общей истории —
/// добавила его же. Добавление, которого снятие не видело, остаётся — при любом
/// номере клиента: раньше при большем номере у снимавшей установки пропадало у обеих.
#[test]
fn an_independent_add_survives_a_remove_whichever_client_number_is_greater() {
    for set in &SETS {
        for (remover_client, adder_client) in [(HIGH, LOW), (LOW, HIGH)] {
            let (remover, adder) = (Replica::new(remover_client), Replica::new(adder_client));
            remover.write(&(set.add)(0), ms(1_000));
            remover.write(&(set.remove)(0), ms(2_000));
            adder.write(&(set.add)(0), ms(1_500));

            remover.sync(&adder);

            let expected = vec![(set.item)(0)];
            assert_eq!((set.shown)(&remover.state()), expected, "{}: the remover, client {remover_client}", set.name);
            assert_eq!((set.shown)(&adder.state()), expected, "{}: the adder, client {remover_client}", set.name);
        }
    }
}

/// Формат документа: элемент лежит в слоте `ключ@<установка>` — ключ длиннее на `@` и
/// номер установки. Ключ без суффикса новый код не пишет (его читают как слот журнала
/// до правки), снятие стирает слот, повторное добавление пишет его снова.
#[test]
fn a_set_element_lives_in_the_slot_of_the_installation_that_added_it() {
    for set in &SETS {
        let replica = Replica::new(LOW);
        let slot = format!("{}@{}", (set.key)(0), replica.device);

        assert!(replica.write(&(set.add)(0), ms(1_000)), "{}: add", set.name);
        assert_eq!(replica.keys(set.root), vec![slot.clone()], "{}: added", set.name);

        assert!(replica.write(&(set.remove)(0), ms(2_000)), "{}: remove", set.name);
        assert!(replica.keys(set.root).is_empty(), "{}: removed", set.name);

        assert!(replica.write(&(set.add)(0), ms(3_000)), "{}: add again", set.name);
        assert_eq!(replica.keys(set.root), vec![slot], "{}: added again", set.name);
    }
}

/// Снятие стирает слоты всех установок, которые видит: после обмена у обеих реплик
/// слоты и свой, и чужой, и снятие уносит оба. Страж: слот только своей установки —
/// и снятое возвращалось бы.
#[test]
fn a_remove_erases_every_slot_it_saw() {
    for set in &SETS {
        let (first, second) = (Replica::new(HIGH), Replica::new(LOW));
        first.write(&(set.add)(0), ms(1_000));
        second.write(&(set.add)(0), ms(1_500));
        first.sync(&second);

        first.write(&(set.remove)(0), ms(2_000));
        first.sync(&second);

        assert!((set.shown)(&first.state()).is_empty(), "{}: the first", set.name);
        assert!((set.shown)(&second.state()).is_empty(), "{}: the second", set.name);
    }
}

/// Журнал до правки читается, и снятие стирает его ключ без суффикса. Страж.
#[test]
fn a_set_of_the_old_layout_is_read_and_erased() {
    for set in &SETS {
        let replica = Replica::new(LOW);
        replica.put_plain(set, 0);
        replica.put_plain(set, 1);

        assert_eq!((set.shown)(&replica.state()), vec![(set.item)(0), (set.item)(1)], "{}: read", set.name);
        assert_eq!(replica.state().unreadable, 0, "{}: nothing unreadable", set.name);

        assert!(replica.write(&(set.remove)(0), ms(2_000)), "{}: remove", set.name);

        assert_eq!((set.shown)(&replica.state()), vec![(set.item)(1)], "{}: erased", set.name);
        assert_eq!(replica.keys(set.root), vec![(set.key)(1)], "{}: the other stays", set.name);
    }
}

/// Добавление элемента, у которого уже есть слот — журнала до правки или другой
/// установки, — ничего не меняет: `false`, документ прежний. Страж.
#[test]
fn an_add_of_an_element_that_has_a_slot_changes_nothing() {
    for set in &SETS {
        let old = Replica::new(LOW);
        old.put_plain(set, 0);
        let before = (old.state(), old.vector());
        assert!(!old.write(&(set.add)(0), ms(2_000)), "{}: the slot of the old layout", set.name);
        assert_eq!((old.state(), old.vector()), before, "{}: the old layout is unchanged", set.name);

        let (author, other) = (Replica::new(HIGH), Replica::new(LOW));
        author.write(&(set.add)(0), ms(1_000));
        author.sync(&other);
        let before = (other.state(), other.vector());
        assert!(!other.write(&(set.add)(0), ms(2_000)), "{}: the slot of another installation", set.name);
        assert_eq!((other.state(), other.vector()), before, "{}: another installation is unchanged", set.name);
    }
}

/// Подписка и запись чёрного списка несут `since`. Слотов у элемента может быть
/// несколько — действует слот с большим временем записи, как у регистров, а не тот,
/// чей номер клиента больше.
#[test]
fn the_newest_slot_decides_since() {
    let newer_since = ms(2_000);
    for (older_client, newer_client) in [(HIGH, LOW), (LOW, HIGH)] {
        let (older, newer) = (Replica::new(older_client), Replica::new(newer_client));
        older.write(&Op::Subscribe(subscription(0)), ms(1_000));
        older.write(&Op::Block(blocked_track(0)), ms(1_000));
        newer.write(&Op::Subscribe(Subscription { since: newer_since, ..subscription(0) }), ms(2_000));
        newer.write(&Op::Block(BlockEntry { since: newer_since, ..blocked_track(0) }), ms(2_000));

        older.sync(&newer);

        for replica in [&older, &newer] {
            let state = replica.state();
            assert_eq!(state.subscriptions, vec![Subscription { since: newer_since, ..subscription(0) }]);
            assert_eq!(state.blocklist, vec![BlockEntry { since: newer_since, ..blocked_track(0) }]);
        }
    }
}

/// Пакет видит собственные слоты: повтор внутри него ничего не добавляет, снятие
/// стирает только что добавленное, и добавление после снятия пишет слот снова.
#[test]
fn a_batch_sees_its_own_slots() {
    for set in &SETS {
        let replica = Replica::new(LOW);
        let meta = OpMeta::new(replica.device, ms(1_000));
        let ops = [(set.add)(0), (set.add)(0), (set.remove)(0), (set.add)(0), (set.add)(1)];

        let changed = replica.roots.write_all(&mut replica.doc.transact_mut(), &ops, &meta).unwrap();

        assert_eq!(changed, 4, "{}: the repeated add is not a change", set.name);
        assert_eq!((set.shown)(&replica.state()), vec![(set.item)(0), (set.item)(1)], "{}", set.name);
    }
}
