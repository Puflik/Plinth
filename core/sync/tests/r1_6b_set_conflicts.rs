//! Р1.6б (`docs/work/r1-6b.md`): множества журнала — лайки, подписки, чёрный
//! список — держат «добавление побеждает» и у установок без общей истории.
//!
//! До правки ключ множества был один на всех. Новая установка добавила элемент и
//! сняла, восстановленная копия старой добавила его же — `yrs` разводит такие
//! вставки по случайному номеру клиента, и если оставалась снятая, добавление из
//! копии пропадало. Номер клиента у каждого открытия журнала свой, поэтому здесь
//! повторы (`ROUNDS`), а точные номера заданы в модульных тестах
//! (`src/journal/r1_6b_tests.rs`).
//!
//! Тесты зовут только прежний API.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r1_6_registers;

use std::collections::BTreeSet;

use plinth_library::model::{BlockEntry, BlockTarget, Subscription};
use plinth_sync::journal::{Journal, Op};
use plinth_types::{ArtistId, Timestamp, TrackId};

use r1_6_registers::{Replica, exchange, t1};

const ROUNDS: usize = 30;
const SEEDS: u64 = 4;
const STEPS: usize = 60;
const ELEMENTS: usize = 3;

fn track(n: usize) -> TrackId {
    format!("11111111-1111-7111-8111-{n:012}").parse().unwrap()
}

fn artist(n: usize) -> ArtistId {
    format!("55555555-5555-7555-8555-{n:012}").parse().unwrap()
}

/// `since` одинаков у всех записей: какой слот ни выбрать, элемент выглядит одинаково.
fn since() -> Timestamp {
    Timestamp::from_millis(1_000)
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

fn lines<T: std::fmt::Debug>(items: &[T]) -> Vec<String> {
    items.iter().map(|item| format!("{item:?}")).collect()
}

/// Одно из множеств; чёрный список — двумя строками: трек и артист.
struct Set {
    name: &'static str,
    add: fn(usize) -> Op,
    remove: fn(usize) -> Op,
    /// Элементы журнала по порядку, каждый — строкой.
    shown: fn(&Journal) -> Vec<String>,
    /// Элемент `n`, каким его показывает журнал.
    item: fn(usize) -> String,
}

const SETS: [Set; 4] = [
    Set {
        name: "likes",
        add: |n| Op::Like { track: track(n) },
        remove: |n| Op::Unlike { track: track(n) },
        shown: |journal| lines(&journal.state().likes),
        item: |n| format!("{:?}", track(n)),
    },
    Set {
        name: "subscriptions",
        add: |n| Op::Subscribe(subscription(n)),
        remove: |n| Op::Unsubscribe { artist: artist(n) },
        shown: |journal| lines(&journal.state().subscriptions),
        item: |n| format!("{:?}", subscription(n)),
    },
    Set {
        name: "blocked tracks",
        add: |n| Op::Block(blocked_track(n)),
        remove: |n| Op::Unblock { target: BlockTarget::Track(track(n)) },
        shown: |journal| lines(&journal.state().blocklist),
        item: |n| format!("{:?}", blocked_track(n)),
    },
    Set {
        name: "blocked artists",
        add: |n| Op::Block(blocked_artist(n)),
        remove: |n| Op::Unblock { target: BlockTarget::Artist(artist(n)) },
        shown: |journal| lines(&journal.state().blocklist),
        item: |n| format!("{:?}", blocked_artist(n)),
    },
];

/// Находка: одна установка добавила элемент и сняла, другая — без общей истории —
/// добавила его же. Снятие не видело чужого добавления, значит, элемент остаётся —
/// у обеих, в каком бы порядке ни обменялись. `ROUNDS` раз, с разными номерами
/// клиентов `yrs`: до правки при большем номере у снимавшей установки добавление
/// пропадало (в половине повторов).
#[test]
fn an_add_that_a_remove_never_saw_survives_in_every_set() {
    for set in &SETS {
        for round in 0..ROUNDS {
            let (mut remover, mut adder) = (Replica::new(), Replica::new());
            remover.journal.record(&(set.add)(0)).unwrap();
            remover.journal.record(&(set.remove)(0)).unwrap();
            adder.journal.record(&(set.add)(0)).unwrap();

            if round % 2 == 0 {
                exchange(&mut remover, &mut adder);
            } else {
                exchange(&mut adder, &mut remover);
            }

            let expected = vec![(set.item)(0)];
            assert_eq!((set.shown)(&remover.journal), expected, "{}: the remover, round {round}", set.name);
            assert_eq!((set.shown)(&adder.journal), expected, "{}: the adder, round {round}", set.name);
        }
    }
}

/// Лайк из журнала до правки (`registers-legacy`) читается, добавлять его ещё раз не
/// нужно, а снятие стирает ключ без суффикса — и копия того же журнала его не
/// возвращает. Страж.
#[test]
fn the_old_layout_is_read_erased_and_not_added_twice() {
    let mut replica = Replica::legacy();
    assert_eq!(replica.journal.state().likes, vec![t1()]);
    assert!(replica.journal.record(&Op::Like { track: t1() }).unwrap().is_none(), "the like is already there");

    assert!(replica.journal.record(&Op::Unlike { track: t1() }).unwrap().is_some());
    assert!(replica.journal.state().likes.is_empty());

    let copy = Replica::legacy();
    replica.take_from(&copy);
    assert!(replica.journal.state().likes.is_empty(), "the copy holds nothing the remove did not see");
}

/// Образец множества с метками (OR-set). Добавление элемента, которого у установки
/// нет, заводит метку; снятие стирает метки, которые установка видит; слияние
/// объединяет метки и стёртое. Элемент есть, пока осталась хоть одна метка.
#[derive(Clone, Default)]
struct Model {
    /// Метки добавлений, которые установка видит: (элемент, метка).
    alive: BTreeSet<(usize, u32)>,
    /// Стёртые метки: слияние их не возвращает.
    dead: BTreeSet<u32>,
}

impl Model {
    fn present(&self, element: usize) -> bool {
        self.alive.iter().any(|&(e, _)| e == element)
    }

    fn add(&mut self, element: usize, tag: u32) {
        if !self.present(element) {
            self.alive.insert((element, tag));
        }
    }

    fn remove(&mut self, element: usize) {
        let seen: Vec<(usize, u32)> = self.alive.iter().filter(|&&(e, _)| e == element).copied().collect();
        for (e, tag) in seen {
            self.alive.remove(&(e, tag));
            self.dead.insert(tag);
        }
    }

    fn merge(&mut self, other: &Model) {
        self.dead.extend(other.dead.iter().copied());
        self.alive.extend(other.alive.iter().copied());
        let dead = &self.dead;
        self.alive.retain(|(_, tag)| !dead.contains(tag));
    }

    /// Элементы по порядку номеров — как их показывает журнал: по ключу.
    fn view(&self, set: &Set) -> Vec<String> {
        (0..ELEMENTS).filter(|&e| self.present(e)).map(set.item).collect()
    }
}

struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }

    fn below(&mut self, n: usize) -> usize {
        usize::try_from(self.next() % u64::try_from(n).unwrap()).unwrap()
    }
}

/// Обмен двух установок: у журналов — настоящий, у образцов — слияние.
fn meet(replicas: &mut [Replica; 3], models: &mut [Model; 3], a: usize, b: usize) {
    let (low, high) = (a.min(b), a.max(b));
    let (left, right) = replicas.split_at_mut(high);
    exchange(&mut left[low], &mut right[0]);
    let (mine, theirs) = (models[low].clone(), models[high].clone());
    models[low].merge(&theirs);
    models[high].merge(&mine);
}

/// Три установки без общей истории пишут и снимают одни и те же элементы, обмениваясь
/// как придётся: на каждом шаге у каждой видно то же, что даёт образец, а после полного
/// обмена у всех одно и то же. 4 зерна × 60 шагов на каждое множество.
#[test]
fn three_installations_follow_the_add_wins_model() {
    for set in &SETS {
        for seed in 1..=SEEDS {
            let mut rng = Rng(seed.wrapping_mul(0x9E37_79B9_7F4A_7C15));
            let mut replicas = [Replica::new(), Replica::new(), Replica::new()];
            let mut models = [Model::default(), Model::default(), Model::default()];
            let mut tag = 0_u32;

            for step in 0..STEPS {
                let actor = rng.below(3);
                let element = rng.below(ELEMENTS);
                match rng.below(5) {
                    0 | 1 => {
                        replicas[actor].journal.record(&(set.add)(element)).unwrap();
                        tag += 1;
                        models[actor].add(element, tag);
                    }
                    2 | 3 => {
                        replicas[actor].journal.record(&(set.remove)(element)).unwrap();
                        models[actor].remove(element);
                    }
                    _ => {
                        let other = (actor + 1 + rng.below(2)) % 3;
                        meet(&mut replicas, &mut models, actor, other);
                    }
                }
                for (n, replica) in replicas.iter().enumerate() {
                    assert_eq!(
                        (set.shown)(&replica.journal),
                        models[n].view(set),
                        "{}: seed {seed}, step {step}, installation {n}",
                        set.name
                    );
                }
            }

            for (a, b) in [(0, 1), (1, 2), (0, 1)] {
                meet(&mut replicas, &mut models, a, b);
            }
            let expected = models[0].view(set);
            for (n, replica) in replicas.iter().enumerate() {
                assert_eq!(
                    (set.shown)(&replica.journal),
                    expected,
                    "{}: seed {seed}, installation {n} after the full exchange",
                    set.name
                );
            }
        }
    }
}
