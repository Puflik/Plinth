//! Общее для прототипов C1: события пользователя, детерминированная нагрузка,
//! эталонная проекция и замер. Каждая библиотека реализует [`Journal`] —
//! одну и ту же модель, чтобы цифры в BENCH.md сравнивались честно.
//!
//! Модель — упрощённый журнал `plan.md` 17.4: лайки (множество треков),
//! плейлисты (имя + упорядоченный список треков) и история прослушиваний —
//! неизменяемые записи `трек;время;сколько слушали`, одинаковой строкой в
//! обеих библиотеках. Идентификаторы — строки UUID (36 знаков): худший
//! реалистичный случай для размера.

use std::collections::{BTreeMap, BTreeSet};
use std::time::{Duration, Instant};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    Like {
        track: String,
    },
    Unlike {
        track: String,
    },
    CreatePlaylist {
        playlist: String,
        name: String,
    },
    AddToPlaylist {
        playlist: String,
        track: String,
    },
    /// Перестановка: в обеих библиотеках нет операции «переместить», это
    /// удаление и вставка значения.
    MoveInPlaylist {
        playlist: String,
        from: u32,
        to: u32,
    },
    RemoveFromPlaylist {
        playlist: String,
        index: u32,
    },
    Play {
        record: String,
    },
}

/// Что строит из журнала база (C3): её пересобирают из журнала целиком.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Projection {
    pub likes: BTreeSet<String>,
    pub playlists: BTreeMap<String, (String, Vec<String>)>,
    pub play_counts: BTreeMap<String, u32>,
    pub plays: usize,
}

impl Projection {
    /// Прослушивание в журнале — строка `трек;время_мс;слушали_мс`.
    pub fn count_play(&mut self, record: &str) {
        let track = record.split(';').next().unwrap_or_default();
        *self.play_counts.entry(track.to_owned()).or_default() += 1;
        self.plays += 1;
    }
}

/// Журнал на одной CRDT-библиотеке. Каждое событие — отдельная транзакция:
/// так пишет приложение, одно действие пользователя — одна правка.
pub trait Journal: Sized {
    const NAME: &'static str;

    /// Общий корень: все устройства начинают с одного и того же документа.
    fn genesis() -> Self;
    /// Копия документа для другого устройства.
    fn fork(&self, device: u64) -> Self;
    fn apply(&mut self, event: &Event);
    /// Полный снимок в самом компактном формате библиотеки.
    fn save(&mut self) -> Vec<u8>;
    /// Изменения с прошлого вызова — то, что дописывается в файл журнала.
    fn save_incremental(&mut self) -> Vec<u8>;
    fn load(bytes: &[u8]) -> Self;
    /// Принять всё, чего нет, из `other` (синхронизация двух устройств).
    fn merge_from(&mut self, other: &mut Self);
    fn project(&self) -> Projection;
    /// Дополнительные размеры, если у библиотеки несколько форматов.
    fn other_sizes(&mut self) -> Vec<(&'static str, usize)> {
        Vec::new()
    }
}

/// Проекция прямо из событий — эталон, с которым сверяется каждая библиотека.
pub fn reference(events: &[Event]) -> Projection {
    let mut p = Projection::default();
    for event in events {
        match event {
            Event::Like { track } => {
                p.likes.insert(track.clone());
            }
            Event::Unlike { track } => {
                p.likes.remove(track);
            }
            Event::CreatePlaylist { playlist, name } => {
                p.playlists.insert(playlist.clone(), (name.clone(), Vec::new()));
            }
            Event::AddToPlaylist { playlist, track } => {
                if let Some((_, tracks)) = p.playlists.get_mut(playlist) {
                    tracks.push(track.clone());
                }
            }
            Event::MoveInPlaylist { playlist, from, to } => {
                if let Some((_, tracks)) = p.playlists.get_mut(playlist) {
                    let track = tracks.remove(*from as usize);
                    tracks.insert(*to as usize, track);
                }
            }
            Event::RemoveFromPlaylist { playlist, index } => {
                if let Some((_, tracks)) = p.playlists.get_mut(playlist) {
                    tracks.remove(*index as usize);
                }
            }
            Event::Play { record } => p.count_play(record),
        }
    }
    p
}

/// Детерминированный генератор (xorshift64*): одинаковая нагрузка для обеих библиотек.
pub struct Rng(u64);

impl Rng {
    pub fn new(seed: u64) -> Self {
        Self(seed.max(1))
    }

    pub fn next(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    pub fn below(&mut self, n: u64) -> u64 {
        self.next() % n
    }

    fn uuid(&mut self) -> String {
        let (a, b) = (self.next(), self.next());
        format!(
            "{:08x}-{:04x}-7{:03x}-{:04x}-{:012x}",
            a >> 32,
            (a >> 16) & 0xffff,
            a & 0xfff,
            (b >> 48) & 0x3fff | 0x8000,
            b & 0xffff_ffff_ffff
        )
    }
}

/// Нагрузка «как у живого человека»: фонотека 2 000 треков, популярные
/// слушаются чаще; 85 % событий — прослушивания, остальное — лайки и плейлисты.
pub fn workload(count: usize, seed: u64) -> Vec<Event> {
    let mut rng = Rng::new(seed);
    let library: Vec<String> = (0..2_000).map(|_| rng.uuid()).collect();
    let mut playlists: Vec<(String, u32)> = Vec::new();
    let mut liked: Vec<String> = Vec::new();
    let mut now_ms: u64 = 1_790_000_000_000;
    let mut events = Vec::with_capacity(count);

    // Популярность — квадрат равномерного: верх фонотеки слушают намного чаще.
    let pick = |rng: &mut Rng| {
        let u = rng.below(1_000_000) as f64 / 1_000_000.0;
        library[(u * u * library.len() as f64) as usize].clone()
    };

    while events.len() < count {
        now_ms += 60_000 + rng.below(240_000);
        let roll = rng.below(1_000);
        let event = match roll {
            0..850 => {
                let listened = 30_000 + rng.below(270_000);
                Event::Play { record: format!("{};{now_ms};{listened}", pick(&mut rng)) }
            }
            850..910 => {
                let track = pick(&mut rng);
                liked.push(track.clone());
                Event::Like { track }
            }
            910..920 if !liked.is_empty() => {
                let track = liked.swap_remove(rng.below(liked.len() as u64) as usize);
                Event::Unlike { track }
            }
            920..925 => {
                let playlist = rng.uuid();
                playlists.push((playlist.clone(), 0));
                Event::CreatePlaylist { playlist, name: format!("Playlist {}", playlists.len()) }
            }
            925..975 if !playlists.is_empty() => {
                let i = rng.below(playlists.len() as u64) as usize;
                playlists[i].1 += 1;
                Event::AddToPlaylist { playlist: playlists[i].0.clone(), track: pick(&mut rng) }
            }
            975..990 if playlists.iter().any(|p| p.1 >= 2) => {
                let candidates: Vec<usize> = (0..playlists.len()).filter(|&i| playlists[i].1 >= 2).collect();
                let i = candidates[rng.below(candidates.len() as u64) as usize];
                let len = u64::from(playlists[i].1);
                let (from, to) = (rng.below(len) as u32, rng.below(len) as u32);
                Event::MoveInPlaylist { playlist: playlists[i].0.clone(), from, to }
            }
            990..1000 if playlists.iter().any(|p| p.1 >= 1) => {
                let candidates: Vec<usize> = (0..playlists.len()).filter(|&i| playlists[i].1 >= 1).collect();
                let i = candidates[rng.below(candidates.len() as u64) as usize];
                let index = rng.below(u64::from(playlists[i].1)) as u32;
                playlists[i].1 -= 1;
                Event::RemoveFromPlaylist { playlist: playlists[i].0.clone(), index }
            }
            _ => continue,
        };
        events.push(event);
    }
    events
}

/// Результат замера одной библиотеки на одном объёме.
#[derive(Debug)]
pub struct Report {
    pub library: &'static str,
    pub events: usize,
    pub snapshot_bytes: usize,
    pub incremental_bytes: usize,
    pub other_sizes: Vec<(&'static str, usize)>,
    pub apply: Duration,
    pub save: Duration,
    pub load: Duration,
    pub project: Duration,
    pub matches_reference: bool,
    pub replicas_converge: bool,
}

/// Замер: применение, снимок, лог изменений, загрузка, проекция, сверка с
/// эталоном и сходимость двух устройств, правивших параллельно.
pub fn measure<J: Journal>(events: &[Event]) -> Report {
    let expected = reference(events);

    let mut journal = J::genesis();
    let started = Instant::now();
    for event in events {
        journal.apply(event);
    }
    let apply = started.elapsed();

    let started = Instant::now();
    let snapshot = journal.save();
    let save = started.elapsed();
    let other_sizes = journal.other_sizes();

    let started = Instant::now();
    let loaded = J::load(&snapshot);
    let load = started.elapsed();

    let started = Instant::now();
    let projection = loaded.project();
    let project = started.elapsed();

    // Лог изменений: файл журнала, в который каждое действие дописывается сразу.
    let mut appending = J::genesis();
    let mut incremental_bytes = appending.save_incremental().len();
    for event in events {
        appending.apply(event);
        incremental_bytes += appending.save_incremental().len();
    }

    Report {
        library: J::NAME,
        events: events.len(),
        snapshot_bytes: snapshot.len(),
        incremental_bytes,
        other_sizes,
        apply,
        save,
        load,
        project,
        matches_reference: projection == expected,
        replicas_converge: converge::<J>(),
    }
}

/// Два устройства от общего начала правят параллельно (в том числе одни и
/// те же плейлисты), потом обмениваются изменениями — проекции обязаны совпасть.
fn converge<J: Journal>() -> bool {
    let shared = workload(2_000, 7);
    let mut phone = J::genesis().fork(1);
    for event in &shared {
        phone.apply(event);
    }
    let mut tablet = phone.fork(2);

    // Правки поверх общего состояния: плейлисты из общей части у обоих.
    let base = reference(&shared);
    let mut rng = Rng::new(99);
    for (device, journal) in [(1_u64, &mut phone), (2, &mut tablet)] {
        let mut state = base.clone();
        for event in workload(1_000, 100 + device) {
            journal.apply(&event);
        }
        for _ in 0..200 {
            let Some((playlist, (_, tracks))) = state.playlists.iter_mut().find(|(_, (_, t))| t.len() >= 2) else {
                break;
            };
            let len = tracks.len() as u64;
            let (from, to) = (rng.below(len) as u32, rng.below(len) as u32);
            let event = Event::MoveInPlaylist { playlist: playlist.clone(), from, to };
            let track = tracks.remove(from as usize);
            tracks.insert(to as usize, track);
            journal.apply(&event);
        }
    }

    phone.merge_from(&mut tablet);
    tablet.merge_from(&mut phone);
    phone.project() == tablet.project()
}

/// Оба устройства переставляют один и тот же трек в плейлисте из пяти.
/// Возвращает длину плейлиста после синхронизации: 5 — перестановка
/// сохранила смысл, больше — трек размножился.
pub fn concurrent_move_len<J: Journal>() -> usize {
    let playlist = "p".to_owned();
    let mut phone = J::genesis().fork(1);
    phone.apply(&Event::CreatePlaylist { playlist: playlist.clone(), name: "Mix".to_owned() });
    for track in ["a", "b", "c", "d", "e"] {
        phone.apply(&Event::AddToPlaylist { playlist: playlist.clone(), track: track.to_owned() });
    }
    let mut tablet = phone.fork(2);
    phone.apply(&Event::MoveInPlaylist { playlist: playlist.clone(), from: 0, to: 4 });
    tablet.apply(&Event::MoveInPlaylist { playlist: playlist.clone(), from: 0, to: 2 });
    phone.merge_from(&mut tablet);
    phone.project().playlists[&playlist].1.len()
}

pub fn kib(bytes: usize) -> String {
    format!("{:.1} КБ", bytes as f64 / 1024.0)
}

pub fn millis(d: Duration) -> String {
    format!("{:.1} мс", d.as_secs_f64() * 1000.0)
}

/// Строка таблицы для BENCH.md.
pub fn row(r: &Report) -> String {
    let others: Vec<String> = r.other_sizes.iter().map(|(name, size)| format!("{name}: {}", kib(*size))).collect();
    format!(
        "| {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} |",
        r.library,
        r.events,
        kib(r.snapshot_bytes),
        kib(r.incremental_bytes),
        if others.is_empty() { "—".to_owned() } else { others.join(", ") },
        millis(r.apply),
        millis(r.save),
        millis(r.load),
        millis(r.project),
        if r.matches_reference { "да" } else { "НЕТ" },
        if r.replicas_converge { "да" } else { "НЕТ" },
    )
}

/// То, что вызывает каждый прототип из `main` и из экспорта `cdylib`.
pub fn run_all<J: Journal>() -> Vec<Report> {
    [1_000, 10_000, 50_000].iter().map(|&n| measure::<J>(&workload(n, 42))).collect()
}

#[cfg(test)]
mod tests {
    use super::{Event, reference, workload};

    #[test]
    fn workload_is_deterministic_and_mostly_plays() {
        let events = workload(10_000, 42);

        assert_eq!(events, workload(10_000, 42));
        let plays = events.iter().filter(|e| matches!(e, Event::Play { .. })).count();
        assert!((8_000..9_200).contains(&plays), "{plays}");
    }

    #[test]
    fn workload_indices_stay_valid() {
        // Эталон паникует на выходе за границы — значит, нагрузка корректна.
        let projection = reference(&workload(50_000, 42));

        assert!(!projection.playlists.is_empty());
        assert!(!projection.likes.is_empty());
    }
}
