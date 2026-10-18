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

/// Пара, найденная [`TrackPassport::pair_up`] или [`TrackPassport::pair_up_where`]:
/// индексы в `wanted` и `candidates` и сходство (`likeness`).
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

    /// Альбомы не противоречат: если известны у обоих и после нормализации различаются —
    /// это другая песня (с другого альбома или сборника), и пары нет; неизвестен хотя бы
    /// у одного — пара возможна, паспорт мог отстать от тегов. Слабее, чем «альбом тот же»
    /// у двойников скана, и не вето в `likeness`: перепривязка сводит не двойников, а
    /// установки с разными библиотеками (Р9г). Симметрично.
    pub fn albums_agree(&self, other: &TrackPassport) -> bool {
        match (&self.album, &other.album) {
            (Some(a), Some(b)) => normalize(a) == normalize(b),
            _ => true,
        }
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
        Self::pair_up_where(wanted, candidates, rank, |_, _| true)
    }

    /// [`pair_up`](Self::pair_up) с условием `allow(желающий, кандидат)`: пара, которой оно
    /// не позволяет, не заводится и никого не занимает — оба паспорта остаются другим парам.
    pub fn pair_up_where<R: Ord>(
        wanted: &[TrackPassport],
        candidates: &[TrackPassport],
        rank: impl Fn(usize) -> R,
        allow: impl Fn(&TrackPassport, &TrackPassport) -> bool,
    ) -> Vec<Pairing> {
        let mut by_key: HashMap<(String, String), Vec<usize>> = HashMap::new();
        for (index, candidate) in candidates.iter().enumerate() {
            by_key.entry(candidate.key()).or_default().push(index);
        }
        let mut pairs: Vec<(Pairing, R)> = Vec::new();
        for (index, passport) in wanted.iter().enumerate() {
            for &candidate in by_key.get(&passport.key()).map_or(&[][..], Vec::as_slice) {
                if let Some(score) = passport.likeness(&candidates[candidate])
                    && allow(passport, &candidates[candidate])
                {
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

#[cfg(test)]
mod tests {
    use super::*;

    fn passport(album: Option<&str>) -> TrackPassport {
        TrackPassport {
            track: TrackId::new(),
            title: "Intro".to_owned(),
            artist: "Band".to_owned(),
            album: album.map(str::to_owned),
            duration: Some(Duration::from_secs(60)),
            mbid: None,
            sources: Vec::new(),
        }
    }

    #[test]
    fn albums_agree_unless_both_are_known_and_differ() {
        let (one, two, bare) = (passport(Some("One")), passport(Some("Two")), passport(None));

        assert!(one.albums_agree(&passport(Some("ONE"))), "нормализация — как в likeness");
        assert!(!one.albums_agree(&two) && !two.albums_agree(&one));
        assert!(one.albums_agree(&bare) && bare.albums_agree(&one) && bare.albums_agree(&passport(None)));
    }

    /// Пара, которой условие не позволяет, никого не занимает: кандидат с лучшим счётом не
    /// проходит, и паспорт достаётся тому, кто проходит.
    #[test]
    fn a_pair_the_condition_forbids_takes_nobody() {
        let wanted = [passport(Some("One"))];
        let candidates = [passport(Some("One")), passport(None)];

        let pairs = TrackPassport::pair_up_where(&wanted, &candidates, |_| 0, |_, candidate| candidate.album.is_none());

        assert_eq!(pairs, [Pairing { wanted: 0, candidate: 1, score: 1 }]);
        assert_eq!(
            TrackPassport::pair_up(&wanted, &candidates, |_| 0),
            [Pairing { wanted: 0, candidate: 0, score: 3 }]
        );
    }
}
