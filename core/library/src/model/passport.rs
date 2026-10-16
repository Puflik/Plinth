use std::cmp::Reverse;
use std::collections::HashMap;
use std::time::Duration;

use plinth_types::{Mbid, TrackId};

use super::OnlineSource;
use crate::text::normalize;

/// Разница длительности одной песни в разных файлах: кодировщики по-разному
/// считают тишину в начале и конце.
const DURATION_TOLERANCE: Duration = Duration::from_secs(2);

/// Паспорт трека (C4, ADR 0007): по чему узнать трек, когда скан новой
/// установки выдаст его файлу новый ID. Лежит в журнале рядом с
/// пользовательскими данными трека; путей нет — они привязаны к устройству.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackPassport {
    pub track: TrackId,
    pub title: String,
    /// Исполнитель строкой, как в каталоге.
    pub artist: String,
    /// Альбом — различает одну песню с альбома и со сборника.
    pub album: Option<String>,
    pub duration: Option<Duration>,
    /// Запись MusicBrainz — если известна, узнаёт трек точнее всего.
    pub mbid: Option<Mbid>,
    /// Варианты у провайдеров (E3): по ним сетевой трек возвращается после
    /// переустановки без скана. Файлы устройства сюда не попадают.
    pub sources: Vec<OnlineSource>,
}

/// Пара, найденная [`TrackPassport::pair_up`]: индексы в `wanted` и `candidates` и
/// сходство (`likeness`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Pairing {
    pub wanted: usize,
    pub candidate: usize,
    pub score: u8,
}

/// Сравнение паспортов — одно на перепривязку после переустановки (C4) и на
/// скан, который узнаёт перенесённый файл (Р1.2): правила не должны разойтись.
impl TrackPassport {
    /// По чему паспорта сводятся в пары: нормализованные название и исполнитель.
    pub fn key(&self) -> (String, String) {
        (normalize(&self.title), normalize(&self.artist))
    }

    /// Насколько `other` похож на этот паспорт; `None` — это другой трек.
    /// Ключи сверяет вызывающий. MBID, если известен у обоих, — совпадает
    /// (+4); альбом у обоих один (+2); длительность, если известна у обоих, —
    /// в пределах двух секунд (+1), дальше — `None`. Симметрично.
    pub fn likeness(&self, other: &TrackPassport) -> Option<u8> {
        let mut score = 0;
        if let (Some(a), Some(b)) = (self.mbid, other.mbid) {
            if a != b {
                return None;
            }
            score += 4;
        }
        if let (Some(a), Some(b)) = (&self.album, &other.album)
            && normalize(a) == normalize(b)
        {
            score += 2;
        }
        if let (Some(a), Some(b)) = (self.duration, other.duration) {
            if a.abs_diff(b) > DURATION_TOLERANCE {
                return None;
            }
            score += 1;
        }
        Some(score)
    }

    /// Лучший из подходящих `candidates`; при равенстве — первый.
    pub fn best_match(&self, candidates: &[TrackPassport]) -> Option<usize> {
        let mut best: Option<(u8, usize)> = None;
        for (index, candidate) in candidates.iter().enumerate() {
            if let Some(score) = self.likeness(candidate)
                && best.is_none_or(|(top, _)| score > top)
            {
                best = Some((score, index));
            }
        }
        best.map(|(_, index)| index)
    }

    /// Сводит `wanted` с `candidates`; пара — тот же ключ (`key`) и `likeness` не
    /// `None`. Пары раздаются лучшими вперёд: по убыванию сходства; при равном
    /// сходстве первым идёт кандидат с меньшим `rank(индекс кандидата)`, затем
    /// желающий, стоящий раньше в `wanted`, затем кандидат, стоящий раньше. Каждый
    /// паспорт входит в пару не больше одного раза. Порядок результата не определён.
    ///
    /// Раздача «каждому желающему — лучший из оставшихся по очереди» отдавала
    /// кандидата первому по порядку, а не тому, кому он подходит больше (Р9в, №12).
    pub fn pair_up<R: Ord>(
        wanted: &[TrackPassport],
        candidates: &[TrackPassport],
        rank: impl Fn(usize) -> R,
    ) -> Vec<Pairing> {
        let mut by_key: HashMap<(String, String), Vec<usize>> = HashMap::new();
        for (index, candidate) in candidates.iter().enumerate() {
            by_key.entry(candidate.key()).or_default().push(index);
        }
        let mut pairs: Vec<(Pairing, R)> = Vec::new();
        for (index, passport) in wanted.iter().enumerate() {
            for &candidate in by_key.get(&passport.key()).map_or(&[][..], Vec::as_slice) {
                if let Some(score) = passport.likeness(&candidates[candidate]) {
                    pairs.push((Pairing { wanted: index, candidate, score }, rank(candidate)));
                }
            }
        }
        pairs.sort_by(|(a, a_rank), (b, b_rank)| {
            (Reverse(a.score), a_rank, a.wanted, a.candidate).cmp(&(Reverse(b.score), b_rank, b.wanted, b.candidate))
        });
        let mut wanted_taken = vec![false; wanted.len()];
        let mut candidate_taken = vec![false; candidates.len()];
        let mut result = Vec::new();
        for (pair, _) in pairs {
            if !wanted_taken[pair.wanted] && !candidate_taken[pair.candidate] {
                wanted_taken[pair.wanted] = true;
                candidate_taken[pair.candidate] = true;
                result.push(pair);
            }
        }
        result
    }
}
